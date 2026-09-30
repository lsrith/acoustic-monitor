// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.monitor

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.lsrith.acousticmonitor.audio.AudioPipeline
import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.classify.FrameResult
import org.lsrith.acousticmonitor.classify.YamnetClassifier
import org.lsrith.acousticmonitor.detect.AlertRepository
import org.lsrith.acousticmonitor.detect.DecisionConfig
import org.lsrith.acousticmonitor.detect.DecisionEngine
import org.lsrith.acousticmonitor.detect.EvidenceTimeline
import org.lsrith.acousticmonitor.detect.WindowVerdict
import org.lsrith.acousticmonitor.dsp.ProsodyAnalyzer
import org.lsrith.acousticmonitor.dsp.ProsodyFrame
import org.lsrith.acousticmonitor.dsp.VocalStress
import org.lsrith.acousticmonitor.integrity.MicIntegrity
import org.lsrith.acousticmonitor.integrity.MicIntegrityMonitor
import org.lsrith.acousticmonitor.integrity.MicState
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import org.lsrith.acousticmonitor.models.ModelRepository
import org.lsrith.acousticmonitor.speech.SpeechEngine
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Wires the audio spine to the fast path and the decision layer.
 *
 * Lives for as long as the process; the foreground service starts and stops it.
 * Later phases attach their own consumers to [AudioPipeline.windows] rather than
 * extending this class.
 */
