// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui.walkthrough

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.lsrith.acousticmonitor.classify.FrameResult
import org.lsrith.acousticmonitor.detect.WindowVerdict
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import org.lsrith.acousticmonitor.monitor.SignalHistory
import org.lsrith.acousticmonitor.ui.SignalChart
import org.lsrith.acousticmonitor.ui.TraceColors
import org.lsrith.acousticmonitor.ui.rememberTrace

/**
 * The fast path: every ~1 s window classified into 521 AudioSet classes, and
 * the handful of those the decision layer actually acts on.
 */
@Composable
fun SoundEventsPage(
    frame: FrameResult?,
    verdict: WindowVerdict?,
    history: List<SignalHistory.Sample>,
    inference: LatencyTracker.Stats,
    endToEnd: LatencyTracker.Stats,
) {
    SignalPage(
        title = "Sound events",
        signalDescription = "sound event classification",
        whatItDoes = "Every window of microphone audio (about 1 s) is classified by YAMNet " +
            "into 521 AudioSet sound classes, on this phone. It was trained on YouTube " +
            "audio, not homes, so expect look-alikes until a real room recalibrates it.",
        tryIt = "Clap, knock on the table, slam a door, or drop keys, and watch the top " +
            "classes change within a second.",
        modelLine = "YAMNet · TensorFlow Lite · 521 AudioSet classes · on-device",
    ) {
        if (frame == null) {
            NothingYet("No window classified yet — press Start.")
        } else {
            HearingNowCard(frame)
            CueGroupsCard(verdict)
            SignalChart(
                title = "Cue path over time",
                explanation = "The strongest cue group's contribution once it feeds the fused " +
                    "score — before loudness and language are added on top.",
                traces = listOf(rememberTrace("Cue path", TraceColors.measured, history) { it.cuePath }),
                fixedMin = 0f,
                fixedMax = 1f,
            )
            LatencyCard(inference, endToEnd)
        }
    }
}

/** The classifier's raw output for the window just judged. */
@Composable
private fun HearingNowCard(frame: FrameResult) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Hearing now", style = MaterialTheme.typography.titleSmall)
            frame.top.take(8).forEach { SignalBar(it.label, it.score) }
            SignalStatRow("Inference", fmtMs(frame.inferenceMs))
            SignalStatRow("Capture → result", fmtMs(frame.captureToResultMs))
        }
    }
}

/**
 * The narrow slice of YAMNet's 521 classes the detection layer listens for,
 * grouped so near-duplicate labels ("Shout", "Yell") count as one cue. This is
 * what actually drives an alert — the full class list above is for display.
 */
@Composable
private fun CueGroupsCard(verdict: WindowVerdict?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cue groups", style = MaterialTheme.typography.titleSmall)
            Text(
                "These are the classes the detection layer actually listens for, not the " +
                    "full 521 above.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (verdict == null || verdict.groups.isEmpty()) {
                NothingYet("No cue group has scored above zero this window.")
            } else {
                verdict.groups.forEach { gs ->
                    val isTop = gs == verdict.topGroup
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(
                            "${gs.group.displayName} · ${gs.label}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (isTop) FontWeight.Medium else FontWeight.Normal,
                            color = if (isTop) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            String.format(Locale.US, "raw %.3f → %.3f", gs.raw, gs.weighted),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

/** Whether the model keeps pace with the microphone, in the same shape as Diagnostics. */
@Composable
private fun LatencyCard(inference: LatencyTracker.Stats, endToEnd: LatencyTracker.Stats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Model latency", style = MaterialTheme.typography.titleSmall)
            LatencyRow("Inference", inference)
            LatencyRow("Capture → result", endToEnd)
        }
    }
}

@Composable
private fun LatencyRow(label: String, stats: LatencyTracker.Stats) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        if (stats.count == 0) {
            Text("No samples yet.", style = MaterialTheme.typography.bodySmall)
        } else {
            SignalStatRow("count", stats.count.toString())
            SignalStatRow("p50", fmtMs(stats.p50))
            SignalStatRow("p95", fmtMs(stats.p95))
            SignalStatRow("max", fmtMs(stats.max))
        }
    }
}

private fun fmtMs(ms: Double) = String.format(Locale.US, "%.1f ms", ms)
