// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsrith.acousticmonitor.BuildConfig
import org.lsrith.acousticmonitor.detect.DecisionConfig
import org.lsrith.acousticmonitor.dsp.ProsodyFrame
import org.lsrith.acousticmonitor.dsp.VocalStress
import org.lsrith.acousticmonitor.integrity.MicIntegrity
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import org.lsrith.acousticmonitor.monitor.MonitorEngine
import org.lsrith.acousticmonitor.speech.TranscriptSegment
import java.util.Locale

@Composable
fun DiagnosticsScreen(
    latency: MonitorEngine.LatencySnapshot,
    onResetMetrics: () -> Unit,
    asrStats: LatencyTracker.Stats,
    altAsrStats: LatencyTracker.Stats,
    transcript: List<TranscriptSegment>,
    config: DecisionConfig,
    unmatchedLabels: List<String>,
    voice: ProsodyFrame,
    integrity: MicIntegrity,
    history: List<org.lsrith.acousticmonitor.monitor.SignalHistory.Sample>,
    nearMiss: Map<org.lsrith.acousticmonitor.detect.CueGroup, org.lsrith.acousticmonitor.monitor.NearMissTracker.Peak>,
    reasoningStats: org.lsrith.acousticmonitor.metrics.LatencyTracker.Stats,
    reasoningStride: Double,
    reasoningBackend: String,
    reasoningModel: String,
    reasoningLatest: org.lsrith.acousticmonitor.llm.ReasonedWindow?,
    reasoningBusy: Boolean,
    reasoningPhase: String?,
    reasoningGenerations: Int,
    reasoningNativeKb: Long,
    reasoningError: String?,
    onAnalyseNow: () -> Unit,
) {
    ScreenColumn {
        // Whether the pipeline is keeping pace with the microphone, which is
        // a different question from how fast any single stage is: audio
        // arrives every hop whether or not the last window has been judged.
        KeepingUpCard(latency.inference)

        Text("Where cues die", style = MaterialTheme.typography.titleMedium)
        NearMissCard(nearMiss)

        Text("Signals over time", style = MaterialTheme.typography.titleMedium)

        // The decision layer's own arithmetic, drawn. Each of these was
        // previously a single number on screen, which is unreadable: a score
        // means nothing without the threshold it is being tested against, and
        // accumulation is a shape over time by definition.
        SignalChart(
            title = "Fused score vs the moving threshold",
            explanation = "The score is tested against a threshold that moves — media and " +
                "laughter push it up, so the same score alerts in a quiet room and does not " +
                "during a film. Alerting is the solid line crossing a dashed one.",
            traces = listOf(
                rememberTrace("Fused score", TraceColors.measured, history) { it.fusedScore },
                rememberTrace("Medium", TraceColors.warn, history, dashed = true) { it.mediumThreshold },
                rememberTrace("High", TraceColors.alarm, history, dashed = true) { it.highThreshold },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )

        SignalChart(
            title = "The three paths, before fusion",
            explanation = "Any one of these can escalate on its own — they are combined with " +
                "OR, not AND. Violence is loud and cued; coercion is quiet and lexical. " +
                "Requiring both would miss each.",
            traces = listOf(
                rememberTrace("Cue", TraceColors.alarm, history) { it.cuePath },
                rememberTrace("Loudness", TraceColors.measured, history) { it.loudnessPath },
                rememberTrace("Language", TraceColors.speech, history) { it.threatPath },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )

        SignalChart(
            title = "Accumulation",
            explanation = "Sustained concern that never individually crossed the bar. This " +
                "rises while it persists and decays when it stops, so a long low-grade " +
                "episode escalates even though no single window did.",
            traces = listOf(
                rememberTrace("Accumulated", TraceColors.warn, history) { it.accumulation },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )

        SignalChart(
            title = "Electronic vs human sound",
            explanation = "Media and laughter adjust acoustic thresholds within configured limits. " +
                "Compare these signals over time to see which context accompanied an incident; " +
                "the chart does not establish its cause.",
            traces = listOf(
                rememberTrace("Media", TraceColors.media, history) { it.mediaScore },
                rememberTrace("Speech", TraceColors.speech, history) { it.speechScore },
                rememberTrace("Laughter", TraceColors.calm, history) { it.laughterScore },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )

        SignalChart(
            title = "Treble share vs the room's baseline",
            explanation = "High frequencies are the first thing a covered or pocketed " +
                "microphone loses. A solid line sitting well under the dashed one is the " +
                "occlusion signal.",
            traces = listOf(
                rememberTrace("Now", TraceColors.measured, history) { it.currentTreble },
                rememberTrace("Baseline", TraceColors.reference, history, dashed = true) { it.baselineTreble },
            ),
        )

        Text("Reasoning", style = MaterialTheme.typography.titleMedium)
        ReasoningCard(
            stats = reasoningStats,
            strideSeconds = reasoningStride,
            backend = reasoningBackend,
            modelName = reasoningModel,
            latest = reasoningLatest,
            busy = reasoningBusy,
            phase = reasoningPhase,
            generations = reasoningGenerations,
            nativeKb = reasoningNativeKb,
            error = reasoningError,
            onAnalyseNow = onAnalyseNow,
        )

        Text("Latency", style = MaterialTheme.typography.titleMedium)
        LatencyCard(
            title = "Inference",
            subtitle = "Time inside YAMNet.",
            stats = latency.inference,
        )
        LatencyCard(
            title = "Capture → verdict",
            subtitle = "Mic to scored window. Target for the acute path is ~1 s.",
            stats = latency.endToEnd,
        )

        // Per speech segment rather than per window, and both were previously
        // unmeasured despite now running on every segment.
        LatencyCard(
            title = "Voice identity",
            subtitle = "Speaker embedding and comparison, per speech segment.",
            stats = latency.voiceId,
        )
        LatencyCard(
            title = "Diarization",
            subtitle = "Speaker count and turn-taking, per speech segment.",
            stats = latency.diarization,
        )
        OutlinedButton(onClick = onResetMetrics) { Text("Reset metrics") }

        Text("ASR", style = MaterialTheme.typography.titleMedium)
        AsrCard(asrStats = asrStats, transcript = transcript)
        LatencyCard(
            title = "Second recogniser",
            subtitle = "Omnilingual alone. Compare against the segment total above: " +
                "if the total is close to the sum rather than the larger of the two, " +
                "they are queueing for cores rather than overlapping.",
            stats = altAsrStats,
        )

        Text("Mic integrity numbers", style = MaterialTheme.typography.titleMedium)
        IntegrityCard(integrity)

        Text("Voice", style = MaterialTheme.typography.titleMedium)
        VoiceCard(voice)

        Text("Decision rules", style = MaterialTheme.typography.titleMedium)
        DecisionRulesCard(config)

        // A cue label that does not exist in YAMNet's class map scores zero
        // forever and silently disables its cue. Surfacing it is the only way
        // that failure is visible.
        if (unmatchedLabels.isNotEmpty()) {
            Text("Cue label check", style = MaterialTheme.typography.titleMedium)
            UnmatchedLabelsCard(unmatchedLabels)
        }

        Text("Build", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                StatRow("Version", BuildConfig.VERSION_NAME)
                StatRow("Commit", BuildConfig.GIT_SHA)
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Text(
                "Scores are probabilities from a model trained on YouTube audio, not on homes, " +
                    "and none of the thresholds above have been calibrated against a real room yet. " +
                    "Expect look-alikes — measuring that baseline is the point.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

/**
 * The reasoning layer, measured on real work.
 *
 * There was a benchmark here that ran a hardcoded sentence through the model
 * and reported how long it took. It measured something nobody said, over a
 * window that did not exist. Every figure here now comes from analysing
 * whatever the detectors actually found — on the interval, or on demand.
 */
@Composable
private fun ReasoningCard(
    stats: org.lsrith.acousticmonitor.metrics.LatencyTracker.Stats,
    strideSeconds: Double,
    backend: String,
    modelName: String,
    latest: org.lsrith.acousticmonitor.llm.ReasonedWindow?,
    busy: Boolean,
    phase: String?,
    generations: Int,
    nativeKb: Long,
    error: String?,
    onAnalyseNow: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatRow("Selected", modelName)
            // The selection and the model that produced the last verdict are
            // different facts, and showing only one of them made two screens
            // disagree about which model was running.
            latest?.takeIf { it.modelName != modelName }?.let {
                StatRow("Last verdict from", it.modelName)
            }
            StatRow("Backend", backend)
            StatRow("Interval", String.format(Locale.US, "every %.0f s", strideSeconds))

            if (stats.count == 0) {
                Text(
                    "No analysis has run yet. Timing appears once the model has " +
                        "judged a real window.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                StatRow("Runs", stats.count.toString())
                StatRow("Last", fmtMs(stats.last))
                StatRow("p50", fmtMs(stats.p50))
                StatRow("p95", fmtMs(stats.p95))
                StatRow("Max", fmtMs(stats.max))
                // The process aborts inside the runtime after tens of
                // generations. These two say whether native memory climbs with
                // each one — a leak — or stays flat, in which case the runtime
                // simply falls over after N calls. A tombstone cannot tell
                // those apart and they need different fixes.
                StatRow("Since load", "$generations generations")
                StatRow("Native heap", String.format(Locale.US, "%,d kB", nativeKb))
            }

            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            // The button stays on screen while a scheduled judgement runs.
            // Replacing it with a progress bar meant that during a generation —
            // which can take the best part of a minute — there was nothing to
            // press, and pressing it a moment earlier appeared to do nothing.
            // Named phases, not a boolean. Loading takes about 21 s and
            // judging most of a minute; showing nothing until both were done
            // was indistinguishable from a button that did nothing.
            if (busy) {
                Text(
                    phase ?: "Working…",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            OutlinedButton(onClick = onAnalyseNow, enabled = !busy) {
                Text(if (busy) "Working…" else "Analyse now")
            }
            Text(
                String.format(
                    Locale.US,
                    "Judges the last %.0f s of detector events, and refuses an " +
                        "empty window rather than judging nothing. Expect a " +
                        "model load on the first run.",
                    strideSeconds * 1.5,
                ),
                style = MaterialTheme.typography.bodySmall,
            )

            latest?.let { w ->
                Text(
                    if (w.concerning) "worth a look" else "ordinary",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    color = if (w.concerning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(w.reason, style = MaterialTheme.typography.bodySmall)
                // The evidence it saw, so the verdict can be disagreed with
                // against something fixed.
                Text("Evidence", style = MaterialTheme.typography.bodySmall)
                Text(
                    w.evidence.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

/**
 * The best each cue group managed recently, and whether it was enough.
 *
 * A group missing from this list was never scored at all — the model did not
 * hear the event. A group present with a weighted score under the bar was
 * heard and then rejected. Those are different faults with opposite fixes,
 * and no other screen distinguishes them.
 */
/**
 * Whether inference finishes inside one hop.
 *
 * The microphone does not wait. If a window takes longer to judge than the
 * next one takes to arrive, the pipeline is behind, and no percentile on its
 * own says so — 600 ms is comfortable against a one-second target and far too
 * slow against a 480 ms hop.
 */
@Composable
private fun KeepingUpCard(inference: LatencyTracker.Stats) {
    val hopMs = org.lsrith.acousticmonitor.audio.AudioSpec.hopSeconds * 1000
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Keeping up", style = MaterialTheme.typography.titleSmall)
            if (inference.count == 0) {
                Text("Not measured yet.", style = MaterialTheme.typography.bodySmall)
                return@Column
            }
            StatRow("Hop", fmtMs(hopMs))
            StatRow("Inference p95", fmtMs(inference.p95))
            StatRow(
                "Headroom at p95",
                String.format(Locale.US, "%.0f%% of the hop", inference.p95 / hopMs * 100),
            )
            Text(
                if (inference.p95 <= hopMs) {
                    "Judging a window takes less than the time until the next one."
                } else {
                    "Windows arrive faster than they are judged; the pipeline is behind."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (inference.p95 <= hopMs) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun NearMissCard(
    peaks: Map<org.lsrith.acousticmonitor.detect.CueGroup, org.lsrith.acousticmonitor.monitor.NearMissTracker.Peak>,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Best two minutes per cue. A group absent here was never scored; " +
                    "one listed below the bar was scored and rejected.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (peaks.isEmpty()) {
                Text(
                    "No cue has scored above zero yet.",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                return@Column
            }

            peaks.values.sortedByDescending { it.raw }.forEach { peak ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        "${peak.group.displayName} · ${peak.label}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        String.format(
                            Locale.US,
                            "raw %.2f → %.2f / %.2f%s",
                            peak.raw, peak.weighted, peak.threshold,
                            if (peak.crossed) " ✓" else String.format(Locale.US, "  short %.2f", peak.shortfall),
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = if (peak.crossed) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun LatencyCard(title: String, subtitle: String, stats: LatencyTracker.Stats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
            if (stats.count == 0) {
                Text("No samples yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                StatRow("last", fmtMs(stats.last))
                StatRow("min", fmtMs(stats.min))
                StatRow("p50", fmtMs(stats.p50))
                StatRow("p90", fmtMs(stats.p90))
                StatRow("p95", fmtMs(stats.p95))
                StatRow("p99", fmtMs(stats.p99))
                StatRow("max", fmtMs(stats.max))
                // Spread, not just position. A stage that jitters between 20 ms
                // and 900 ms is a different problem from one that always takes
                // 400 ms, and the percentiles alone do not separate them.
                StatRow("mean ± sd", "${fmtMs(stats.mean)} ± ${fmtMs(stats.stdDev)}")
                StatRow("n", "${stats.count} of ${stats.totalSeen}")


                // over everything ever measured, so a stall that has scrolled
                // out of the retained window still counts against it.
                stats.withinBudget?.let { fraction ->
                    val ok = fraction >= 0.95
                    StatRow(
                        "within ${fmtMs(stats.budgetMs)}",
                        String.format(
                            Locale.US, "%.1f%%  (%d over)",
                            fraction * 100, stats.overBudget,
                        ),
                    )
                    if (!ok) {
                        Text(
                            "Missing its budget on more than one window in twenty.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

/**
 * ASR timing plus two derived figures across the current transcript: mean
 * real-time factor (segments with a zero RTF are excluded rather than
 * dragging the mean toward zero — see [TranscriptSegment.realTimeFactor])
 * and mean gain, which points at distance/placement rather than the model
 * when it runs high.
 */
@Composable
private fun AsrCard(asrStats: LatencyTracker.Stats, transcript: List<TranscriptSegment>) {
    val rtfs = transcript.map { it.realTimeFactor }.filter { it != 0.0 }
    val meanRtf = if (rtfs.isEmpty()) null else rtfs.sum() / rtfs.size
    val meanGain = if (transcript.isEmpty()) null else transcript.map { it.asrGain }.sum() / transcript.size

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (asrStats.count == 0) {
                Text("No transcriptions yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                StatRow("last", fmtMs(asrStats.last))
                StatRow("p50", fmtMs(asrStats.p50))
                StatRow("p95", fmtMs(asrStats.p95))
                StatRow("max", fmtMs(asrStats.max))
            }
            Text("Real-time factor", style = MaterialTheme.typography.titleSmall)
            if (meanRtf == null) {
                Text("No transcriptions yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    String.format(Locale.US, "%.2f", meanRtf),
                    style = MaterialTheme.typography.titleMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (meanRtf >= 1.0) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurface,
                )
            }
            Text("Gain", style = MaterialTheme.typography.titleSmall)
            if (meanGain == null) {
                Text("No transcriptions yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                StatRow("Mean gain", String.format(Locale.US, "×%.1f", meanGain))
                Text(
                    "A large gain means the speech arrived very quietly.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Level alone cannot separate "nobody is home" from "someone put a cushion on
 * the phone" — cloth is a low-pass filter, so the treble share is what tells
 * them apart. Both are shown against their learned baseline.
 */
@Composable
private fun IntegrityCard(integrity: MicIntegrity) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow(
                "Level vs baseline",
                String.format(Locale.US, "%.1f / %.1f dBFS", integrity.rmsDbfs, integrity.baselineDbfs),
            )
            StatRow(
                "Treble share vs baseline",
                String.format(Locale.US, "%.3f / %.3f", integrity.hfRatio, integrity.baselineHfRatio),
            )
        }
    }
}

/** Vocal measures, defined only over voiced speech. */
@Composable
private fun VoiceCard(voice: ProsodyFrame) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!voice.voiced) {
                Text("No pitched speech in this window.", style = MaterialTheme.typography.bodySmall)
            } else {
                StatRow("Pitch", String.format(Locale.US, "%.0f Hz", voice.f0Hz))
                StatRow("Jitter", String.format(Locale.US, "%.4f", voice.jitter))
                StatRow("Shimmer", String.format(Locale.US, "%.4f", voice.shimmer))
                StatRow("Harmonicity", String.format(Locale.US, "%.3f", voice.harmonicity))
                StatRow("Strain", String.format(Locale.US, "%.3f", VocalStress.of(voice)))
            }
            StatRow("Effort", String.format(Locale.US, "%.1f dBFS", voice.vocalEffortDb))
        }
    }
}

@Composable
private fun DecisionRulesCard(config: DecisionConfig) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatRow("Medium threshold", String.format(Locale.US, "%.2f", config.mediumThreshold))
            StatRow("High threshold", String.format(Locale.US, "%.2f", config.highThreshold))
            StatRow("Persistence", "${config.persistenceK} of last ${config.persistenceM} windows")
            StatRow(
                "Media penalty",
                String.format(Locale.US, "×%.2f max %.2f", config.mediaPenalty, config.maxMediaFactor),
            )
            StatRow("Close after", "${config.quietWindowsToClose} quiet windows")
            StatRow("Ambient weight", String.format(Locale.US, "%.2f", config.ambientWeight))
            StatRow("Threat weight", String.format(Locale.US, "%.2f", config.threatWeight))
            StatRow("Stress boost", String.format(Locale.US, "%.2f", config.stressBoost))
        }
    }
}

@Composable
private fun UnmatchedLabelsCard(unmatched: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "Cue labels not present in the model's class map",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                "These never score and their cue is effectively disabled. " +
                    "Populated once monitoring has run.",
                style = MaterialTheme.typography.bodySmall,
            )
            unmatched.forEach {
                Text("· $it", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodySmall)
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun fmtMs(ms: Double) = String.format(Locale.US, "%.1f ms", ms)
