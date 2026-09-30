// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.speech

import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineMoonshineModelConfig
import com.k2fsa.sherpa.onnx.OfflineOmnilingualAsrCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.lsrith.acousticmonitor.audio.AudioPipeline
import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.detect.ThreatAssessment
import org.lsrith.acousticmonitor.detect.ThreatScorer
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.models.ModelRepository
import java.io.File

/**
 * The context path: speech segmentation followed by on-device transcription.
 *
 * Reads [AudioPipeline.chunks] rather than the overlapping windows the fast path
 * uses — VAD consumes a continuous stream, and replaying half of every buffer
 * would corrupt its state.
 *
 * Segmentation matters for cost as much as accuracy: transcribing continuously
 * would burn the battery on silence. Whisper only ever sees audio Silero has
 * already judged to contain speech.
 */
class SpeechEngine(
    private val models: ModelRepository,
    private val gate: org.lsrith.acousticmonitor.monitor.StageGate,
) {

    private var vad: Vad? = null
    private var recognizer: OfflineRecognizer? = null

    /**
     * Omnilingual, held alongside the primary recogniser rather than instead
     * of it.
     *
     * Whisper identifies the language reliably and transcribes English well;
     * Omnilingual covers Malay and Tamil but, having no language conditioning
     * in this binding, writes English back in the wrong script. Neither is
     * usable alone in a Singapore home. Run together, each does the part it is
     * good at: Whisper says what language it heard, and anything it reports as
     * not-English is re-decoded by Omnilingual.
     *
     * Costs a second decode only on non-English segments. Same audio, same
     * segmentation, no second microphone stream.
     */
    private var secondary: OfflineRecognizer? = null

    private val _secondaryActive = MutableStateFlow(false)

    /** Whether the Malay/Tamil pass is available and loaded. */
    val secondaryActive: StateFlow<Boolean> = _secondaryActive.asStateFlow()

    private val asrLatency = LatencyTracker()

    /**
     * The second recogniser, timed separately.
     *
     * The segment figure is the wall time of both running together, which
     * cannot show whether they are actually overlapping or merely queueing
     * behind each other for the same cores. Two separate timings and one
     * wall-clock total can.
     */
    private val altLatency = LatencyTracker()

    private val _altStats = MutableStateFlow(LatencyTracker.Stats())
    val altAsrStats: StateFlow<LatencyTracker.Stats> = _altStats.asStateFlow()
    private var nextId = 1L

    private val _segments = MutableStateFlow<List<TranscriptSegment>>(emptyList())
    /** Most recent transcriptions, newest first, capped at [MAX_SEGMENTS]. */
    val segments: StateFlow<List<TranscriptSegment>> = _segments.asStateFlow()

    private val _speaking = MutableStateFlow(false)
    val speaking: StateFlow<Boolean> = _speaking.asStateFlow()

    private val _asrStats = MutableStateFlow(LatencyTracker.Stats())
    val asrStats: StateFlow<LatencyTracker.Stats> = _asrStats.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Receives each completed speech segment with its unmodified audio.
     *
     * Lets the speaker and diarization stages run off the same segmentation
     * the recogniser used, without opening a second microphone stream or
     * duplicating voice-activity detection. Invoked on the speech thread, so
     * a consumer that does real work must hand it off.
     */
    @Volatile
    var onSegmentAudio: ((TranscriptSegment, FloatArray) -> Unit)? = null

    /** Long-lived scope for derived state only; no work runs on it. */
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val threatScorer = ThreatScorer()

    /** Applied before voice detection, not after — see [AutoGain]. */
    private val autoGain = AutoGain()

    private val _inputGain = MutableStateFlow(1f)
    /** Gain the speech path is currently applying. Large means very quiet input. */
    val inputGain: StateFlow<Float> = _inputGain.asStateFlow()

    /** A threat reading together with when it was heard, so it can be aged out. */
    data class TimedThreat(val assessment: ThreatAssessment, val atEpochMs: Long)

    private val _latestThreat = MutableStateFlow<TimedThreat?>(null)

    /**
     * Most recent non-empty threat reading.
     *
     * Fusion decays this by age rather than latching it — language from ten
     * minutes ago is context, not an active signal.
     */
    val latestThreat: StateFlow<TimedThreat?> = _latestThreat.asStateFlow()

    private var job: Job? = null

    fun start(scope: CoroutineScope, pipeline: AudioPipeline) {
        if (job != null) return
        if (!openModels()) {
            Log.e(TAG, "speech models unavailable: ${_error.value}")
            return
        }
        autoGain.reset()

        job = scope.launch(Dispatchers.Default) {
            // Silero consumes exactly windowSize samples per call, so incoming
            // chunks are re-blocked rather than passed through as they arrive.
            val window = FloatArray(SILERO_WINDOW)
            var filled = 0

            pipeline.chunks.collect { chunk ->
                // Applied here, on the loop's own thread, so nothing is
                // released while it is in use.
                val engine = vad ?: return@collect
                var offset = 0
                while (offset < chunk.samples.size) {
                    val take = minOf(SILERO_WINDOW - filled, chunk.samples.size - offset)
                    System.arraycopy(chunk.samples, offset, window, filled, take)
                    filled += take
                    offset += take
                    if (filled < SILERO_WINDOW) continue

                    filled = 0
                    // Gain BEFORE detection. Normalising only the recogniser's
                    // input left the detector itself starved, so quiet
                    // far-field speech produced no segment at all.
                    autoGain.process(window)
                    _inputGain.value = autoGain.gain
                    engine.acceptWaveform(window)
                    _speaking.value = engine.isSpeechDetected()

                    while (!engine.empty()) {
                        val segment = engine.front()
                        engine.pop()
                        transcribe(segment.samples)
                    }
                }
            }
        }
        Log.i(TAG, "speech engine started ($PRIMARY_LABEL)")
    }

    fun stop() {
        job?.cancel()
        job = null
        // Flush so a final utterance in progress is not silently discarded.
        runCatching { vad?.flush() }
        closeModels()
        _speaking.value = false
    }

    fun clearTranscript() {
        _segments.value = emptyList()
        _latestThreat.value = null
    }

    fun resetMetrics() {
        asrLatency.reset()
        _asrStats.value = LatencyTracker.Stats()
    }

    /**
     * Brings a speech segment up to a consistent level before recognition.
     *
     * The microphone is opened UNPROCESSED so that automatic gain control does
     * not flatten the loudness cues shout detection depends on. The cost is
     * that speech from across a room arrives far quieter than the near-field
     * audio these models were trained on, which degrades recognition badly.
     *
     * Normalising here gives the recogniser the level it expects without
     * touching the raw buffer the acoustic path reads — the two stages want
     * opposite things from the same microphone, and this is where they diverge.
     *
     * Peak-based with a ceiling on the gain applied, so near-silence is not
     * amplified into noise.
     */
    private fun normaliseForAsr(samples: FloatArray): Triple<FloatArray, Float, Float> {
        var peak = 0f
        for (s in samples) {
            val a = kotlin.math.abs(s)
            if (a > peak) peak = a
        }
        if (peak < MIN_PEAK_TO_NORMALISE) return Triple(samples, 1f, peak)
        val gain = (TARGET_PEAK / peak).coerceAtMost(MAX_ASR_GAIN)
        if (gain <= 1.05f) return Triple(samples, 1f, peak)
        return Triple(FloatArray(samples.size) { i -> (samples[i] * gain).coerceIn(-1f, 1f) }, gain, peak)
    }

    /**
     * What each recogniser reported as the language of the last segment.
     *
     * There was a manual language dropdown here. It was the wrong shape for
     * the problem: this device runs unattended in a home where English,
     * Mandarin, Malay and Tamil are spoken, often in the same sentence, and
     * nobody is present to pick one. A setting that has to be right in advance
     * cannot help a system whose whole job is to run without anyone watching.
     *
     * So the language is reported, not chosen — and a recogniser that cannot
     * identify it is one that cannot be deployed here, which is a finding
     * about the model rather than a gap in the UI.
     */
    private val _detectedLanguage = MutableStateFlow<String?>(null)
    val detectedLanguage: StateFlow<String?> = _detectedLanguage.asStateFlow()

    private suspend fun transcribe(raw: FloatArray) {
        val engine = recognizer ?: return
        if (raw.isEmpty()) return

        // Voice detection has already done its work by the time a segment
        // reaches here; what is gated is the decode. With transcription off
        // the utterance still goes to the voice consumers, because speaker
        // identity and turn-taking need the segmentation, not the words.
        val stages = gate.current
        if (!stages.transcription) {
            if (stages.voices) {
                onSegmentAudio?.invoke(
                    TranscriptSegment(
                        id = nextId++,
                        startEpochMs = System.currentTimeMillis(),
                        audioSeconds = raw.size.toDouble() / AudioSpec.SAMPLE_RATE,
                        text = "",
                        language = "",
                        emotion = "",
                        asrMs = 0.0,
                    ),
                    raw,
                )
            }
            return
        }
        // Segments arrive already gain-corrected; this only trims any
        // remaining headroom. The meaningful figure is AutoGain's.
        val (samples, trimGain, _) = normaliseForAsr(raw)
        val appliedGain = autoGain.gain * trimGain
        val rawPeak = autoGain.lastRawPeak

        // Both recognisers read the same audio at the same time. Sequentially
        // this would add Omnilingual's cost to every segment; in parallel the
        // segment costs whichever of the two is slower, and they use different
        // thread pools inside ONNX.
        val startNanos = SystemClock.elapsedRealtimeNanos()
        // On the engine's own scope, not a child of this one. coroutineScope
        // waits for its children before returning, so wrapping the async in it
        // ran the two decodes back to back — the opposite of the intent, and
        // with the second recogniser going first.
        val alternate = secondary?.let { alt ->
            engineScope.async {
                val begun = SystemClock.elapsedRealtimeNanos()
                decode(alt, samples).also {
                    altLatency.record((SystemClock.elapsedRealtimeNanos() - begun) / 1_000_000.0)
                    _altStats.value = altLatency.snapshot()
                }
            }
        }

        val result = try {
            val stream = engine.createStream()
            stream.acceptWaveform(samples, AudioSpec.SAMPLE_RATE)
            engine.decode(stream)
            val r = engine.getResult(stream)
            stream.release()
            r
        } catch (t: Throwable) {
            Log.e(TAG, "transcription failed", t)
            alternate?.cancel()
            return
        }
        val altText = alternate?.await().orEmpty()
        val asrMs = (SystemClock.elapsedRealtimeNanos() - startNanos) / 1_000_000.0

        val text = result.text.trim()
        // Nothing was heard by either. An empty primary with a non-empty
        // alternate still counts — that is the case this pair exists for.
        if (text.isEmpty() && altText.isEmpty()) return

        val language = result.lang?.takeIf { it.isNotBlank() }.orEmpty()

        asrLatency.record(asrMs)
        _asrStats.value = asrLatency.snapshot()

        // Scored over both readings, taking the stronger. Neither transcript
        // is authoritative, so treating either one as the only text to check
        // would discard a threat the other model heard — and the paths are OR,
        // not AND.
        val assessment = threatScorer.score(text)
        val altAssessment = if (altText.isEmpty()) null else threatScorer.score(altText)
        val strongest = listOfNotNull(assessment, altAssessment).maxByOrNull { it.score }
        if (strongest != null && !strongest.isEmpty) {
            _latestThreat.value = TimedThreat(strongest, System.currentTimeMillis())
        }

        _detectedLanguage.value = language.takeIf { it.isNotEmpty() }

        val segment = TranscriptSegment(
            id = nextId++,
            startEpochMs = System.currentTimeMillis(),
            audioSeconds = samples.size.toDouble() / AudioSpec.SAMPLE_RATE,
            text = text,
            language = language,
            emotion = result.emotion.orEmpty(),
            asrMs = asrMs,
            threat = assessment,
            // Names whichever model's words these actually are, so a row can
            // be attributed rather than assumed.
            modelLabel = PRIMARY_LABEL,
            altText = altText,
            altModelLabel = if (altText.isEmpty()) "" else SECOND_PASS_LABEL,
            altThreat = altAssessment,
            asrGain = appliedGain,
            rawPeak = rawPeak,
        )
        _segments.value = (listOf(segment) + _segments.value).take(MAX_SEGMENTS)

        // The raw segment, for consumers that need the audio rather than the
        // text — speaker identity and turn-taking. Handed over rather than
        // re-read from a second stream, and `raw` deliberately: the ASR copy
        // has been gain-normalised, which would skew an embedding.
        onSegmentAudio?.invoke(segment, raw)
    }

    private fun openModels(): Boolean {
        val vadSpec = ModelCatalog.SILERO_VAD
        if (!models.isCached(vadSpec)) {
            _error.value = "${vadSpec.displayName} is not downloaded"
            return false
        }

        val asrSpec = ModelCatalog.WHISPER_BASE
        if (!models.isCached(asrSpec)) {
            _error.value = "${asrSpec.displayName} is not downloaded or is incomplete"
            return false
        }

        // sherpa does not throw on a bad path — it returns a recogniser with a
        // null native handle, and the process segfaults later at createStream.
        // A missing file has to be caught here, in Kotlin, where it can be
        // reported instead of killing the app.
        if (asrSpec.kind == org.lsrith.acousticmonitor.models.ModelSpec.Kind.TAR_BZ2) {
            val dir = models.dirFor(asrSpec)
            val missing = asrSpec.requiredFiles.filterNot { File(dir, it).isFile }
            if (missing.isNotEmpty()) {
                _error.value = "${asrSpec.displayName} is missing ${missing.joinToString()}"
                Log.e(TAG, "refusing to open ${asrSpec.id}: missing ${missing.joinToString()}")
                return false
            }
        }

        return try {
            vad = Vad(
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = models.fileFor(vadSpec).absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.35f,
                        minSpeechDuration = 0.25f,
                        windowSize = SILERO_WINDOW,
                        maxSpeechDuration = 12f,
                    ),
                    sampleRate = AudioSpec.SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu",
                )
            )
            recognizer = OfflineRecognizer(config = recognizerConfig(asrSpec.let { models.dirFor(it) }))
            openSecondary()
            _error.value = null
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not open speech models", t)
            _error.value = t.message ?: "Could not load speech models"
            closeModels()
            false
        }
    }

    /**
     * Loads Omnilingual as the non-English pass, when it is present.
     *
     * Optional by design: absent weights mean Whisper's own text stands for
     * every language, which is worse for Malay and Tamil but never a failure.
     * Only meaningful behind Whisper — Omnilingual as the primary has nothing
     * to tell it which segments are foreign.
     */
    /** One decode on one recogniser. Returns empty rather than throwing. */
    private fun decode(engine: OfflineRecognizer, samples: FloatArray): String =
        runCatching {
            val stream = engine.createStream()
            stream.acceptWaveform(samples, AudioSpec.SAMPLE_RATE)
            engine.decode(stream)
            val text = engine.getResult(stream).text.trim()
            stream.release()
            text
        }.onFailure { Log.w(TAG, "second recogniser failed: ${it.message}") }.getOrDefault("")

    private fun openSecondary() {
        secondary = null
        _secondaryActive.value = false
        val spec = ModelCatalog.OMNILINGUAL_300M
        if (!models.isCached(spec)) return
        val dir = models.dirFor(spec)
        if (spec.requiredFiles.any { !File(dir, it).isFile }) {
            Log.w(TAG, "omnilingual incomplete; second pass disabled")
            return
        }
        runCatching {
            secondary = OfflineRecognizer(config = omnilingualConfig(dir))
            _secondaryActive.value = true
            Log.i(TAG, "second pass ready: ${spec.displayName}")
        }.onFailure {
            Log.w(TAG, "second pass unavailable: ${it.message}")
            secondary = null
            _secondaryActive.value = false
        }
    }

    private fun omnilingualConfig(dir: File) = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = AudioSpec.SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            omnilingual = OfflineOmnilingualAsrCtcModelConfig(
                model = File(dir, "model.int8.onnx").absolutePath,
            ),
            tokens = File(dir, "tokens.txt").absolutePath,
            numThreads = ASR_THREADS,
        ),
    )

    private fun recognizerConfig(dir: File) = OfflineRecognizerConfig(
        featConfig = FeatureConfig(sampleRate = AudioSpec.SAMPLE_RATE, featureDim = 80),
        modelConfig = OfflineModelConfig(
            whisper = OfflineWhisperModelConfig(
                encoder = File(dir, "base-encoder.int8.onnx").absolutePath,
                decoder = File(dir, "base-decoder.int8.onnx").absolutePath,
            ),
            tokens = File(dir, "base-tokens.txt").absolutePath,
            modelType = "whisper",
            numThreads = ASR_THREADS,
        ),
    )

    private fun closeModels() {
        runCatching { vad?.release() }
        runCatching { secondary?.release() }
        secondary = null
        _secondaryActive.value = false
        runCatching { recognizer?.release() }
        vad = null
        recognizer = null
    }

    companion object {
        /**
         * The recogniser is fixed, not chosen.
         *
         * There was a dropdown here. Once Whisper tiny and Moonshine went and
         * Omnilingual became the second pass rather than an alternative, it
         * offered exactly one viable option — and the one it also offered
         * could not identify the language it was hearing, so picking it was
         * always a mistake. An arrangement with no valid alternative is
         * configuration in name only.
         */
        const val PRIMARY_LABEL = "Whisper base"
        const val SECOND_PASS_LABEL = "Omnilingual"

        private const val TAG = "SpeechEngine"

        /** Silero's fixed frame at 16 kHz. */
        private const val SILERO_WINDOW = 512

        /**
         * Language tags whose transcription is left to the primary.
         *
         * Whisper reports two-letter codes; the leading pair is compared so a
         * regional variant does not read as foreign.
         */
        private val ENGLISH_TAGS = setOf("en")

        /**
         * Threads for each recogniser, sized so the two together do not
         * oversubscribe the device.
         *
         * Both now decode the same segment at the same time. Two fixed threads
         * each was chosen when only one ran; concurrently that is four ONNX
         * worker threads competing, on top of YAMNet, the VAD, the speaker and
         * diarization stages and — when it is loaded — the reasoning model.
         * Oversubscription does not fail, it just makes both recognisers
         * slower than either was alone, which would have shown up as the
         * concurrency saving nothing.
         *
         * Half the cores, shared between the two, and never fewer than one
         * each. On this device's 8 cores that is 2 apiece — the same as before
         * when one ran alone, and no worse when both do.
         */
        private val ASR_THREADS: Int =
            (Runtime.getRuntime().availableProcessors() / 4).coerceIn(1, 4)
        private const val MAX_SEGMENTS = 100

        /** Level a normalised segment is brought to before recognition. */
        private const val TARGET_PEAK = 0.85f

        /** Below this the segment is essentially silence; amplifying it only raises noise. */
        private const val MIN_PEAK_TO_NORMALISE = 0.005f

        /** Ceiling on applied gain, so a very quiet room does not become a hiss. */
        private const val MAX_ASR_GAIN = 30f
    }
}
