// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui.walkthrough

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.Locale
import org.lsrith.acousticmonitor.classify.FrameResult
import org.lsrith.acousticmonitor.detect.CueGroup
import org.lsrith.acousticmonitor.detect.DecisionConfig
import org.lsrith.acousticmonitor.detect.WindowVerdict
import org.lsrith.acousticmonitor.monitor.SignalHistory
import org.lsrith.acousticmonitor.ui.SignalChart
import org.lsrith.acousticmonitor.ui.TraceColors
import org.lsrith.acousticmonitor.ui.rememberTrace


@Composable
internal fun MediaPlaybackPage(
    frame: FrameResult?,
    verdict: WindowVerdict?,
    history: List<SignalHistory.Sample>,
    config: DecisionConfig,
) {
    SignalPage(
        title = "Media playback",
        signalDescription = "audio replay detection",
        modelLine = "YAMNet media classes (Television, Radio, Music, Singing, Soundtrack…) · " +
            "on-device",
        whatItDoes = "Recognises that the sound is television/radio/music-like and raises the " +
            "alert bar accordingly — it demotes, never silences, by up to " +
            "×${String.format(Locale.US, "%.1f", config.maxMediaFactor)}. This is " +
            "content-based, not channel-based: a realistic argument played from a speaker " +
            "with no music bed under it will not be recognised as media.",
        tryIt = "Play a film fight scene, or music, from a laptop next to the phone. Watch " +
            "the media score rise and the threshold lines move up with it.",
    ) {
        MediaClassesCard(frame, verdict)
        ThresholdEffectCard(verdict, config)

        SignalChart(
            title = "Media vs thresholds over time",
            explanation = "The media score and the fused score against the medium and high " +
                "bars it is tested against — both bars move up as media (or laughter) is " +
                "detected.",
            traces = listOf(
                rememberTrace("Media score", TraceColors.media, history) { it.mediaScore },
                rememberTrace("Fused score", TraceColors.measured, history) { it.fusedScore },
                rememberTrace("Medium bar", TraceColors.warn, history, dashed = true) { it.mediumThreshold },
                rememberTrace("High bar", TraceColors.alarm, history, dashed = true) { it.highThreshold },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )
    }
}

@Composable
private fun MediaClassesCard(frame: FrameResult?, verdict: WindowVerdict?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Media classes now", style = MaterialTheme.typography.titleSmall)
            if (frame == null) {
                NothingYet("No frame classified yet.")
            } else {
                CueGroup.MEDIA_LABELS.forEach { label ->
                    SignalBar(label, frame.scores[label] ?: 0f)
                }
            }
            SignalStatRow(
                "Media score (max of these)",
                String.format(Locale.US, "%.3f", verdict?.mediaScore ?: 0f),
            )
            SignalStatRow(
                "Laughter score",
                String.format(Locale.US, "%.3f", verdict?.laughterScore ?: 0f),
            )
        }
    }
}

@Composable
private fun ThresholdEffectCard(verdict: WindowVerdict?, config: DecisionConfig) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Effect on the alert bar", style = MaterialTheme.typography.titleSmall)
            SignalStatRow("Base medium threshold", String.format(Locale.US, "%.3f", config.mediumThreshold))
            SignalStatRow(
                "Effective medium",
                verdict?.let { String.format(Locale.US, "%.3f", it.effectiveMediumThreshold) } ?: "—",
            )
            SignalStatRow("Base high threshold", String.format(Locale.US, "%.3f", config.highThreshold))
            SignalStatRow(
                "Effective high",
                verdict?.let { String.format(Locale.US, "%.3f", it.effectiveHighThreshold) } ?: "—",
            )
            SignalStatRow("Cap", String.format(Locale.US, "×%.1f", config.maxMediaFactor))
            Text(
                "The effective bar, not the base one, is what the fused score must cross.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
