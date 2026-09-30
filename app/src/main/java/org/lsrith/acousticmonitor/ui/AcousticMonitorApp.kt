// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewCarousel
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.lsrith.acousticmonitor.R
import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.detect.AlertRepository
import org.lsrith.acousticmonitor.detect.EventEvidence
import org.lsrith.acousticmonitor.detect.LabelExporter
import org.lsrith.acousticmonitor.detect.SnippetPlayer
import org.lsrith.acousticmonitor.detect.ThreatLexicon
import org.lsrith.acousticmonitor.llm.LlmEngine
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.models.ModelRepository
import org.lsrith.acousticmonitor.monitor.MonitorEngine
import org.lsrith.acousticmonitor.monitor.StageGate
import org.lsrith.acousticmonitor.service.MonitorService
import org.lsrith.acousticmonitor.speaker.SpeakerEngine
import org.lsrith.acousticmonitor.ui.walkthrough.DemoMode
import org.lsrith.acousticmonitor.ui.walkthrough.WalkthroughActions
import org.lsrith.acousticmonitor.ui.walkthrough.WalkthroughScreen
import org.lsrith.acousticmonitor.ui.walkthrough.WalkthroughState

private val DarkColors = darkColorScheme(
    primary = Color(0xFF7FD1FF),
    secondary = Color(0xFF9CCC65),
    error = Color(0xFFFF6E6E),
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0B5C86),
    secondary = Color(0xFF3E7A1E),
    error = Color(0xFFB3261E),
)

/** Seconds of audio taken from the ring buffer to build a voiceprint. */
/** Seconds of actual speech an enrolment needs, not seconds of wall clock. */
private const val ENROL_SPEECH_SECONDS = 8.0

/** Give up if that much speech never arrives. */
private const val ENROL_TIMEOUT_MS = 60_000L

private const val TAB_LIVE = 0
private const val TAB_DIAGNOSTICS = 1
private const val TAB_ALERTS = 2
private const val TAB_SETTINGS = 3
private const val TAB_MODELS = 4
private const val TAB_SIGNALS = 5

/**
 * Destinations are organised by purpose rather than by subsystem: what you show
 * someone, what you review afterwards, what you set up once, and what you
 * inspect when something looks wrong.
 */
