// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui.walkthrough

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.lsrith.acousticmonitor.audio.AudioPipeline
import org.lsrith.acousticmonitor.classify.FrameResult
import org.lsrith.acousticmonitor.detect.DecisionConfig
import org.lsrith.acousticmonitor.detect.WindowVerdict
import org.lsrith.acousticmonitor.dsp.ProsodyFrame
import org.lsrith.acousticmonitor.llm.ReasonedWindow
import org.lsrith.acousticmonitor.metrics.LatencyTracker
import org.lsrith.acousticmonitor.monitor.MonitorEngine
import org.lsrith.acousticmonitor.monitor.RoomReading
import org.lsrith.acousticmonitor.monitor.SignalHistory
import org.lsrith.acousticmonitor.monitor.StageGate
import org.lsrith.acousticmonitor.speaker.DiarizationResult
import org.lsrith.acousticmonitor.speaker.SpeakerMatch
import org.lsrith.acousticmonitor.speech.SpeechEngine
import org.lsrith.acousticmonitor.speech.TranscriptSegment


enum class SignalTab(val label: String, val stages: StageGate.Stages) {
    OVERVIEW("Overview", StageGate.Stages.NONE),
    SOUNDS("Sounds", StageGate.Stages.NONE.copy(classifier = true)),
    /** The lexicon reads the transcript, so the recognisers must run. */
    THREAT("Threat", StageGate.Stages.NONE.copy(classifier = true, transcription = true)),
    /** Voice detection is part of the speech engine and always on; the speech classes come from YAMNet. */
    VOICE("Voice", StageGate.Stages.NONE.copy(classifier = true)),
    TRANSCRIPT("Transcript", StageGate.Stages.NONE.copy(transcription = true)),
    /** Voiceprints and turn-taking need the segmentation, not the words. */
    SPEAKERS("Speakers", StageGate.Stages.NONE.copy(voices = true)),
    /** Prosody is not gated; the strain figure is carried on the verdict. */
    STRESS("Stress", StageGate.Stages.NONE.copy(classifier = true)),
    MEDIA("Media", StageGate.Stages.NONE.copy(classifier = true)),
    /** The reasoning layer judges everything, so everything feeds it. */
    REASONING("Reasoning", StageGate.Stages.ALL),
}

/**
 * Everything the walkthrough pages read, gathered once.
 *
 * Every field is a value the engine already publishes — this bundles, it does
 * not derive. Nothing here is computed for display.
 */
data class WalkthroughState(
    val capture: AudioPipeline.CaptureState,
    val calibration: MonitorEngine.Calibration,
    val level: Float,
    val room: RoomReading,
    val frame: FrameResult?,
    val verdict: WindowVerdict?,
    val history: List<SignalHistory.Sample>,
    val latency: MonitorEngine.LatencySnapshot,
    val config: DecisionConfig,
    val transcript: List<TranscriptSegment>,
    val latestThreat: SpeechEngine.TimedThreat?,
    val speaking: Boolean,
    val inputGain: Float,
    val speechError: String?,
    val asrStats: LatencyTracker.Stats,
    val altAsrStats: LatencyTracker.Stats,
    val detectedLanguage: String?,
    val secondPassActive: Boolean,
    val enrolled: List<String>,
    val lastMatch: SpeakerMatch?,
    val speakerReady: Boolean,
    val speakerError: String?,
    val enrolling: Boolean,
    val enrolSecondsRemaining: Int,
    val diarization: DiarizationResult?,
    val voice: ProsodyFrame,
    val reasoning: ReasonedWindow?,
    val reasoningStrideSeconds: Double,
    val reasoningError: String?,
    val reasoningBusy: Boolean,
    val reasoningPhase: String?,
    val reasoningStats: LatencyTracker.Stats,
    val reasoningModelName: String,
    val reasoningBackend: String,
    val reasoningUnavailable: Boolean,
)

/** What the walkthrough can ask the app to do. */
data class WalkthroughActions(
    val onStart: () -> Unit,
    val onStop: () -> Unit,
    val onStartEnrol: (String) -> Unit,
    val onRemoveVoice: (String) -> Unit,
    val onAnalyseNow: () -> Unit,
    val onSwitchToProduct: () -> Unit,
)

