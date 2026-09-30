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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import org.lsrith.acousticmonitor.speech.SpeechEngine
import org.lsrith.acousticmonitor.speech.TranscriptSegment

/**
 * The context path, in the open: what Silero cut the audio into, and what
 * each recogniser made of it.
 */
@Composable
fun TranscriptionPage(
    transcript: List<TranscriptSegment>,
    speaking: Boolean,
    speechError: String?,
    asrStats: LatencyTracker.Stats,
    altAsrStats: LatencyTracker.Stats,
    detectedLanguage: String?,
    secondPassActive: Boolean,
) {
    val modelLine = buildString {
        append(SpeechEngine.PRIMARY_LABEL)
        if (secondPassActive) append(" + ${SpeechEngine.SECOND_PASS_LABEL}")
        append(" · sherpa-onnx · Silero VAD · on-device")
    }
    SignalPage(
        title = "Transcription",
        signalDescription = "real-time transcription",
        whatItDoes = "Silero VAD segments the microphone stream into finished utterances; " +
            "each segment is decoded by both recognisers in parallel and both readings are " +
            "kept, because nothing running on this device can reliably choose which one is " +
            "right. The language shown is whatever the primary recogniser reports for that " +
            "segment, not a setting — this device runs unattended in a home where several " +
            "languages are spoken, sometimes in the same sentence.",
        tryIt = "Speak a sentence, pause, and watch the segment arrive. Then try a sentence " +
            "in a second language and compare what each recogniser reports.",
        modelLine = modelLine,
    ) {
        NowCard(speaking, detectedLanguage, secondPassActive, speechError)
        TranscriptCard(transcript)
        KeepingUpCard(asrStats, altAsrStats)
    }
}

/** The instantaneous state a presenter checks before speaking. */
@Composable
private fun NowCard(
    speaking: Boolean,
    detectedLanguage: String?,
    secondPassActive: Boolean,
    speechError: String?,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Now", style = MaterialTheme.typography.titleSmall)
            SignalStatRow("Speech", if (speaking) "Speech detected" else "Silence")
            SignalStatRow("Detected language", detectedLanguage ?: "—")
            SignalStatRow("Second recogniser", if (secondPassActive) "active" else "not active")
            speechError?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/** The recent segments themselves, both readings attributed rather than one presented as final. */
@Composable
private fun TranscriptCard(transcript: List<TranscriptSegment>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Transcript", style = MaterialTheme.typography.titleSmall)
            val recent = transcript.take(12)
            if (recent.isEmpty()) {
                NothingYet("Nothing transcribed yet — say something and pause.")
            } else {
                val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    recent.forEach { segment -> TranscriptRow(segment, timeFormat) }
                }
            }
        }
    }
}

@Composable
private fun TranscriptRow(segment: TranscriptSegment, timeFormat: SimpleDateFormat) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            timeFormat.format(Date(segment.startEpochMs)),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("${segment.modelLabel}: ${segment.text}", style = MaterialTheme.typography.bodyMedium)
        if (segment.altText.isNotEmpty()) {
            Text(
                "${segment.altModelLabel}: ${segment.altText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            String.format(
                Locale.US, "%.1fs audio · %.0f ms · RTF %.2f · gain ×%.1f",
                segment.audioSeconds, segment.asrMs, segment.realTimeFactor, segment.asrGain,
            ),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val tags = listOfNotNull(
            segment.language.takeIf { it.isNotEmpty() },
            segment.emotion.takeIf { it.isNotEmpty() },
        )
        if (tags.isNotEmpty()) {
            Text(
                tags.joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Whether each recogniser is fast enough to matter, not just accurate. */
@Composable
private fun KeepingUpCard(asrStats: LatencyTracker.Stats, altAsrStats: LatencyTracker.Stats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Keeping up", style = MaterialTheme.typography.titleSmall)
            StatsBlock(SpeechEngine.PRIMARY_LABEL, asrStats)
            StatsBlock(SpeechEngine.SECOND_PASS_LABEL, altAsrStats)
        }
    }
}

@Composable
private fun StatsBlock(label: String, stats: LatencyTracker.Stats) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (stats.count == 0) {
            Text("no segments yet", style = MaterialTheme.typography.bodySmall)
        } else {
            SignalStatRow("count", stats.count.toString())
            SignalStatRow("p50", String.format(Locale.US, "%.0f ms", stats.p50))
            SignalStatRow("p95", String.format(Locale.US, "%.0f ms", stats.p95))
            SignalStatRow("max", String.format(Locale.US, "%.0f ms", stats.max))
        }
    }
}
