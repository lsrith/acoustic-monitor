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
import org.lsrith.acousticmonitor.detect.DecisionConfig
import org.lsrith.acousticmonitor.detect.WindowVerdict
import org.lsrith.acousticmonitor.dsp.ProsodyFrame
import org.lsrith.acousticmonitor.monitor.SignalHistory
import org.lsrith.acousticmonitor.ui.SignalChart
import org.lsrith.acousticmonitor.ui.TraceColors
import org.lsrith.acousticmonitor.ui.rememberTrace

/**
 * Vocal strain from waveform shape alone — jitter, shimmer, loss of
 * harmonicity — not a learned emotion model.
 *
 * Shown separately from the fused decision because it never decides anything
 * by itself: [DecisionConfig.stressBoost] only lets it amplify a cue that
 * already exists (see [org.lsrith.acousticmonitor.detect.DecisionEngine]).
 */
@Composable
internal fun VocalStressPage(
    voice: ProsodyFrame,
    verdict: WindowVerdict?,
    history: List<SignalHistory.Sample>,
    config: DecisionConfig,
) {
    SignalPage(
        title = "Vocal stress",
        signalDescription = "speech emotion recognition",
        modelLine = "Prosody DSP (pitch, jitter, shimmer, harmonicity, spectral tilt) · " +
            "no neural model · on-device",
        whatItDoes = "This measures strain in the voice, not emotion: jitter, shimmer and " +
            "loss of harmonicity rise when a voice is shouted, strained or breaking. It is " +
            "only defined over voiced speech, and the normalising constants have not been " +
            "calibrated against real voices. It never fires an alert on its own — it only " +
            "amplifies the acoustic cue path, by up to a factor of " +
            String.format(Locale.US, "%.2f", config.stressBoost) + " (config.stressBoost).",
        tryIt = "Speak calmly, then raise your voice sharply or speak with strain in it. " +
            "Watch strain rise in a way loudness alone would not explain.",
    ) {
        NowCard(voice, verdict)

        SignalChart(
            title = "Strain over time",
            explanation = "Vocal strain over recent windows. Zero over unvoiced audio by " +
                "definition — there is nothing to measure without a pitched voice.",
            traces = listOf(
                rememberTrace("Strain", TraceColors.warn, history) { it.vocalStress },
            ),
            fixedMin = 0f,
            fixedMax = 1f,
        )

        WhatMovesItCard()
    }
}

@Composable
private fun NowCard(voice: ProsodyFrame, verdict: WindowVerdict?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Now", style = MaterialTheme.typography.titleSmall)
            SignalStatRow("Voiced", if (voice.voiced) "yes" else "no")
            if (voice.voiced) {
                SignalStatRow("F0", String.format(Locale.US, "%.0f Hz", voice.f0Hz))
                SignalStatRow("Jitter", String.format(Locale.US, "%.3f", voice.jitter))
                SignalStatRow("Shimmer", String.format(Locale.US, "%.3f", voice.shimmer))
                SignalStatRow("Harmonicity", String.format(Locale.US, "%.2f", voice.harmonicity))
                SignalStatRow("Vocal effort", String.format(Locale.US, "%.1f dB", voice.vocalEffortDb))
                SignalStatRow("Spectral tilt", String.format(Locale.US, "%.2f", voice.spectralTilt))
                if (verdict != null) {
                    SignalBar("Strain", verdict.vocalStress)
                } else {
                    NothingYet("No window judged yet.")
                }
            } else {
                NothingYet("Unvoiced — strain is only defined over speech.")
                SignalStatRow("Vocal effort", String.format(Locale.US, "%.1f dB", voice.vocalEffortDb))
                SignalStatRow("Spectral tilt", String.format(Locale.US, "%.2f", voice.spectralTilt))
                SignalStatRow("Harmonicity", String.format(Locale.US, "%.2f", voice.harmonicity))
            }
        }
    }
}


@Composable
private fun WhatMovesItCard() {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("What moves it", style = MaterialTheme.typography.titleSmall)
            Text(
                "Jitter and shimmer: under strain the glottis loses fine control of both " +
                    "timing and amplitude, so pitch period and peak amplitude wobble frame to " +
                    "frame even on a held note — and this rises before loudness does.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Spectral tilt: shouting recruits more high-frequency energy than raising " +
                    "volume alone would predict, so a rising treble share is itself a strain " +
                    "signal, not just a loudness one.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "The constants that turn jitter and shimmer into a 0..1 strain figure are " +
                    "guesses — they have not been calibrated against real voices in real rooms.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