/**
 * One tab per on-device signal, with the microphone controls pinned above so
 * the presenter can start and stop from any page without hunting.
 */
@Composable
fun WalkthroughScreen(
    state: WalkthroughState,
    actions: WalkthroughActions,
    micGranted: Boolean,
    onRequestPermissions: () -> Unit,
    /** Called with the page on screen, so only its stages spend compute. */
    onFocus: (SignalTab) -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(SignalTab.OVERVIEW.ordinal) }
    LaunchedEffect(tab) { onFocus(SignalTab.entries[tab]) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (micGranted) {
                Button(onClick = actions.onStart, enabled = !state.capture.running) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Start")
                }
                OutlinedButton(onClick = actions.onStop, enabled = state.capture.running) {
                    Icon(Icons.Default.Stop, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Stop")
                }
            } else {
                Button(onClick = onRequestPermissions) { Text("Grant microphone") }
            }
            Spacer(Modifier.width(4.dp))
            Text(
                if (state.capture.running) "Listening" else "Stopped",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            SignalTab.entries.forEach { entry ->
                Tab(
                    selected = tab == entry.ordinal,
                    onClick = { tab = entry.ordinal },
                    text = { Text(entry.label, maxLines = 1) },
                )
            }
        }

        when (SignalTab.entries[tab]) {
            SignalTab.OVERVIEW -> OverviewPage(
                capture = state.capture,
                calibration = state.calibration,
                onSwitchToProduct = actions.onSwitchToProduct,
                onOpen = { tab = it.ordinal },
            )

            SignalTab.SOUNDS -> SoundEventsPage(
                frame = state.frame,
                verdict = state.verdict,
                history = state.history,
                inference = state.latency.inference,
                endToEnd = state.latency.endToEnd,
            )

            SignalTab.THREAT -> ThreatLanguagePage(
                transcript = state.transcript,
                latestThreat = state.latestThreat,
                history = state.history,
                config = state.config,
            )

            SignalTab.VOICE -> VoiceActivityPage(
                speaking = state.speaking,
                level = state.level,
                room = state.room,
                inputGain = state.inputGain,
                verdict = state.verdict,
                history = state.history,
                capture = state.capture,
            )

            SignalTab.TRANSCRIPT -> TranscriptionPage(
                transcript = state.transcript,
                speaking = state.speaking,
                speechError = state.speechError,
                asrStats = state.asrStats,
                altAsrStats = state.altAsrStats,
                detectedLanguage = state.detectedLanguage,
                secondPassActive = state.secondPassActive,
            )

            SignalTab.SPEAKERS -> SpeakersPage(
                enrolled = state.enrolled,
                lastMatch = state.lastMatch,
                speakerReady = state.speakerReady,
                speakerError = state.speakerError,
                enrolling = state.enrolling,
                enrolSecondsRemaining = state.enrolSecondsRemaining,
                diarization = state.diarization,
                voiceIdLatency = state.latency.voiceId,
                diarizationLatency = state.latency.diarization,
                onStartEnrol = actions.onStartEnrol,
                onRemoveVoice = actions.onRemoveVoice,
            )

            SignalTab.STRESS -> VocalStressPage(
                voice = state.voice,
                verdict = state.verdict,
                history = state.history,
                config = state.config,
            )

            SignalTab.MEDIA -> MediaPlaybackPage(
                frame = state.frame,
                verdict = state.verdict,
                history = state.history,
                config = state.config,
            )

            SignalTab.REASONING -> ReasoningPage(
                reasoning = state.reasoning,
                strideSeconds = state.reasoningStrideSeconds,
                error = state.reasoningError,
                busy = state.reasoningBusy,
                phase = state.reasoningPhase,
                stats = state.reasoningStats,
                modelName = state.reasoningModelName,
                backend = state.reasoningBackend,
                unavailable = state.reasoningUnavailable,
                onAnalyseNow = actions.onAnalyseNow,
            )
        }
    }
}
