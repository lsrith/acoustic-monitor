// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui.walkthrough

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.lsrith.acousticmonitor.llm.ReasonedWindow
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


@Composable
fun ReasoningPage(
    reasoning: ReasonedWindow?,
    strideSeconds: Double,
    error: String?,
    busy: Boolean,
    phase: String?,
    stats: LatencyTracker.Stats,
    modelName: String,
    backend: String,
    unavailable: Boolean,
    onAnalyseNow: () -> Unit,
) {
    SignalPage(
        title = "Reasoning",
        signalDescription = "signal fusion and baseline comparison",
        modelLine = "$modelName · $backend · LiteRT-LM · on-device",
        whatItDoes = "A small on-device language model reads the last window of evidence " +
            "from every other page — sound cues, level vs room, transcript with lexicon " +
            "hits, voice identity, turn-taking — and says whether it is concerning and why. " +
            "It runs at whatever pace the phone sustains: a slow generation widens the next " +
            "window rather than falling behind. On runs longer than about twenty minutes it " +
            "has crashed the process — a known, open issue.",
        tryIt = "Make some noise and say something to the room, then press \"Judge now\" " +
            "(or wait one stride), then read the reason it gives.",
    ) {
        if (unavailable) {
            UnavailableCard()
        }
        LatestJudgementCard(reasoning, error)
        EvidenceCard(reasoning)
        CadenceCard(strideSeconds, busy, phase, stats, unavailable, onAnalyseNow)
    }
}

@Composable
private fun UnavailableCard() {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                "The selected reasoning model could not run on this phone. Choose another " +
                    "in Setup.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun LatestJudgementCard(reasoning: ReasonedWindow?, error: String?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Latest judgement", style = MaterialTheme.typography.titleSmall)
            if (reasoning == null) {
                NothingYet("No judgement yet.")
            } else {
                Text(
                    if (reasoning.concerning) "concerning" else "not concerning",
                    style = MaterialTheme.typography.bodyMedium,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    color = if (reasoning.concerning) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(reasoning.reason, style = MaterialTheme.typography.bodyMedium)
                SignalStatRow("Model", reasoning.modelName)
                Text(
                    String.format(
                        Locale.US,
                        "%.0f s window · %.0f ms to generate",
                        reasoning.windowSeconds, reasoning.generateMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Text(
                    TIME_FORMAT.format(Date(reasoning.atEpochMs)),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (error != null) {
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The raw timeline the model was handed — long, and left unformatted on purpose. */
@Composable
private fun EvidenceCard(reasoning: ReasonedWindow?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Evidence it read", style = MaterialTheme.typography.titleSmall)
            if (reasoning == null) {
                NothingYet("No evidence yet.")
            } else {
                Text(
                    reasoning.evidence,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
        }
    }
}

@Composable
private fun CadenceCard(
    strideSeconds: Double,
    busy: Boolean,
    phase: String?,
    stats: LatencyTracker.Stats,
    unavailable: Boolean,
    onAnalyseNow: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Cadence", style = MaterialTheme.typography.titleSmall)
            SignalStatRow("Stride", String.format(Locale.US, "%.0f s", strideSeconds))
            Text(
                phase ?: if (busy) "Busy" else "Idle",
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (stats.count == 0) {
                NothingYet("No runs yet.")
            } else {
                SignalStatRow("Count", stats.count.toString())
                SignalStatRow("p50", String.format(Locale.US, "%.0f ms", stats.p50))
                SignalStatRow("p95", String.format(Locale.US, "%.0f ms", stats.p95))
                SignalStatRow("Max", String.format(Locale.US, "%.0f ms", stats.max))
            }
            Button(
                onClick = onAnalyseNow,
                enabled = !busy && !unavailable,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Judge now") }
        }
    }
}

private val TIME_FORMAT = SimpleDateFormat("HH:mm:ss", Locale.US)
