// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import org.lsrith.acousticmonitor.classify.FrameResult
import org.lsrith.acousticmonitor.monitor.RoomReading

enum class Severity { NONE, MEDIUM, HIGH }

/** One group's contribution to a single window. */
data class GroupScore(val group: CueGroup, val label: String, val raw: Float, val weighted: Float)

/** The decision layer's read on one window. Cheap, stateless, displayable live. */
data class WindowVerdict(
    val endSampleIndex: Long,
    val severity: Severity,
    val fusedScore: Float,
    val groups: List<GroupScore>,
    val topGroup: GroupScore?,
    val speechScore: Float,
    val mediaScore: Float,
    val laughterScore: Float,
    /** Medium threshold after demotion, so the UI can show the moving bar. */
    val effectiveMediumThreshold: Float,
    val effectiveHighThreshold: Float,
    /** Loudness relative to the learned room. */
    val ambient: RoomReading,
    /** Sustained sub-threshold concern that never recovered. */
    val accumulation: AccumulationState,
    /** Contribution of the cue path alone, before fusion. */
    val cuePathScore: Float,
    /** Contribution of the loudness-deviation path alone. */
    val ambientPathScore: Float,
    /** Contribution of the threat-language path alone, already age-decayed. */
    val threatPathScore: Float,
    /** Vocal strain, 0..1. Amplifies the cue path; never fires on its own. */
    val vocalStress: Float,
)

/** A completed detection, ready to persist. Emitted once, when the event closes. */
data class DetectedEvent(
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val severity: Severity,
    val peakFusedScore: Float,
    val peakCueGroup: CueGroup,
    val peakCueLabel: String,
    val cueSummary: String,
    val mediaScoreAtPeak: Float,
    val speechScoreAtPeak: Float,
    val windowCount: Int,
    /** Sample index at the peak, so the snippet can be centred on it. */
    val peakEndSampleIndex: Long,
)

/**
 * Turns a stream of per-window class scores into discrete events.
 *
 * The rules are deliberately legible rather than clever. Everything here is
 * inspectable and adjustable, because the honest state of affairs is that no
 * threshold in [DecisionConfig] has been calibrated against a real home yet.
 *
 * Not thread-safe: drive it from a single consumer of the audio pipeline.
 */
