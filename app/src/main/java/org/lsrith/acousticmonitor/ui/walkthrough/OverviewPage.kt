// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui.walkthrough

import androidx.compose.foundation.clickable
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
import java.util.Locale
import org.lsrith.acousticmonitor.audio.AudioPipeline
import org.lsrith.acousticmonitor.monitor.MonitorEngine
import org.lsrith.acousticmonitor.ui.ScreenColumn


private data class SignalRow(
    val term: String,
    val tab: SignalTab?,
    val status: String,
    val note: String? = null,
)


private val SIGNAL_ROWS = listOf(
    SignalRow("Sound event classification", SignalTab.SOUNDS, "Sound events"),
    SignalRow(
        term = "Threat/toxicity classification",
        tab = SignalTab.THREAT,
        status = "Threat language",
        note = "Lexicon only — the toxicity model is built but not wired in.",
    ),
    SignalRow(
        term = "Vocal strain analysis",
        tab = SignalTab.STRESS,
        status = "Vocal stress",
        note = "Strain, not emotion.",
    ),
    SignalRow("Voice activity recognition", SignalTab.VOICE, "Voice activity"),
    SignalRow(
        term = "Media playback classification",
        tab = SignalTab.MEDIA,
        status = "Media playback",
        note = "Classifies broadcast media; does not detect replay attacks.",
    ),
    SignalRow("Speaker diarization & verification", SignalTab.SPEAKERS, "Speakers"),
    SignalRow("Real-time transcription", SignalTab.TRANSCRIPT, "Transcription"),
    SignalRow("Signal fusion and reasoning", SignalTab.REASONING, "Reasoning"),
)


@Composable
fun OverviewPage(
    capture: AudioPipeline.CaptureState,
    calibration: MonitorEngine.Calibration,
    onSwitchToProduct: () -> Unit,
    onOpen: (SignalTab) -> Unit,
) {
    ScreenColumn {
        Text("On-device signal walkthrough", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Every signal below runs on this phone. Nothing leaves the device.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "Only the page on screen runs its models, so each one is measured on its own.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        CaptureCard(capture, calibration)

        Text("Available signal analyses", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column {
                SIGNAL_ROWS.forEach { row -> SignalRowItem(row, onOpen) }
            }
        }

        OutlinedButton(onClick = onSwitchToProduct) {
            Text("Switch to monitoring view")
        }
    }
}

/**
 * The one signal every other page depends on: whether the microphone is
 * running, and how far the room's baseline has been learned. A page that
 * looks quiet and a page whose engine has nothing to say are indistinguishable
 * without this.
 */
@Composable
private fun CaptureCard(capture: AudioPipeline.CaptureState, calibration: MonitorEngine.Calibration) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Capture", style = MaterialTheme.typography.titleSmall)
                Text(
                    if (capture.running) "listening" else "stopped",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (capture.running) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            SignalStatRow("Source", capture.audioSourceName)
            SignalStatRow("Windows emitted", capture.windowsEmitted.toString())
            SignalStatRow("Dropped windows", capture.droppedWindows.toString())
            capture.error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }

            if (calibration.neverCalibrated) {
                Text(
                    "Room not calibrated yet — start it from Setup.",
                    style = MaterialTheme.typography.bodySmall,
                )
            } else {
                LinearProgressIndicator(
                    progress = { calibration.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    String.format(
                        Locale.US, "%d:%02d observed · %d%% confidence%s",
                        calibration.observedSeconds / 60,
                        calibration.observedSeconds % 60,
                        (calibration.progress * 100).toInt(),
                        if (calibration.ready) " · ready" else "",
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}


@Composable
private fun SignalRowItem(row: SignalRow, onOpen: (SignalTab) -> Unit) {
    val tab = row.tab
    Column(
        (if (tab != null) Modifier.clickable { onOpen(tab) } else Modifier)
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                row.term,
                style = MaterialTheme.typography.bodyMedium,
                color = if (tab != null) MaterialTheme.colorScheme.onSurface
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                row.status,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (tab != null) FontWeight.Medium else FontWeight.Normal,
                color = if (tab != null) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        row.note?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
