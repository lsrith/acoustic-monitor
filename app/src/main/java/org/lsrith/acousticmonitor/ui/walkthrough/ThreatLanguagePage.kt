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
import java.util.Locale
import org.lsrith.acousticmonitor.detect.DecisionConfig
import org.lsrith.acousticmonitor.detect.ThreatAssessment
import org.lsrith.acousticmonitor.detect.ThreatLexicon
import org.lsrith.acousticmonitor.monitor.SignalHistory
import org.lsrith.acousticmonitor.speech.SpeechEngine
import org.lsrith.acousticmonitor.speech.TranscriptSegment
import org.lsrith.acousticmonitor.ui.SignalChart
import org.lsrith.acousticmonitor.ui.TraceColors
import org.lsrith.acousticmonitor.ui.rememberTrace


@Composable
fun ThreatLanguagePage(
    transcript: List<TranscriptSegment>,
    latestThreat: SpeechEngine.TimedThreat?,
    history: List<SignalHistory.Sample>,
    config: DecisionConfig,
) {
    SignalPage(
        title = "Threat language",
        signalDescription = "threat/toxicity classification",
        whatItDoes = "Every phrase that can move this score is a literal entry in a " +
            "plain-English lexicon (ThreatLexicon), so a reviewer can read exactly what " +
            "fired and why — this is rules over the transcript, not a learned classifier. " +
            "A toxicity model exists elsewhere in this build but is not wired into this " +
            "path, so it contributes nothing to what is shown here.",
        tryIt = "Say a sentence containing a threat to the room, e.g. \"I'm going to hit " +
            "you if you don't shut up\", then a neutral one. The hit appears when the " +
            "transcript for that segment arrives — about a second after you stop speaking.",
        modelLine = "Rules-based lexicon over the transcript · ThreatLexicon " +
            "v${ThreatLexicon.VERSION} · on-device",
    ) {
        LatestReadingCard(latestThreat)
        DecayCard(config)
        PerSegmentHitsCard(transcript)
        SignalChart(
            title = "Threat path over time",
            explanation = "The threat-language path's contribution to the fused score. It " +
                "rises the instant a segment scores and decays with age afterwards — " +
                "language is evidence while it is recent, context once it is not.",
            traces = listOf(
                rememberTrace("Threat", TraceColors.alarm, history) { it.threatPath },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )
    }
}

/** What the lexicon last found, and how long ago — a stale hit reads very differently from a fresh one. */
@Composable
private fun LatestReadingCard(latestThreat: SpeechEngine.TimedThreat?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Latest reading", style = MaterialTheme.typography.titleSmall)
            if (latestThreat == null) {
                NothingYet("No threat language heard yet.")
                return@Column
            }
            val assessment = latestThreat.assessment
            // A clock, not a signal: this is the age of the reading, not a
            // new measurement of anything the microphone heard.
            val secondsAgo = (System.currentTimeMillis() - latestThreat.atEpochMs) / 1000.0
            SignalBar("Score", assessment.score)
            SignalStatRow("Category", assessment.topCategory?.displayName ?: "—")
            SignalStatRow("Heard", String.format(Locale.US, "%.0f s ago", secondsAgo))
            assessment.hits.forEach { hit ->
                Text(
                    "${hit.category.displayName} · ${hit.phrase}" +
                        if (hit.negated) "  (negated)" else "",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = if (hit.negated) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** The two numbers that turn one reading into a time-limited contribution to fusion. */
@Composable
private fun DecayCard(config: DecisionConfig) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Decay", style = MaterialTheme.typography.titleSmall)
            Text(
                String.format(
                    Locale.US,
                    "Fusion decays this reading over %.0f s and weights the path at ×%.2f " +
                        "against the others.",
                    config.threatDecaySeconds, config.threatWeight,
                ),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * The transcript rows a reviewer would actually want to check first — the
 * ones the lexicon matched, both readings when there were two.
 */
@Composable
private fun PerSegmentHitsCard(transcript: List<TranscriptSegment>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Per-segment hits", style = MaterialTheme.typography.titleSmall)
            val hits = transcript.filter {
                (it.threat != null && !it.threat.isEmpty) ||
                    (it.altThreat != null && !it.altThreat.isEmpty)
            }.take(10)
            if (hits.isEmpty()) {
                NothingYet("No segment has matched the lexicon yet.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    hits.forEach { segment -> SegmentHitRow(segment) }
                }
            }
        }
    }
}

@Composable
private fun SegmentHitRow(segment: TranscriptSegment) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("${segment.modelLabel}: ${segment.text}", style = MaterialTheme.typography.bodyMedium)
        segment.threat?.takeIf { !it.isEmpty }?.let { AssessmentLine(it) }
        if (segment.altText.isNotEmpty()) {
            Text(
                "${segment.altModelLabel}: ${segment.altText}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            segment.altThreat?.takeIf { !it.isEmpty }?.let { AssessmentLine(it) }
        }
    }
}

@Composable
private fun AssessmentLine(assessment: ThreatAssessment) {
    Text(
        String.format(
            Locale.US, "%s · %.2f",
            assessment.topCategory?.displayName ?: "—", assessment.score,
        ),
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.error,
    )
}