@Composable
fun AcousticMonitorApp(
    engine: MonitorEngine,
    models: ModelRepository,
    alerts: AlertRepository,
    speaker: SpeakerEngine,
    llm: LlmEngine,
    reasoningLoop: org.lsrith.acousticmonitor.llm.ReasoningLoop,
    diarization: org.lsrith.acousticmonitor.speaker.DiarizationEngine,
    choices: org.lsrith.acousticmonitor.models.ChoiceStore,
    micGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onRetryModels: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(colorScheme = if (dark) DarkColors else LightColors) {
        var demoMode by rememberSaveable {
            mutableStateOf(DemoMode.fromName(choices.loadDemoMode()))
        }
        // Each face has its own home; landing on Live with the walkthrough's
        // bar showing would select nothing.
        var tab by rememberSaveable {
            mutableIntStateOf(if (demoMode == DemoMode.WALKTHROUGH) TAB_SIGNALS else TAB_LIVE)
        }
        // The product view runs everything, as a shipped app would. The
        // walkthrough narrows the gate to the page on screen; that is done
        // from inside it, since it knows which page that is.
        LaunchedEffect(demoMode) {
            if (demoMode == DemoMode.PRODUCT) engine.gate.set(StageGate.Stages.ALL)
        }
        val productDestinations = listOf(
            Triple(TAB_LIVE, Icons.Default.GraphicEq, stringResource(R.string.tab_live)),
            Triple(TAB_DIAGNOSTICS, Icons.Default.Tune, stringResource(R.string.tab_diagnostics)),
            Triple(TAB_ALERTS, Icons.Default.NotificationsActive, stringResource(R.string.tab_alerts)),
            Triple(TAB_SETTINGS, Icons.Default.Settings, stringResource(R.string.tab_settings)),
            Triple(TAB_MODELS, Icons.Default.CloudDownload, stringResource(R.string.tab_models)),
        )
        // The walkthrough keeps only what a presenter needs beside the
        // signals: Setup for enrolment and the model choice, Models to fetch
        // weights. Alerts and Diagnostics belong to the product story.
        val walkthroughDestinations = listOf(
            Triple(TAB_SIGNALS, Icons.Default.ViewCarousel, stringResource(R.string.tab_signals)),
            Triple(TAB_SETTINGS, Icons.Default.Settings, stringResource(R.string.tab_settings)),
            Triple(TAB_MODELS, Icons.Default.CloudDownload, stringResource(R.string.tab_models)),
        )
        val destinations =
            if (demoMode == DemoMode.WALKTHROUGH) walkthroughDestinations else productDestinations

        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        val modelState by models.state.collectAsState()
        val capture by engine.pipeline.state.collectAsState()
        val calibration by engine.calibration.collectAsState()
        val verdict by engine.verdict.collectAsState()
        val frame by engine.frame.collectAsState()
        val level by engine.level.collectAsState()
        val integrity by engine.micIntegrity.collectAsState()
        val voice by engine.prosodyFrame.collectAsState()
        val latency by engine.latency.collectAsState()

        val events by alerts.events.collectAsState(initial = emptyList())
        val transcript by engine.speech.segments.collectAsState()
        val asrStats by engine.speech.asrStats.collectAsState()
        val altAsrStats by engine.speech.altAsrStats.collectAsState()
        val detectedLanguage by engine.speech.detectedLanguage.collectAsState()
        val secondPassActive by engine.speech.secondaryActive.collectAsState()
        val speechError by engine.speech.error.collectAsState()
        val speaking by engine.speech.speaking.collectAsState()
        val latestThreat by engine.speech.latestThreat.collectAsState()
        val inputGain by engine.speech.inputGain.collectAsState()

        val enrolledVoices by speaker.enrolled.collectAsState()
        val lastMatch by speaker.lastMatch.collectAsState()
        val speakerReady by speaker.ready.collectAsState()
        val speakerModelPresent = modelState.itemFor(ModelCatalog.SPEAKER_EMBEDDING)?.status ==
            ModelRepository.Status.READY
        val speakerError by speaker.error.collectAsState()
        val diarizationResult by diarization.lastResult.collectAsState()
        val room by engine.room.collectAsState()

        val reasoning by reasoningLoop.latest.collectAsState()
        val reasoningStride by reasoningLoop.strideSeconds.collectAsState()
        val reasoningError by reasoningLoop.error.collectAsState()
        val history by engine.history.samples.collectAsState()
        val nearMiss by engine.nearMiss.peaks.collectAsState()
        val unavailableLlm by llm.unavailable.collectAsState()
        val reasoningStats by reasoningLoop.stats.collectAsState()
        val reasoningBusy by reasoningLoop.busy.collectAsState()
        val reasoningPhase by reasoningLoop.phase.collectAsState()
        val reasoningGenerations by llm.generationsThisEngine.collectAsState()
        val reasoningNativeKb by llm.nativeHeapKb.collectAsState()

        val selectedLlm by llm.model.collectAsState()
        val llmBackend by llm.backend.collectAsState()

        val briefing by llm.lastBriefing.collectAsState()
        val briefingBusy by llm.busy.collectAsState()

        var enrollSecondsLeft by remember { mutableIntStateOf(0) }
        var exportMessage by remember { mutableStateOf<String?>(null) }


        val exporter = remember(context) { LabelExporter(context) }
        val player = remember { SnippetPlayer() }
        DisposableEffect(Unit) { onDispose { player.stop() } }

        // Shared by Setup and the Speakers page, so both enrol the same way.
        val startEnrol: (String) -> Unit = { name ->
            scope.launch {
                if (!models.ensure(ModelCatalog.SPEAKER_EMBEDDING)) return@launch
                speaker.open()
                // Waits for speech, not for a clock. The old
                // countdown took five seconds of whatever the
                // room held, pauses included.
                engine.beginEnrolment()
                val deadline = System.currentTimeMillis() + ENROL_TIMEOUT_MS
                while (
                    engine.enrolSeconds.value < ENROL_SPEECH_SECONDS &&
                    System.currentTimeMillis() < deadline
                ) {
                    enrollSecondsLeft =
                        (ENROL_SPEECH_SECONDS - engine.enrolSeconds.value)
                            .coerceAtLeast(0.0).toInt() + 1
                    delay(250)
                }
                enrollSecondsLeft = 0
                speaker.enroll(name, engine.finishEnrolment())
            }
        }

        val setDemoMode: (DemoMode) -> Unit = { mode ->
            demoMode = mode
            choices.saveDemoMode(mode.name)
            tab = if (mode == DemoMode.WALKTHROUGH) TAB_SIGNALS else TAB_LIVE
        }

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                if (modelState.ready) {
                    NavigationBar {
                        destinations.forEach { (index, icon, title) ->
                            NavigationBarItem(
                                selected = tab == index,
                                onClick = { tab = index },
                                icon = { Icon(icon, contentDescription = title) },
                                label = { Text(title, maxLines = 1) },
                                alwaysShowLabel = false,
                            )
                        }
                    }
                }
            },
        ) { padding ->
            if (!modelState.ready) {
                Column(Modifier.padding(padding)) {
                    SetupScreen(state = modelState, onRetry = onRetryModels)
                }
                return@Scaffold
            }

            Column(Modifier.padding(padding)) {
                when (tab) {
                    TAB_LIVE -> LiveScreen(
                        running = capture.running,
                        calibration = calibration,
                        verdict = verdict,
                        topClasses = frame?.top.orEmpty(),
                        transcript = transcript,
                        latestAlert = events.firstOrNull(),
                        integrity = integrity,
                        speaking = speaking,
                        speechError = speechError,
                        inputGain = inputGain,
                        asrModelLabel = org.lsrith.acousticmonitor.speech.SpeechEngine.PRIMARY_LABEL,
                        reasoning = reasoning,
                        reasoningStrideSeconds = reasoningStride,
                        reasoningError = reasoningError,
                        history = history,
                        onOpenModels = { tab = TAB_MODELS },
                        micGranted = micGranted,
                        onStart = { MonitorService.start(context) },
                        onStop = { MonitorService.stop(context) },
                        onRequestPermissions = onRequestPermissions,
                        // Hands off to the Alerts destination rather than
                        // duplicating the detail view on two screens.
                        onOpenAlert = { tab = TAB_ALERTS },
                    )

                    TAB_ALERTS -> AlertsScreen(
                        llmModelName = selectedLlm.displayName,
                        events = events,
                        onSetReview = { id, label -> scope.launch { alerts.setReview(id, label) } },
                        onPlaySnippet = { player.play(it) },
                        onClearAll = { scope.launch { alerts.clearAll(events) } },
                        onExport = {
                            val file = exporter.export(events, ThreatLexicon.VERSION)
                            exportMessage = file?.let { "Wrote ${it.name}" }
                                ?: "Nothing reviewed yet to export"
                        },
                        exportMessage = exportMessage,
                        onBrief = { ev ->
                            scope.launch {
                                if (llm.open()) {
                                    llm.brief(EventEvidence.promptFacts(ev))
                                }
                            }
                        },
                        briefing = briefing,
                        briefingBusy = briefingBusy,
                    )

                    TAB_MODELS -> ModelsScreen(
                        modelState = modelState,
                        onDownload = { spec -> scope.launch { models.ensure(spec) } },
                    )

                    TAB_SETTINGS -> SettingsScreen(
                        demoMode = demoMode,
                        onSelectDemoMode = setDemoMode,
                        detectedLanguage = detectedLanguage,
                        secondPassActive = secondPassActive,
                        onOpenModels = { tab = TAB_MODELS },
                        calibration = calibration,
                        running = capture.running,
                        onRelearn = { engine.calibrate() },
                        asrError = speechError,
                        enrolled = enrolledVoices,
                        lastMatch = lastMatch,
                        // Availability, not openness: gating on "engine opened"
                        // deadlocked this, since the engine only opened during
                        // enrolment and enrolment was disabled until it had.
                        speakerReady = speakerModelPresent,
                        speakerError = speakerError,
                        enrolling = enrollSecondsLeft > 0,
                        enrollSecondsRemaining = enrollSecondsLeft,
                        onStartEnroll = startEnrol,
                        onRemoveVoice = { speaker.remove(it) },
                        onClearVoices = { speaker.clearAll() },
                        captureState = capture,
                        level = level,
                        selectedLlm = selectedLlm,
                        onSelectLlm = { spec ->
                            scope.launch {
                                llm.selectModel(spec)
                                models.ensure(spec)
                            }
                        },
                        unavailableLlm = unavailableLlm,
                        llmBackend = llmBackend,
                        modelState = modelState,
                        onDownloadModel = { spec -> scope.launch { models.ensure(spec) } },
                    )

                    TAB_SIGNALS -> WalkthroughScreen(
                        state = WalkthroughState(
                            capture = capture,
                            calibration = calibration,
                            level = level,
                            room = room,
                            frame = frame,
                            verdict = verdict,
                            history = history,
                            latency = latency,
                            config = engine.config,
                            transcript = transcript,
                            latestThreat = latestThreat,
                            speaking = speaking,
                            inputGain = inputGain,
                            speechError = speechError,
                            asrStats = asrStats,
                            altAsrStats = altAsrStats,
                            detectedLanguage = detectedLanguage,
                            secondPassActive = secondPassActive,
                            enrolled = enrolledVoices,
                            lastMatch = lastMatch,
                            speakerReady = speakerModelPresent,
                            speakerError = speakerError,
                            enrolling = enrollSecondsLeft > 0,
                            enrolSecondsRemaining = enrollSecondsLeft,
                            diarization = diarizationResult,
                            voice = voice,
                            reasoning = reasoning,
                            reasoningStrideSeconds = reasoningStride,
                            reasoningError = reasoningError,
                            reasoningBusy = reasoningBusy,
                            reasoningPhase = reasoningPhase,
                            reasoningStats = reasoningStats,
                            reasoningModelName = selectedLlm.displayName,
                            reasoningBackend = llmBackend,
                            reasoningUnavailable = selectedLlm.id in unavailableLlm,
                        ),
                        actions = WalkthroughActions(
                            onStart = { MonitorService.start(context) },
                            onStop = { MonitorService.stop(context) },
                            onStartEnrol = startEnrol,
                            onRemoveVoice = { speaker.remove(it) },
                            onAnalyseNow = { scope.launch { reasoningLoop.analyseNow() } },
                            onSwitchToProduct = { setDemoMode(DemoMode.PRODUCT) },
                        ),
                        micGranted = micGranted,
                        onRequestPermissions = onRequestPermissions,
                        onFocus = { page -> engine.gate.set(page.stages) },
                    )

                    TAB_DIAGNOSTICS -> DiagnosticsScreen(
                        latency = latency,
                        onResetMetrics = {
                            engine.resetMetrics()
                            reasoningLoop.resetStats()
                        },
                        asrStats = asrStats,
                        altAsrStats = altAsrStats,
                        transcript = transcript,
                        config = engine.config,
                        unmatchedLabels = engine.unmatchedLabels,
                        voice = voice,
                        integrity = integrity,
                        history = history,
                        nearMiss = nearMiss,
                        reasoningStats = reasoningStats,
                        reasoningStride = reasoningStride,
                        reasoningBackend = llmBackend,
                        reasoningModel = selectedLlm.displayName,
                        reasoningLatest = reasoning,
                        reasoningBusy = reasoningBusy,
                        reasoningPhase = reasoningPhase,
                        reasoningGenerations = reasoningGenerations,
                        reasoningNativeKb = reasoningNativeKb,
                        reasoningError = reasoningError,
                        onAnalyseNow = { scope.launch { reasoningLoop.analyseNow() } },
                    )
                    // Unreachable; a destination index that is not a
                    // known tab falls back to the demonstration screen rather
                    // than to whichever branch happened to be last.
                    else -> Unit
                }
            }
        }
    }
}