class DecisionEngine(
    @Volatile var config: DecisionConfig = DecisionConfig(),
) {

    private val recentFiring = ArrayDeque<Boolean>()
    private var open: OpenEvent? = null
    private var quietRun = 0

    private val accumulation = AccumulationTracker()

    /** Configured labels the model has never emitted — almost certainly typos. */
    private val seenLabels = HashSet<String>()
    var unmatchedLabels: List<String> = emptyList()
        private set

    private class OpenEvent(
        val startedAtEpochMs: Long,
        var endedAtEpochMs: Long,
        var severity: Severity,
        var peakFused: Float,
        var peakGroup: CueGroup,
        var peakLabel: String,
        var peakMedia: Float,
        var peakSpeech: Float,
        var peakEndSampleIndex: Long,
        var windowCount: Int,
        val groupPeaks: MutableMap<CueGroup, Float>,
        var fromAccumulation: Boolean,
        // Every path's high-water mark, so the record says what actually
        // contributed rather than implying the acoustic cue was the whole story.
        var peakCuePath: Float,
        var peakAmbientPath: Float,
        var peakThreatPath: Float,
        var peakVocalStress: Float,
        var peakAmbientDeviationDb: Float,
        var peakLaughter: Float,
    )

    /**
     * Feeds one window through the rules.
     *
     * Returns the window's verdict, plus a [DetectedEvent] on the window where
     * an event closes. Events are emitted on close rather than on open so the
     * record carries the full peak and duration.
     */
    /**
     * @param threatScore 0..1 from the language path, already decayed by age.
     *   Zero when no speech has been transcribed or no models are loaded — the
     *   acoustic paths must keep working without it.
     */
    fun accept(
        frame: FrameResult,
        room: RoomReading,
        threatScore: Float,
        vocalStress: Float,
        nowEpochMs: Long,
    ): Pair<WindowVerdict, DetectedEvent?> {
        trackLabelCoverage(frame)

        val cfg = config
        val groups = CueGroup.entries.mapNotNull { group ->
            val best = group.labels
                .mapNotNull { label -> frame.scores[label]?.let { label to it } }
                .maxByOrNull { it.second }
                ?: return@mapNotNull null
            GroupScore(group, best.first, best.second, best.second * cfg.weightOf(group))
        }

        val speech = maxScore(frame, CueGroup.SPEECH_LABELS)
        val media = maxScore(frame, CueGroup.MEDIA_LABELS)
        val laughter = maxScore(frame, CueGroup.LAUGHTER_LABELS)
        val reading = room

        // Context raises acoustic thresholds, with a cap to keep large deviations detectable.
        val demotion = (1f + cfg.mediaPenalty * media + cfg.laughterPenalty * laughter)
            .coerceAtMost(cfg.maxMediaFactor)

        // Capping the factor alone did not keep that promise. The loudness
        // path cannot exceed cfg.ambientWeight by construction, and the
        // demoted medium bar climbed past it — so a loudness-only event was
        // unreachable while a television played. That is demotion silencing,
        // and it is precisely the masking case the rule exists to resist. The
        // bar is therefore also held just below the weakest path's own
        // ceiling, so a maximal deviation still crosses at full demotion.
        // Never below the configured bar. Applied unconditionally the cap
        // also *lowered* an undemoted threshold — with a low ambientWeight it
        // pinned the bar to a constant, turning media demotion into a no-op
        // and quietly using a number the Diagnostics screen was not showing.
        // Demotion may only ever raise it.
        val acousticCeiling = cfg.ambientWeight * cfg.demotionCeilingFraction
        val effMedium = (cfg.mediumThreshold * demotion)
            .coerceAtMost(maxOf(cfg.mediumThreshold, acousticCeiling))
        // High is left uncapped, and that is a demotion rather than a
        // silencing: a cry whose group weight is 0.90 crosses HIGH in a quiet
        // room and only MEDIUM with a television at full volume. It still
        // records an incident. Note the earlier claim here that
        // "cues run to 1.0" was wrong — a cue is raw score times its group
        // weight, and only GUNSHOT carries weight 1.0.
        val effHigh = cfg.highThreshold * demotion

        // Multiple sufficient paths rather than an AND gate: any one strong
        // path escalates on its own, because requiring corroboration would miss
        // the cases where only one signal is available.
        val top = groups.maxByOrNull { it.weighted }

        // Strain multiplies an existing cue rather than contributing its own
        // score, so it cannot manufacture an alert out of a raised voice alone.
        val rawCue = top?.weighted ?: 0f
        val cuePath = (rawCue * (1f + cfg.stressBoost * vocalStress)).coerceAtMost(1f)
        // Weighted by how much the learned room can be trusted, so the path
        // fades in as confidence grows rather than switching on at a fixed time.
        val ambientPath = reading.weightedDeviation * cfg.ambientWeight
        val threatPath = threatScore * cfg.threatWeight
        val acoustic = maxOf(cuePath, ambientPath)

        /** Highest path score, for display. Severity is decided per path below. */
        val fused = maxOf(acoustic, threatPath)

        // Feed accumulation with how close this window came to firing, so an
        // evening of near-misses that never recovers can escalate on its own.
        val strength = if (effMedium <= 0f) 0f else (fused / effMedium).coerceIn(0f, 1f)
        val accumulated = accumulation.accept(strength)

        // Each path is judged against its own bar. Acoustic paths face the
        // demoted thresholds; language faces the undemoted ones, because a
        // television playing does not make a threat spoken over it less real
        // and demoting language would reward masking.
        val severity = when {
            acoustic >= effHigh || threatPath >= cfg.highThreshold -> Severity.HIGH
            // Sustained sub-threshold concern with no recovery is treated as
            // high on its own — it is the pattern that acute thresholds miss.
            accumulated.escalated -> Severity.HIGH
            acoustic >= effMedium || threatPath >= cfg.mediumThreshold -> Severity.MEDIUM
            else -> Severity.NONE
        }

        val verdict = WindowVerdict(
            endSampleIndex = frame.endSampleIndex,
            severity = severity,
            fusedScore = fused,
            groups = groups.sortedByDescending { it.weighted },
            topGroup = top,
            speechScore = speech,
            mediaScore = media,
            laughterScore = laughter,
            effectiveMediumThreshold = effMedium,
            effectiveHighThreshold = effHigh,
            ambient = reading,
            accumulation = accumulated,
            cuePathScore = cuePath,
            ambientPathScore = ambientPath,
            threatPathScore = threatPath,
            vocalStress = vocalStress,
        )

        val closed = updateEventState(verdict, cfg, nowEpochMs)
        return verdict to closed
    }

    /** Ends any open event immediately, e.g. when monitoring stops. */
    fun flush(): DetectedEvent? {
        val event = open ?: return null
        open = null
        quietRun = 0
        recentFiring.clear()
        return event.toDetected()
    }

    fun reset() = resetEventState()

    /**
     * Clears in-flight event tracking but keeps the learned baseline.
     *
     * Restarting monitoring should not discard what the room sounds like.
     */
    fun resetEventState() {
        open = null
        quietRun = 0
        recentFiring.clear()
        accumulation.reset()
    }


    private fun updateEventState(
        verdict: WindowVerdict,
        cfg: DecisionConfig,
        nowEpochMs: Long,
    ): DetectedEvent? {
        val firing = verdict.severity != Severity.NONE
        recentFiring.addLast(firing)
        while (recentFiring.size > cfg.persistenceM) recentFiring.removeFirst()

        val persistent = recentFiring.count { it } >= cfg.persistenceK

        val current = open
        if (current == null) {
            if (persistent) {
                // Back-date the start across the window that established
                // persistence, so the event covers the onset rather than the
                // moment the rule happened to trip.
                val backdateMs = (cfg.persistenceM * WINDOW_HOP_MS).toLong()
                open = OpenEvent(
                    startedAtEpochMs = nowEpochMs - backdateMs,
                    endedAtEpochMs = nowEpochMs,
                    severity = verdict.severity,
                    peakFused = verdict.fusedScore,
                    peakGroup = verdict.topGroup?.group ?: CueGroup.IMPACT,
                    peakLabel = verdict.topGroup?.label.orEmpty(),
                    peakMedia = verdict.mediaScore,
                    peakSpeech = verdict.speechScore,
                    peakEndSampleIndex = verdict.endSampleIndex,
                    windowCount = 1,
                    groupPeaks = mutableMapOf(),
                    fromAccumulation = verdict.accumulation.escalated,
                    peakCuePath = 0f,
                    peakAmbientPath = 0f,
                    peakThreatPath = 0f,
                    peakVocalStress = 0f,
                    peakAmbientDeviationDb = 0f,
                    peakLaughter = 0f,
                )
                absorb(open!!, verdict)
                quietRun = 0
            }
            return null
        }

        current.windowCount++
        current.endedAtEpochMs = nowEpochMs
        absorb(current, verdict)

        if (firing) {
            quietRun = 0
        } else {
            quietRun++
            if (quietRun >= cfg.quietWindowsToClose) {
                open = null
                quietRun = 0
                return current.toDetected()
            }
        }
        return null
    }

    private fun absorb(event: OpenEvent, verdict: WindowVerdict) {
        if (verdict.accumulation.escalated) event.fromAccumulation = true
        if (verdict.cuePathScore > event.peakCuePath) event.peakCuePath = verdict.cuePathScore
        if (verdict.ambientPathScore > event.peakAmbientPath) event.peakAmbientPath = verdict.ambientPathScore
        if (verdict.threatPathScore > event.peakThreatPath) event.peakThreatPath = verdict.threatPathScore
        if (verdict.vocalStress > event.peakVocalStress) event.peakVocalStress = verdict.vocalStress
        if (verdict.laughterScore > event.peakLaughter) event.peakLaughter = verdict.laughterScore
        if (verdict.ambient.deviationDb > event.peakAmbientDeviationDb) {
            event.peakAmbientDeviationDb = verdict.ambient.deviationDb
        }
        for (g in verdict.groups) {
            val prev = event.groupPeaks[g.group] ?: 0f
            if (g.raw > prev) event.groupPeaks[g.group] = g.raw
        }
        if (verdict.severity.ordinal > event.severity.ordinal) event.severity = verdict.severity
        if (verdict.fusedScore > event.peakFused) {
            event.peakFused = verdict.fusedScore
            verdict.topGroup?.let {
                event.peakGroup = it.group
                event.peakLabel = it.label
            }
            event.peakMedia = verdict.mediaScore
            event.peakSpeech = verdict.speechScore
            event.peakEndSampleIndex = verdict.endSampleIndex
        }
    }

    private fun OpenEvent.toDetected() = DetectedEvent(
        startedAtEpochMs = startedAtEpochMs,
        endedAtEpochMs = endedAtEpochMs,
        severity = severity,
        peakFusedScore = peakFused,
        peakCueGroup = peakGroup,
        peakCueLabel = peakLabel,
        // Every path that contributed, not only the acoustic cues. An event
        // driven by language or by sustained loudness would otherwise be
        // described purely by whichever YAMNet class happened to peak, which
        // reads as though the cue was the whole reason.
        cueSummary = buildString {
            if (fromAccumulation) append("sustained pattern · ")

            val cues = groupPeaks.entries
                .sortedByDescending { it.value }
                .take(3)
                .joinToString(", ") { "${it.key.displayName} ${"%.2f".format(it.value)}" }
            append(if (cues.isEmpty()) "no single cue above threshold" else cues)

            val paths = buildList {
                if (peakCuePath > 0.01f) add("cue ${"%.2f".format(peakCuePath)}")
                if (peakAmbientPath > 0.01f) {
                    add("loudness ${"%.2f".format(peakAmbientPath)} (${"%+.0f".format(peakAmbientDeviationDb)} dB)")
                }
                if (peakThreatPath > 0.01f) add("language ${"%.2f".format(peakThreatPath)}")
                if (peakVocalStress > 0.01f) add("strain ${"%.2f".format(peakVocalStress)}")
                if (peakLaughter > 0.05f) add("laughter ${"%.2f".format(peakLaughter)}")
            }
            if (paths.isNotEmpty()) append(" · paths: ").append(paths.joinToString(", "))
        },
        mediaScoreAtPeak = peakMedia,
        speechScoreAtPeak = peakSpeech,
        windowCount = windowCount,
        peakEndSampleIndex = peakEndSampleIndex,
    )

    private fun maxScore(frame: FrameResult, labels: List<String>): Float {
        var best = 0f
        for (label in labels) {
            val score = frame.scores[label] ?: continue
            if (score > best) best = score
        }
        return best
    }

    /**
     * A label misspelled against YAMNet's class map scores zero forever and
     * silently disables its cue. Comparing the configured labels against what
     * the model actually emits turns that into something visible.
     */
    private fun trackLabelCoverage(frame: FrameResult) {
        if (frame.scores.isEmpty()) return
        val before = seenLabels.size
        seenLabels.addAll(frame.scores.keys)
        if (seenLabels.size != before || unmatchedLabels.isEmpty()) {
            unmatchedLabels = CueGroup.ALL_REFERENCED.filterNot { it in seenLabels }
        }
    }

    companion object {
        /** Matches AudioSpec.HOP_SAMPLES at 16 kHz. */
        private const val WINDOW_HOP_MS = 480
    }
}
