// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui.walkthrough

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.speaker.DiarizationResult
import org.lsrith.acousticmonitor.speaker.SpeakerEngine
import org.lsrith.acousticmonitor.speaker.SpeakerMatch
import java.util.Locale

/**
 * Voice identity: who is speaking, and how many.
 *
 * Verification and diarization answer different questions from the same
 * utterance — one says whose voice it is, the other says how the floor was
 * shared — and both are shown together because a presenter demonstrating one
 * naturally demonstrates the other.
 */
@Composable
fun SpeakersPage(
    enrolled: List<String>,
    lastMatch: SpeakerMatch?,
    speakerReady: Boolean,
    speakerError: String?,
    enrolling: Boolean,
    enrolSecondsRemaining: Int,
    diarization: DiarizationResult?,
    voiceIdLatency: LatencyTracker.Stats,
    diarizationLatency: LatencyTracker.Stats,
    onStartEnrol: (String) -> Unit,
    onRemoveVoice: (String) -> Unit,
) {
    SignalPage(
        title = "Speakers",
        signalDescription = "speaker diarization & verification",
        modelLine = "${ModelCatalog.SPEAKER_EMBEDDING.displayName} + " +
            "${ModelCatalog.SPEAKER_SEGMENTATION.displayName} · sherpa-onnx · on-device",
        whatItDoes = "Two things happen on every finished utterance. Verification embeds it " +
            "and compares the vector against enrolled voiceprints by cosine similarity, " +
            "matching above ${SpeakerEngine.DEFAULT_THRESHOLD} — a plausible cutoff, not one " +
            "calibrated against real voices. Diarization segments the same utterance into " +
            "who-spoke-when, giving speaker count, turns per minute and overlap: what " +
            "separates a shouted monologue from a shouting match.",
        tryIt = "Enrol one person (needs about 8 seconds of actual speech), then have them " +
            "speak alone and watch the match. Then have two people talk over each other and " +
            "watch overlap and turns/min move.",
    ) {
        EnrolCard(
            speakerReady = speakerReady,
            enrolling = enrolling,
            enrolSecondsRemaining = enrolSecondsRemaining,
            speakerError = speakerError,
            onStartEnrol = onStartEnrol,
        )
        EnrolledVoicesCard(enrolled = enrolled, onRemoveVoice = onRemoveVoice)
        LastMatchCard(lastMatch)
        TurnTakingCard(diarization)
        SpeakerLatencyCard(voiceIdLatency, diarizationLatency)
    }
}

@Composable
private fun EnrolCard(
    speakerReady: Boolean,
    enrolling: Boolean,
    enrolSecondsRemaining: Int,
    speakerError: String?,
    onStartEnrol: (String) -> Unit,
) {
    var name by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Enrol", style = MaterialTheme.typography.titleSmall)
            if (!speakerReady) {
                Text(
                    "${ModelCatalog.SPEAKER_EMBEDDING.displayName} is not downloaded — fetch " +
                        "it from Models in the product view before enrolling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                placeholder = { Text("Child, Adult A, …") },
                enabled = !enrolling,
                modifier = Modifier.fillMaxWidth(),
            )
            if (enrolling) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Keep talking — $enrolSecondsRemaining s of speech to go",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            } else {
                Button(
                    onClick = { onStartEnrol(name); name = "" },
                    enabled = speakerReady && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Enrol voice") }
            }
            if (speakerError != null) {
                Text(
                    speakerError,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EnrolledVoicesCard(enrolled: List<String>, onRemoveVoice: (String) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Enrolled voices", style = MaterialTheme.typography.titleSmall)
            if (enrolled.isEmpty()) {
                NothingYet("No voices enrolled yet.")
            } else {
                enrolled.forEach { name ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name, style = MaterialTheme.typography.bodyMedium)
                        TextButton(onClick = { onRemoveVoice(name) }) { Text("Remove") }
                    }
                }
            }
        }
    }
}

@Composable
private fun LastMatchCard(lastMatch: SpeakerMatch?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Last match", style = MaterialTheme.typography.titleSmall)
            if (lastMatch == null) {
                NothingYet("No utterance compared yet.")
            } else {
                SignalStatRow("Name", lastMatch.name)
                SignalStatRow("Similarity", String.format(Locale.US, "%.2f", lastMatch.similarity))
                SignalStatRow("Result", if (lastMatch.isMatch) "match" else "no match")
            }
        }
    }
}

/** How the whole clip was split into who-spoke-when, from the last diarized utterance. */
@Composable
private fun TurnTakingCard(diarization: DiarizationResult?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Turn-taking (last utterance)", style = MaterialTheme.typography.titleSmall)
            if (diarization == null) {
                NothingYet("No utterance diarized yet.")
            } else {
                SignalStatRow("Speakers", diarization.speakerCount.toString())
                SignalStatRow("Speech", String.format(Locale.US, "%.1f s", diarization.totalSpeechSec))
                SignalStatRow("Turns/min", String.format(Locale.US, "%.0f", diarization.turnsPerMinute))
                SignalStatRow("Overlap", String.format(Locale.US, "%.2f", diarization.overlapRatio))
                SignalStatRow("Processed in", String.format(Locale.US, "%.0f ms", diarization.processingMs))
                diarization.turns.forEach { turn ->
                    Text(
                        String.format(
                            Locale.US,
                            "Speaker %d  %.1f–%.1fs (%.1fs)",
                            turn.speaker, turn.startSec, turn.endSec, turn.durationSec,
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }
    }
}

@Composable
private fun SpeakerLatencyCard(voiceIdLatency: LatencyTracker.Stats, diarizationLatency: LatencyTracker.Stats) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Latency", style = MaterialTheme.typography.titleSmall)
            Text("Voice identity", style = MaterialTheme.typography.bodyMedium)
            LatencyRows(voiceIdLatency)
            Text("Diarization", style = MaterialTheme.typography.bodyMedium)
            LatencyRows(diarizationLatency)
        }
    }
}

@Composable
private fun LatencyRows(stats: LatencyTracker.Stats) {
    if (stats.count == 0) {
        NothingYet("No runs yet.")
    } else {
        SignalStatRow("Count", stats.count.toString())
        SignalStatRow("p50", String.format(Locale.US, "%.0f ms", stats.p50))
        SignalStatRow("p95", String.format(Locale.US, "%.0f ms", stats.p95))
        SignalStatRow("Max", String.format(Locale.US, "%.0f ms", stats.max))
    }
}
