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
import org.lsrith.acousticmonitor.audio.AudioPipeline
import org.lsrith.acousticmonitor.detect.WindowVerdict
import org.lsrith.acousticmonitor.monitor.RoomReading
import org.lsrith.acousticmonitor.monitor.SignalHistory
import org.lsrith.acousticmonitor.ui.SignalChart
import org.lsrith.acousticmonitor.ui.TraceColors
import org.lsrith.acousticmonitor.ui.rememberTrace

/**
 * Two independent "someone is talking" readings, side by side.
 *
 * The gating VAD (Silero) decides whether the transcriber runs at all; the
 * sound classifier's speech classes are a second, unrelated opinion from the
 * same audio. They are shown together because they can and do disagree — a
 * distant voice can fail one and pass the other — and neither is corrected
 * toward the other here.
 */
@Composable
internal fun VoiceActivityPage(
    speaking: Boolean,
    level: Float,
    room: RoomReading,
    inputGain: Float,
    verdict: WindowVerdict?,
    history: List<SignalHistory.Sample>,
    capture: AudioPipeline.CaptureState,
) {
    SignalPage(
        title = "Voice activity",
        signalDescription = "voice activity recognition",
        modelLine = "Silero VAD · sherpa-onnx · plus YAMNet speech classes · on-device",
        whatItDoes = "Two independent readings of \"is someone talking\": a dedicated " +
            "voice-activity model that gates the transcriber, and the sound classifier's own " +
            "speech classes — plus the level against the learned room baseline.",
        tryIt = "Speak from across the room, then whisper, then stay silent. Watch the " +
            "auto-gain climb as the input gets quieter.",
    ) {
        NowCard(speaking, verdict, level, room, inputGain)

        SignalChart(
            title = "Level vs baseline",
            explanation = "The microphone level now against what this room normally sounds " +
                "like. A gap between them is what the loudness path reacts to.",
            traces = listOf(
                rememberTrace("Now", TraceColors.measured, history) { it.currentDbfs },
                rememberTrace("Baseline", TraceColors.reference, history, dashed = true) { it.baselineDbfs },
            ),
            unit = " dBFS",
        )

        SignalChart(
            title = "Speech score over time",
            explanation = "YAMNet's speech classes over recent windows — the second, " +
                "independent opinion on whether someone is talking.",
            traces = listOf(
                rememberTrace("Speech score", TraceColors.speech, history) { it.speechScore },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )

        CaptureCard(capture)
    }
}

@Composable
private fun NowCard(
    speaking: Boolean,
    verdict: WindowVerdict?,
    level: Float,
    room: RoomReading,
    inputGain: Float,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Now", style = MaterialTheme.typography.titleSmall)
            Text(
                if (speaking) "Speech detected" else "Silence",
                style = MaterialTheme.typography.titleMedium,
            )
            if (verdict != null) {
                SignalBar("Speech classes (YAMNet)", verdict.speechScore)
            } else {
                NothingYet("No window judged yet.")
            }
            SignalStatRow("Level", String.format(Locale.US, "%.1f dBFS", level))
            SignalStatRow("Room baseline", String.format(Locale.US, "%.1f dBFS", room.baselineDbfs))
            SignalStatRow("Deviation", String.format(Locale.US, "%+.1f dB", room.deviationDb))
            SignalStatRow("Auto-gain", String.format(Locale.US, "×%.1f", inputGain))
        }
    }
}

/** Whether the one shared microphone stream is actually running and keeping up. */
@Composable
private fun CaptureCard(capture: AudioPipeline.CaptureState) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Capture", style = MaterialTheme.typography.titleSmall)
            SignalStatRow("Audio source", capture.audioSourceName)
            SignalStatRow("Windows emitted", capture.windowsEmitted.toString())
            SignalStatRow("Dropped windows", capture.droppedWindows.toString())
        }
    }
}