class MonitorEngine(
    private val context: Context,
    models: ModelRepository,
    private val alerts: AlertRepository,
    val speech: SpeechEngine,
    private val speaker: org.lsrith.acousticmonitor.speaker.SpeakerEngine,
    private val diarization: org.lsrith.acousticmonitor.speaker.DiarizationEngine,
    val gate: StageGate,
) {

    val pipeline = AudioPipeline()
    private val classifier = YamnetClassifier(context, models)
    private val decision = DecisionEngine()
    private val integrity = MicIntegrityMonitor()
    private val prosody = ProsodyAnalyzer()
    private val calibrationStore = CalibrationStore(context)

    /** The one room profile. Both the loudness path and integrity read it. */
    private val roomProfile = RoomProfile()

    /** What the cheap detectors noticed, for the reasoning layer to read. */
    val timeline = EvidenceTimeline()

    /**
     * Rolling history of the decision signals, for the charts.
     *
     * Kept here rather than in the UI so it survives tab switches and
     * recomposition — a trace that resets whenever the screen is left tells
     * you nothing about what just happened. 240 windows at the 0.48 s hop is
     * roughly two minutes.
     */
    val history = SignalHistory()

    /**
     * Where cues die, for tuning.
     *
     * Answers a question the rest of the UI cannot: whether a missed event was
     * never scored by the model or was scored and then rejected by the bar.
     */
    val nearMiss = NearMissTracker()

    /** One-pole low-pass state for the treble split, carried across windows. */
    private var lowpass = 0f

    /**
     * Inference must finish inside one hop, or the pipeline falls behind the
     * microphone. That is the budget that actually matters here — not a round
     * number, but the rate the audio arrives at.
     */
    private val inferenceLatency = LatencyTracker(budgetMs = AudioSpec.hopSeconds * 1000)


    private val endToEndLatency = LatencyTracker(budgetMs = ACUTE_TARGET_MS)

    /** Identity and turn-taking, which run per speech segment rather than per window. */
    private val voiceIdLatency = LatencyTracker(64)
    private val diarizationLatency = LatencyTracker(64)

    /**
     * Persistence must outlive the service: an event still open when monitoring
     * stops is real data, and writing it on a scope that is being cancelled
     * would silently drop it.
     */
    private val storageScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _frame = MutableStateFlow<FrameResult?>(null)
    val frame: StateFlow<FrameResult?> = _frame.asStateFlow()

    private val _verdict = MutableStateFlow<WindowVerdict?>(null)
    val verdict: StateFlow<WindowVerdict?> = _verdict.asStateFlow()

    private val _level = MutableStateFlow(SILENCE_DBFS)
    /** Window RMS in dBFS. Useful sanity check that the mic is actually live. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _latency = MutableStateFlow(LatencySnapshot())
    val latency: StateFlow<LatencySnapshot> = _latency.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    /** Engine-level failure, distinct from the capture errors on [AudioPipeline.state]. */
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _micIntegrity = MutableStateFlow(MicIntegrity())
    /**
     * Whether the microphone is still hearing the room.
     *
     * A smothered phone reports a calm evening, which is indistinguishable from
     * a genuinely calm one unless something is watching for it.
     */
    val micIntegrity: StateFlow<MicIntegrity> = _micIntegrity.asStateFlow()

    private val _room = MutableStateFlow(RoomReading())
    /** The learned room and the present moment's distance from it. */
    val room: StateFlow<RoomReading> = _room.asStateFlow()

    private val _prosody = MutableStateFlow(ProsodyFrame(false, 0f, 0f, 0f, 0f, -96f, 0f))
    /** Latest vocal measures. Only meaningful while [ProsodyFrame.voiced]. */
    val prosodyFrame: StateFlow<ProsodyFrame> = _prosody.asStateFlow()

    /**
     * The single learning period, covering every baseline the app maintains.
     *
     * Mic integrity and ambient loudness each learn their own normal, but they
     * run on the same clock and finish together, so there is no reason to make
     * anyone track them separately. One wait, one progress bar, one control.
     */
    data class Calibration(
        /** Confidence in the learned room, 0..1. Rises continuously. */
        val progress: Float = 0f,
        /**
         * How long this room has been observed, in seconds.
         *
         * Counts up. There is no deadline to count down to any more — learning
         * never finishes, it only becomes better founded.
         */
        val observedSeconds: Int = 0,
        val ready: Boolean = false,
        /**
         * True before any calibration has been asked for. Distinct from
         * learning: nothing is happening and nothing will until someone starts
         * it from Settings.
         */
        val neverCalibrated: Boolean = true,
    )

    private val _calibration = MutableStateFlow(Calibration())
    val calibration: StateFlow<Calibration> = _calibration.asStateFlow()

    private var job: Job? = null

    /** Edge-trigger latch so one occlusion episode raises one alert. */
    private var integrityAlertRaised = false

    /** Survives start/stop: the room stays learned until someone relearns it. */
    private var calibrationRequested = false

    private var lastLoggedCue: org.lsrith.acousticmonitor.detect.CueGroup? = null
    private var loggedLoudness = false
    private var loggedMedia = false
    private var loggedLaughter = false
    private var loggedStress = false
    private var loggedEscalated = false
    private var lastLoggedSegmentId = 0L
    private var lastLoggedIntegrity: MicState? = null

    var config: DecisionConfig
        get() = decision.config
        set(value) { decision.config = value }

    /** Configured cue labels YAMNet has never emitted — a typo, not a quiet room. */
    val unmatchedLabels: List<String> get() = decision.unmatchedLabels

    data class LatencySnapshot(
        val inference: LatencyTracker.Stats = LatencyTracker.Stats(),
        val endToEnd: LatencyTracker.Stats = LatencyTracker.Stats(),
        val voiceId: LatencyTracker.Stats = LatencyTracker.Stats(),
        val diarization: LatencyTracker.Stats = LatencyTracker.Stats(),
    )

    init {
        // A room learned under a previous build is still that room.
        calibrationStore.load()?.let { saved ->
            roomProfile.restore(saved.micBaselineDbfs, saved.micBaselineHfRatio, saved.ageSeconds)
            calibrationRequested = true
            _calibration.value = Calibration(progress = 1f, ready = true, neverCalibrated = false)
        }
    }

    fun start(scope: CoroutineScope) {
        if (job != null) return
        try {
            classifier.open()
        } catch (t: Throwable) {
            Log.e(TAG, "cannot open classifier", t)
            _error.value = t.message ?: "Could not load the model"
            return
        }
        _error.value = null
        // Deliberately NOT resetting the baselines: a room's normal does not
        // change because monitoring was restarted. Relearning is a setup act,
        // triggered from Settings, not a side effect of pressing Start.
        decision.resetEventState()
        prosody.reset()
        integrityAlertRaised = false
        pipeline.start(scope)

        // Speech runs off pipeline.chunks, independently of the fast path — if
        // its models are absent it reports that and the rest carries on.
        speech.start(scope, pipeline)

        // Who was speaking, and how they took turns. Both models were built
        // and then read by nothing; they enter the timeline as observations
        // for the reasoning layer rather than as scores in the fusion, because
        // "an unenrolled voice, then a loud impact" is a judgement about
        // sequence and not a number that can be added to another number.
        //
        // Runs off the recogniser's own segmentation via a callback, so there
        // is no second microphone stream and no duplicated voice detection.
        // Deliberately off the speech thread: this inference is slow enough to
        // delay transcription if it ran inline.
        val segmentAudio = kotlinx.coroutines.channels.Channel<Pair<org.lsrith.acousticmonitor.speech.TranscriptSegment, FloatArray>>(
            capacity = kotlinx.coroutines.channels.Channel.CONFLATED,
        )
        speech.onSegmentAudio = { segment, samples ->
            segmentAudio.trySend(segment to samples)
        }
        scope.launch(Dispatchers.Default) {
            for ((segment, samples) in segmentAudio) {
                describeVoices(segment, samples)
            }
        }

        // Speech with no acoustic event is exactly the quiet-coercion case, so
        // transcripts enter the timeline regardless of whether anything fired.
        scope.launch {
            speech.segments.collect { segments ->
                val newest = segments.firstOrNull() ?: return@collect
                if (newest.id == lastLoggedSegmentId) return@collect
                lastLoggedSegmentId = newest.id
                val threat = newest.threat?.takeIf { !it.isEmpty }
                timeline.add(
                    newest.startEpochMs,
                    "speech",
                    buildString {
                        append('"').append(newest.text.take(160)).append('"')
                        threat?.let { append(" [lexicon ${it.topCategory?.displayName} %.2f]".format(it.score)) }
                    },
                )
            }
        }

        job = scope.launch(Dispatchers.Default) {
            pipeline.windows.collect { window ->
                // Measured once, learned once, then read by both consumers.
                val (levelDbfs, trebleShare) = measure(window.samples)
                _level.value = levelDbfs
                val roomNow = roomProfile.accept(levelDbfs, trebleShare, AudioSpec.hopSeconds)
                _room.value = roomNow

                val integrityNow = integrity.judge(levelDbfs, trebleShare, roomNow)
                _micIntegrity.value = integrityNow
                raiseIntegrityAlertIfNeeded(integrityNow)

                // The room is learned from every window whether or not the
                // classifier runs on it: confidence rises continuously, and
                // "ready" is simply the point at which the profile is trusted
                // enough to judge occlusion.
                updateCalibration(roomNow, now = System.currentTimeMillis())

                // Prosody is cheap DSP and is read on its own page, so it is
                // not behind the classifier gate.
                val voice = prosody.accept(window.samples, AudioSpec.SAMPLE_RATE)
                _prosody.value = voice

                if (!gate.current.classifier) {
                    // A frame from before the gate closed would be shown as
                    // if it were current; clearing it says "not running".
                    if (_frame.value != null) {
                        _frame.value = null
                        _verdict.value = null
                    }
                    return@collect
                }

                val result = classifier.classify(window) ?: return@collect
                inferenceLatency.record(result.inferenceMs)
                endToEndLatency.record(result.captureToResultMs)
                _frame.value = result
                _latency.value = LatencySnapshot(
                    inference = inferenceLatency.snapshot(),
                    endToEnd = endToEndLatency.snapshot(),
                    voiceId = voiceIdLatency.snapshot(),
                    diarization = diarizationLatency.snapshot(),
                )

                val now = System.currentTimeMillis()
                val (verdict, closed) = decision.accept(
                    frame = result,
                    room = roomNow,
                    threatScore = decayedThreat(now),
                    vocalStress = VocalStress.of(voice),
                    nowEpochMs = now,
                )
                _verdict.value = verdict
                history.record(verdict, now)
                nearMiss.accept(verdict, now)
                recordEvidence(verdict, roomNow, integrityNow, now)

                if (closed != null) {
                    storageScope.launch { alerts.record(closed, pipeline.ring) }
                }
            }
        }
        Log.i(TAG, "engine started")
    }

    private fun updateCalibration(roomNow: RoomReading, now: Long) {
        val nowReady = roomNow.confidence >= RoomReading.MIN_CONFIDENCE_FOR_OCCLUSION
        val wasReady = _calibration.value.ready
        _calibration.value = Calibration(
            progress = roomNow.confidence,
            observedSeconds = roomNow.ageSeconds.toInt(),
            ready = nowReady,
            neverCalibrated = !calibrationRequested,
        )
        if (nowReady && !wasReady) {
            val snap = roomProfile.snapshot()
            calibrationStore.save(
                CalibrationStore.Saved(
                    micBaselineDbfs = snap.levelDbfs,
                    micBaselineHfRatio = snap.trebleShare,
                    ambientBaselineDbfs = snap.levelDbfs,
                    ageSeconds = snap.ageSeconds,
                    completedAtEpochMs = now,
                )
            )
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        speech.stop()
        pipeline.stop()

        decision.flush()?.let { pending ->
            storageScope.launch { alerts.record(pending, pipeline.ring) }
        }

        classifier.close()
        // The callback outlives the coroutine that drained it, so it has to be
        // detached explicitly or the next run installs a second one.
        speech.onSegmentAudio = null
        speaker.close()
        diarization.close()
        _frame.value = null
        _verdict.value = null
        _level.value = SILENCE_DBFS
        Log.i(TAG, "engine stopped")
    }

    /**
     * Throws away the learned room and starts the warm-up again.
     *
     * The baseline is only as good as the conditions it was learned in, so this
     * has to be available after the fact rather than only at startup.
     */
    /**
     * Starts learning the room. The only thing that does.
     *
     * Whatever happens during the learning window becomes the baseline
     * everything is later judged against, so it has to be someone's decision
     * about conditions being representative — not a side effect of starting
     * monitoring, which would silently relearn the room every time.
     */
    fun calibrate() {
        calibrationStore.clear()
        roomProfile.reset()
        integrity.relearn()
        calibrationRequested = true
        _micIntegrity.value = MicIntegrity()
        _calibration.value = Calibration(neverCalibrated = false)
    }

    fun resetMetrics() {
        inferenceLatency.reset()
        endToEndLatency.reset()
        voiceIdLatency.reset()
        diarizationLatency.reset()
        _latency.value = LatencySnapshot()
        // The charts are measurements too. Resetting the numbers and leaving
        // five traces of pre-reset data on screen makes the reset a lie.
        history.clear()
        nearMiss.clear()
    }

    /**
     * The language path's current contribution, faded by how long ago it was
     * heard. Zero when speech models are absent, so the acoustic paths keep
     * working on their own.
     */
    /**
     * Raises one alert per episode of lost microphone integrity.
     *
     * Edge-triggered, not level-triggered: an occlusion lasting an hour is one
     * event, not seven thousand. The flag clears only once the microphone is
     * hearing the room again, so a recurrence after recovery does alert afresh.
     *
     * MUTED outranks OCCLUDED because no audio at all is total blindness,
     * whereas a muffled microphone still carries some signal.
     */
    private fun raiseIntegrityAlertIfNeeded(current: MicIntegrity) {
        if (!current.actionable) {
            if (current.state == MicState.OK || current.state == MicState.QUIET) {
                integrityAlertRaised = false
            }
            return
        }
        if (integrityAlertRaised) return
        integrityAlertRaised = true

        val severity = if (current.state == MicState.MUTED) "HIGH" else "MEDIUM"
        storageScope.launch {
            alerts.recordIntegrityFailure(
                state = current.state.name,
                severity = severity,
                secondsInState = current.secondsInState,
                levelDbfs = current.rmsDbfs,
                baselineDbfs = current.baselineDbfs,
                trebleShare = current.hfRatio,
                baselineTrebleShare = current.baselineHfRatio,
                nowEpochMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Logs notable changes for the reasoning layer.
     *
     * On change, not per window: an hour of an empty room should cost almost
     * nothing, and a timeline of six thousand identical lines would bury the
     * two that matter.
     */
    /**
     * Adds who spoke and how they took turns, when those models can say.
     *
     * Silent when a model is absent — an unavailable component contributes
     * nothing rather than a reassuring default, because a reader must never
     * mistake a missing signal for a calm one.
     */
    /**
     * Collects speech for enrolment, using the recogniser's own segmentation.
     *
     * Non-null while enrolling. Enrolment previously read a fixed five seconds
     * off the ring buffer, which captured whatever the room contained —
     * pauses, background, someone else — and an embedding built from that
     * describes the room rather than the person.
     */
    @Volatile
    private var enrolCollector: MutableList<FloatArray>? = null

    private val _enrolSeconds = MutableStateFlow(0.0)

    /** Seconds of actual speech gathered so far, not seconds elapsed. */
    val enrolSeconds: StateFlow<Double> = _enrolSeconds.asStateFlow()

    fun beginEnrolment() {
        enrolCollector = mutableListOf()
        _enrolSeconds.value = 0.0
    }

    /** Returns the utterances gathered, and stops collecting. */
    fun finishEnrolment(): List<FloatArray> {
        val gathered = enrolCollector.orEmpty().toList()
        enrolCollector = null
        return gathered
    }

    private fun describeVoices(segment: org.lsrith.acousticmonitor.speech.TranscriptSegment, samples: FloatArray) {
        // Enrolment feeds off the same segments, so it never opens a second
        // stream and never enrols silence.
        enrolCollector?.let { gathered ->
            gathered += samples
            _enrolSeconds.value = gathered.sumOf { it.size }.toDouble() / AudioSpec.SAMPLE_RATE
            return
        }

        if (!gate.current.voices) return

        // Both open() calls are idempotent and return false when the weights
        // are absent, so an uninstalled model simply contributes nothing.
        if (speaker.modelAvailable() && speaker.open()) {
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            speaker.identify(samples)?.let { match ->
                voiceIdLatency.record((android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                timeline.add(
                    segment.startEpochMs,
                    "voiceid",
                    if (match.isMatch) {
                        "matches enrolled %s (%.2f)".format(match.name, match.similarity)
                    } else {
                        // Stated, not omitted. An unrecognised voice in a home
                        // that has enrolled its adults is itself information.
                        "unrecognised voice (best %s %.2f)".format(match.name, match.similarity)
                    },
                )
            }
        }

        if (diarization.open()) {
            val started = android.os.SystemClock.elapsedRealtimeNanos()
            diarization.process(samples)?.let { d ->
                diarizationLatency.record((android.os.SystemClock.elapsedRealtimeNanos() - started) / 1_000_000.0)
                if (d.speakerCount > 0) {
                    timeline.add(
                        segment.startEpochMs,
                        "voices",
                        // Turn-taking rhythm is what separates a shouted
                        // monologue from a shouting match, and no level
                        // measurement can tell them apart.
                        "%d speaker(s), %.0f turns/min, overlap %.2f"
                            .format(d.speakerCount, d.turnsPerMinute, d.overlapRatio),
                    )
                }
            }
        }
    }

    private fun recordEvidence(
        verdict: WindowVerdict,
        room: RoomReading,
        integrityNow: MicIntegrity,
        nowEpochMs: Long,
    ) {
        verdict.topGroup?.takeIf { it.raw > EVIDENCE_CUE_FLOOR }?.let {
            if (it.group != lastLoggedCue) {
                lastLoggedCue = it.group
                timeline.add(nowEpochMs, "cue", "${it.label} %.2f".format(it.raw))
            }
        }

        if (room.deviationDb > EVIDENCE_LOUDNESS_DB && !loggedLoudness) {
            loggedLoudness = true
            timeline.add(nowEpochMs, "loudness", "%+.0f dB above room".format(room.deviationDb))
        } else if (room.deviationDb < EVIDENCE_LOUDNESS_DB / 2) {
            loggedLoudness = false
        }

        if (verdict.mediaScore > EVIDENCE_MEDIA_FLOOR && !loggedMedia) {
            loggedMedia = true
            timeline.add(nowEpochMs, "media", "media %.2f, rising".format(verdict.mediaScore))
        } else if (verdict.mediaScore < EVIDENCE_MEDIA_FLOOR / 2) {
            loggedMedia = false
        }

        // Laughter is one of the few cues separating roughhousing from
        // assault, and the model cannot weigh it if it never appears. Logged
        // as an observation, not a conclusion: the reasoning layer decides

        if (verdict.laughterScore > EVIDENCE_LAUGHTER_FLOOR && !loggedLaughter) {
            loggedLaughter = true
            timeline.add(nowEpochMs, "laughter", "laughter %.2f".format(verdict.laughterScore))
        } else if (verdict.laughterScore < EVIDENCE_LAUGHTER_FLOOR / 2) {
            loggedLaughter = false
        }

        // A strained voice with no cue behind it is exactly what a threshold
        // discards and a reader would not.
        if (verdict.vocalStress > EVIDENCE_STRESS_FLOOR && !loggedStress) {
            loggedStress = true
            timeline.add(nowEpochMs, "voice", "strained %.2f".format(verdict.vocalStress))
        } else if (verdict.vocalStress < EVIDENCE_STRESS_FLOOR / 2) {
            loggedStress = false
        }

        // The sustained path, which is invisible in any single window. Entered
        // on the crossing only, so a long episode is one line rather than
        // hundreds and the ordering stays readable.
        if (verdict.accumulation.escalated != loggedEscalated) {
            loggedEscalated = verdict.accumulation.escalated
            timeline.add(
                nowEpochMs,
                "sustained",
                if (loggedEscalated) {
                    "concern sustained over %d windows without recovery"
                        .format(verdict.accumulation.contributingWindows)
                } else {
                    "recovered"
                },
            )
        }

        if (integrityNow.state != lastLoggedIntegrity) {
            lastLoggedIntegrity = integrityNow.state
            timeline.add(nowEpochMs, "mic", integrityNow.state.name.lowercase())
        }
    }

    private fun decayedThreat(nowEpochMs: Long): Float {
        val timed = speech.latestThreat.value ?: return 0f
        val ageSeconds = (nowEpochMs - timed.atEpochMs) / 1000.0
        if (ageSeconds < 0) return timed.assessment.score
        val decay = exp(-ageSeconds / decision.config.threatDecaySeconds)
        return (timed.assessment.score * decay).toFloat()
    }

    /**
     * Level and treble share for one window, computed once.
     *
     * Both were previously derived twice from the same samples — here for the
     * loudness path and again inside the integrity monitor.
     */
    private fun measure(samples: FloatArray): Pair<Float, Float> {
        var sumSq = 0.0
        var lowSq = 0.0
        var highSq = 0.0
        for (x in samples) {
            lowpass += LP_ALPHA * (x - lowpass)
            val high = x - lowpass
            sumSq += x.toDouble() * x
            lowSq += lowpass.toDouble() * lowpass
            highSq += high.toDouble() * high
        }
        val rms = sqrt(sumSq / samples.size.coerceAtLeast(1))
        val db = if (rms <= 1e-9) SILENCE_DBFS
        else (20.0 * log10(rms)).toFloat().coerceAtLeast(SILENCE_DBFS)
        val bandTotal = lowSq + highSq
        val treble = if (bandTotal <= 1e-12) 0f else (highSq / bandTotal).toFloat()
        return db to treble
    }

    companion object {

        private const val ACUTE_TARGET_MS = 1_000.0

        private const val TAG = "MonitorEngine"
        const val SILENCE_DBFS = -96f

        /** Matches the warm-up configured on both baselines. */
        const val CALIBRATION_SECONDS = 300f

        private const val EVIDENCE_CUE_FLOOR = 0.30f
        private const val EVIDENCE_LOUDNESS_DB = 8f
        private const val EVIDENCE_LAUGHTER_FLOOR = 0.30f
        private const val EVIDENCE_STRESS_FLOOR = 0.45f
        private const val EVIDENCE_MEDIA_FLOOR = 0.40f

        /** ~1 kHz split at 16 kHz: alpha = 1 - exp(-2*pi*fc/fs). */
        private val LP_ALPHA: Float =
            (1.0 - exp(-2.0 * Math.PI * 1000.0 / AudioSpec.SAMPLE_RATE)).toFloat()
    }
}
