// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor

import android.app.Application
import org.lsrith.acousticmonitor.data.AcousticMonitorDatabase
import org.lsrith.acousticmonitor.detect.AlertNotifier
import org.lsrith.acousticmonitor.detect.AlertRepository
import org.lsrith.acousticmonitor.detect.SnippetStore
import org.lsrith.acousticmonitor.llm.LlmEngine
import org.lsrith.acousticmonitor.llm.ReasoningLoop
import org.lsrith.acousticmonitor.models.ModelRepository
import org.lsrith.acousticmonitor.monitor.MonitorEngine
import org.lsrith.acousticmonitor.speaker.SpeakerEngine
import org.lsrith.acousticmonitor.speech.SpeechEngine

class AcousticMonitorApplication : Application() {

    /** Model weights are fetched once on first launch and cached thereafter. */
    val models: ModelRepository by lazy { ModelRepository(this) }

    private val database: AcousticMonitorDatabase by lazy { AcousticMonitorDatabase.create(this) }
    private val snippets: SnippetStore by lazy { SnippetStore(this) }
    private val notifier: AlertNotifier by lazy { AlertNotifier(this).also { it.ensureChannel() } }

    val alerts: AlertRepository by lazy {
        AlertRepository(database.alertEvents(), snippets, notifier)
    }

    val choices: org.lsrith.acousticmonitor.models.ChoiceStore by lazy { org.lsrith.acousticmonitor.models.ChoiceStore(this) }

    /** One gate, read by every stage, set by whichever view is showing. */
    val gate: org.lsrith.acousticmonitor.monitor.StageGate by lazy { org.lsrith.acousticmonitor.monitor.StageGate() }

    val speech: SpeechEngine by lazy { SpeechEngine(models, gate) }

    val speaker: SpeakerEngine by lazy { SpeakerEngine(models) }

    val diarization: org.lsrith.acousticmonitor.speaker.DiarizationEngine by lazy {
        org.lsrith.acousticmonitor.speaker.DiarizationEngine(models)
    }

    val llm: LlmEngine by lazy { LlmEngine(this, models, choices) }

    /**
     * Reasoning runs at whatever pace the device sustains, entirely beside the
     * detection path — if the model never loads, nothing else changes.
     */
    val reasoning: ReasoningLoop by lazy { ReasoningLoop(llm, engine.timeline, gate) }

    /** Process-scoped so the UI and the foreground service observe the same engine. */
    val engine: MonitorEngine by lazy { MonitorEngine(this, models, alerts, speech, speaker, diarization, gate) }
}
