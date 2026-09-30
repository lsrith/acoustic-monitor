// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import org.lsrith.acousticmonitor.data.AlertEventEntity

/** What one component contributed to an event, and whether it contributed at all. */
data class ModelContribution(
    val component: String,
    val kind: String,
    val contribution: String,
    /** False when the component was present but had nothing to say. */
    val contributed: Boolean,
)

/**
 * Attributes an event to the components that produced it.
 *
 * There are now several models running concurrently and a summary naming only
 * the loudest AudioSet class reads as though that class was the whole reason.
 * Worse, it hides the components that said *nothing* — and a path staying
 * silent is itself evidence, especially when a decision rested on one signal
 * alone.
 *
 * Parsed from the stored summary rather than from new columns, so existing
 * recorded events stay readable.
 */
object EventEvidence {

    fun of(event: AlertEventEntity): List<ModelContribution> {
        val summary = event.cueSummary
        val paths = summary.substringAfter("paths:", "").trim()

        fun path(name: String): String? = paths
            .split(",")
            .map { it.trim() }
            .firstOrNull { it.startsWith(name) }
            ?.removePrefix(name)
            ?.trim()

        val integrity = event.peakCueGroup == AlertRepository.INTEGRITY_GROUP
        if (integrity) {
            return listOf(
                ModelContribution(
                    component = "Mic integrity",
                    kind = "DSP — level and spectral tilt",
                    contribution = summary,
                    contributed = true,
                ),
                ModelContribution(
                    component = "Everything else",
                    kind = "—",
                    contribution = "Not applicable: this records a loss of signal, not a detection.",
                    contributed = false,
                ),
            )
        }

        val cue = path("cue")
        val loudness = path("loudness")
        val language = path("language")
        val strain = path("strain")
        val laughter = path("laughter")

        return listOf(
            ModelContribution(
                component = "YAMNet",
                kind = "521 AudioSet classes",
                contribution = summary.substringBefore(" · paths:").substringAfter("sustained pattern · "),
                contributed = cue != null,
            ),
            ModelContribution(
                component = "Room loudness",
                kind = "DSP — deviation from learned baseline",
                contribution = loudness ?: "within the room's normal",
                contributed = loudness != null,
            ),
            ModelContribution(
                component = "Threat lexicon",
                kind = "rules over the transcript, ${ThreatLexicon.VERSION}",
                contribution = language ?: "no threat language matched",
                contributed = language != null,
            ),
            ModelContribution(
                component = "Prosody",
                kind = "DSP — jitter, shimmer, harmonicity",
                contribution = strain ?: "no vocal strain measured",
                contributed = strain != null,
            ),
            ModelContribution(
                component = "Media and laughter",
                kind = "YAMNet context classes — demote only",
                contribution = buildString {
                    append("media %.2f".format(event.mediaScoreAtPeak))
                    laughter?.let { append(", laughter $it") }
                },
                contributed = event.mediaScoreAtPeak > 0.05f || laughter != null,
            ),
            ModelContribution(
                component = "Toxicity classifier",
                kind = "Toxic-BERT ONNX",
                contribution = "Built but not wired into fusion — contributes nothing yet.",
                contributed = false,
            ),
            ModelContribution(
                component = "Speaker verification",
                kind = "WeSpeaker embedding",
                contribution = "Not consulted for this event.",
                contributed = false,
            ),
        )
    }


    fun promptFacts(event: AlertEventEntity): String = buildString {
        appendLine("Severity: ${event.severity}")
        appendLine("Duration: ${(event.endedAtEpochMs - event.startedAtEpochMs) / 1000} s over ${event.windowCount} windows")
        appendLine("Peak fused score: ${"%.2f".format(event.peakFusedScore)}")
        appendLine("Loudest cue: ${event.peakCueLabel}")
        appendLine()
        appendLine("Component contributions:")
        of(event).forEach {
            appendLine("- ${it.component} (${it.kind}): ${it.contribution}${if (it.contributed) "" else "  [no contribution]"}")
        }
        appendLine()
        appendLine("Only recorded audio signals are available. Do not infer physical context, intent, or unmeasured conditions.")
    }
}
