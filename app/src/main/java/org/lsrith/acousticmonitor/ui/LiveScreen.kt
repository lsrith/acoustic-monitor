// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.lsrith.acousticmonitor.classify.ScoredClass
import org.lsrith.acousticmonitor.data.AlertEventEntity
import org.lsrith.acousticmonitor.detect.Severity
import org.lsrith.acousticmonitor.detect.WindowVerdict
import org.lsrith.acousticmonitor.integrity.MicIntegrity
import org.lsrith.acousticmonitor.integrity.MicState
import org.lsrith.acousticmonitor.monitor.MonitorEngine
import org.lsrith.acousticmonitor.speech.TranscriptSegment

/**
 * The demonstration screen — everything that moves while something is
 * happening, on one page.
 *
 * Deliberately not an inspection surface: current decision first, exceptions
 * only when they are exceptional, detail last. The per-signal internals live
 * in Diagnostics.
 */
@Composable
fun LiveScreen(
    running: Boolean,
    calibration: MonitorEngine.Calibration,
    verdict: WindowVerdict?,
    topClasses: List<ScoredClass>,
    transcript: List<TranscriptSegment>,
    latestAlert: AlertEventEntity?,
    integrity: MicIntegrity,
    speaking: Boolean,
    speechError: String?,
    inputGain: Float,
    asrModelLabel: String,
    reasoning: org.lsrith.acousticmonitor.llm.ReasonedWindow?,
    reasoningStrideSeconds: Double,
    reasoningError: String?,
    history: List<org.lsrith.acousticmonitor.monitor.SignalHistory.Sample>,
    onOpenModels: () -> Unit,
    micGranted: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenAlert: (Long) -> Unit,
) {
    if (!micGranted) {
        ScreenColumn {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Microphone permission required", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Audio is analysed on this device only. Nothing is uploaded.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = onRequestPermissions) { Text("Grant") }
                }
            }
        }
        return
    }

    ScreenColumn {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onStart, enabled = !running) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Start")
            }
            OutlinedButton(onClick = onStop, enabled = running) {
                Icon(Icons.Default.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Stop")
            }
        }

        // Nothing below is trustworthy until every baseline is learned, so
        // this is the only thing worth looking at while it is up.
        if (!calibration.ready) {
            CalibrationBanner(calibration)
        }

        // Exception report, not a status readout: present only while the
        // microphone's evidence cannot be trusted.
        if (integrity.state == MicState.OCCLUDED || integrity.state == MicState.MUTED) {
            IntegrityWarningCard(integrity)
        }

        Text(
            "Incidents mark configured signal thresholds or changes from the learned room baseline. " +
                "A deviation does not establish its cause or whether conditions are safe.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DecisionCard(verdict)

        // The one chart that belongs on the main screen. Everything this
        // system does rests on "louder than this room normally is", and that
        // claim is unreadable as two numbers — it needs the two lines.
        SignalChart(
            title = "Level vs the room's baseline",
            explanation = "Solid is what the microphone hears now; dashed is what this room " +
                "normally sounds like. The gap between them is the spike — not the height of " +
                "either line.",
            traces = listOf(
                rememberTrace("Now", TraceColors.measured, history) { it.currentDbfs },
                rememberTrace("Baseline", TraceColors.reference, history, dashed = true) { it.baselineDbfs },
            ),
            unit = " dBFS",
        )

        ReasoningCard(reasoning, reasoningStrideSeconds, reasoningError)

        latestAlert?.let { LatestAlertCard(it, onOpenAlert) }

        // Continuous and immediate, so it sits above the transcript, which is
        // intermittent and lags behind.
        HearingNowCard(topClasses)

        TranscriptCard(transcript, speaking, speechError, inputGain, asrModelLabel, onOpenModels)
    }
}

@Composable
private fun CalibrationBanner(calibration: MonitorEngine.Calibration) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (calibration.neverCalibrated) {
                // Nothing is happening and nothing will — distinct from a wait
                // in progress, and it needs a different instruction.
                Text("Room not calibrated", style = MaterialTheme.typography.titleSmall)
                Text(
                    "The loudness and mic-integrity paths stay inactive until the room " +
                        "has been learned. Start it from Setup, when conditions are " +
                        "representative — whatever happens during the five-minute window " +
                        "becomes the baseline everything is later judged against.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                Text(
                    "Learning the room — keep conditions normal",
                    style = MaterialTheme.typography.bodyMedium,
                )
                LinearProgressIndicator(
                    progress = { calibration.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                // Elapsed and confidence, not a countdown: learning never
                // finishes, it only becomes better founded.
                Text(
                    String.format(
                        Locale.US, "%d:%02d observed · %d%% confidence",
                        calibration.observedSeconds / 60,
                        calibration.observedSeconds % 60,
                        (calibration.progress * 100).toInt(),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun IntegrityWarningCard(integrity: MicIntegrity) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (integrity.state == MicState.OCCLUDED) {
                    "Microphone may be covered"
                } else {
                    "Microphone is delivering no audio"
                },
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                String.format(Locale.US, "For %.0f s", integrity.secondsInState),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * The language model's read on the last window of evidence.
 *
 * Shown separately from the decision because it is not part of it — it runs
 * beside detection, at whatever pace the device sustains, and refines rather
 * than gates. A stale or missing verdict changes nothing about what alerts.
 */
@Composable
private fun ReasoningCard(
    reasoning: org.lsrith.acousticmonitor.llm.ReasonedWindow?,
    strideSeconds: Double,
    error: String?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Reasoning", style = MaterialTheme.typography.titleSmall)
                Text(
                    String.format(Locale.US, "every %.0f s", strideSeconds),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            when {
                error != null -> Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                reasoning == null -> Text(
                    "No window judged yet. Detection is unaffected either way.",
                    style = MaterialTheme.typography.bodySmall,
                )
                else -> {
                    Text(
                        if (reasoning.concerning) "worth a look" else "ordinary",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        color = if (reasoning.concerning) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(reasoning.reason, style = MaterialTheme.typography.bodySmall)
                    Text(
                        String.format(
                            Locale.US, "%.0f s window · %.1f s to judge",
                            reasoning.windowSeconds, reasoning.generateMs / 1000.0,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
            Text(
                // Named from the verdict, not from the current selection. The
                // hardcoded name that used to sit here said Qwen3 0.6B whatever
                // was actually running.
                reasoning?.let { "${it.modelName} over a rolling window of detector events." }
                    ?: "Reasons over a rolling window of detector events.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Refines; never gates.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The most prominent element on the page: what the system currently believes. */
@Composable
private fun DecisionCard(verdict: WindowVerdict?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            // Label and value on one line. Severity as a heading wasted a third
            // of the card and read as the card's title rather than its answer.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Decision · now", style = MaterialTheme.typography.titleSmall)
                Text(
                    verdict?.severity?.name?.lowercase() ?: "no verdict yet",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = when (verdict?.severity) {
                        Severity.HIGH -> MaterialTheme.colorScheme.error
                        Severity.MEDIUM -> MaterialTheme.colorScheme.primary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            if (verdict != null) {
                Text(
                    String.format(
                        Locale.US, "%.3f vs %.3f medium",
                        verdict.fusedScore, verdict.effectiveMediumThreshold,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    PathScore("cue", verdict.cuePathScore)
                    PathScore("loud", verdict.ambientPathScore)
                    PathScore("lang", verdict.threatPathScore)
                }
            }
            Text(
                "YAMNet cues · room loudness · transcript language",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun PathScore(label: String, score: Float) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            String.format(Locale.US, "%.2f", score),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun LatestAlertCard(alert: AlertEventEntity, onOpenAlert: (Long) -> Unit) {
    val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    val ageMs = System.currentTimeMillis() - alert.startedAtEpochMs
    val age = when {
        ageMs < 60_000L -> "just now"
        ageMs < 3_600_000L -> "${ageMs / 60_000L} min ago"
        else -> "${ageMs / 3_600_000L} h ago"
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpenAlert(alert.id) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last recorded event", style = MaterialTheme.typography.titleSmall)
            StatRow("Time", "${timeFormat.format(Date(alert.startedAtEpochMs))} · $age")
            StatRow("Severity", alert.severity)
            StatRow("Cue", alert.peakCueLabel)
            StatRow("Peak score", String.format(Locale.US, "%.2f", alert.peakFusedScore))
            Text(
                "Tap to review",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptCard(
    transcript: List<TranscriptSegment>,
    speaking: Boolean,
    speechError: String?,
    inputGain: Float,
    modelLabel: String,
    onOpenModels: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Transcript", style = MaterialTheme.typography.titleSmall)
                // Without this, a voice detector that never fires is
                // indistinguishable from a recogniser producing nothing.
                Text(
                    if (speaking) "speech detected" else "listening",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (speaking) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            speechError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                // A feature whose model is absent must offer the way to get it,
                // not just report that it is missing.
                if (it.contains("download", ignoreCase = true) || it.contains("missing", ignoreCase = true)) {
                    TextButton(onClick = onOpenModels) { Text("Download a speech model") }
                }
            }
            if (inputGain > 1.5f) {
                Text(
                    String.format(Locale.US, "input gain ×%.0f — speech is arriving quietly", inputGain),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            val recent = transcript.take(5)
            if (recent.isEmpty()) {
                Text("Nothing transcribed yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    recent.forEach { segment -> TranscriptRow(segment, timeFormat) }
                }
            }
            Text(
                "Silero VAD → both recognisers on every segment",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TranscriptRow(segment: TranscriptSegment, timeFormat: SimpleDateFormat) {
    Column {
        Text(
            timeFormat.format(Date(segment.startEpochMs)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        // Both readings, attributed. Neither recogniser is authoritative —
        // Whisper misidentifies Singapore Chinese and Tamil, Omnilingual has no
        // language conditioning at all, and nothing available can choose
        // between them. Showing one and hiding the other would be presenting a
        // decision that was never made.
        ReadingRow(segment.modelLabel, segment.text, segment.threat)
        if (segment.altText.isNotEmpty()) {
            ReadingRow(segment.altModelLabel, segment.altText, segment.altThreat)
        }
    }
}

@Composable
private fun ReadingRow(
    model: String,
    text: String,
    threat: org.lsrith.acousticmonitor.detect.ThreatAssessment?,
) {
    if (text.isEmpty()) return
    Row(verticalAlignment = Alignment.Top) {
        Text(
            model.take(12).padEnd(12),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Column {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (threat != null && threat.score > 0f) {
                Text(
                    String.format(
                        Locale.US, "%s %.2f",
                        threat.topCategory?.displayName ?: "threat", threat.score,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun HearingNowCard(topClasses: List<ScoredClass>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Hearing now", style = MaterialTheme.typography.titleSmall)
            val top = topClasses.take(5)
            if (top.isEmpty()) {
                Text("—", style = MaterialTheme.typography.bodySmall)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    top.forEach { ClassBar(it) }
                }
            }
            Text(
                "YAMNet · 521 AudioSet classes",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ClassBar(item: ScoredClass) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(item.label, style = MaterialTheme.typography.bodyMedium)
            Text(
                String.format(Locale.US, "%.3f", item.score),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
        Spacer(Modifier.height(2.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                Modifier
                    .fillMaxWidth(item.score.coerceIn(0f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
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
