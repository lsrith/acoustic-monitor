// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.llm

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.lsrith.acousticmonitor.detect.EvidenceTimeline

/** The reasoning layer's read on one window of evidence. */
data class ReasonedWindow(
    val atEpochMs: Long,
    val concerning: Boolean,
    val reason: String,
    val windowSeconds: Double,
    val generateMs: Double,
    val evidence: String,
    /**
     * The model that produced this verdict.
     *
     * Carried on the result rather than read from the current selection: the
     * two diverge the moment someone switches models, and a panel that named
     * the selection while displaying an older model's judgement was doing
     * exactly that — Live said one model, Diagnostics said another.
     */
    val modelName: String,
)

/**
 * Runs the language model over a sliding window of evidence, at whatever pace
 * the device can sustain.
 *
 * Latency stops being a gate and becomes a resolution setting. If a judgement
 * takes three seconds there is a verdict every three seconds; if it takes
 * sixty, one every sixty. The architecture is identical either way — only the
 * granularity changes — which is why this does not need the device's speed
 * known in advance. It measures itself and settles.
 *
 * Window is 1.5× the stride, so consecutive windows overlap and an event
 * falling near a boundary is seen whole by at least one of them.
 *
 * **Never blocks detection.** This runs on its own coroutine; the acoustic
 * paths neither wait for it nor notice if it fails. If the model never loads,
 * the system behaves exactly as it did before this existed.
 */
class ReasoningLoop(
    private val llm: LlmEngine,
    private val timeline: EvidenceTimeline,
    private val gate: org.lsrith.acousticmonitor.monitor.StageGate,
) {

    private var job: Job? = null

    /** Watches the gate for as long as monitoring runs; owns [job]. */
    private var gateJob: Job? = null

    /**
     * Owned by this class, so teardown does not depend on a caller's lifetime.
     *
     * Releasing the engine on the service's `lifecycleScope` was wrong twice
     * over: that scope is `Main.immediate`, so an uncontended close ran the
     * multi-gigabyte native release on the UI thread — and when a generation
     * was in flight, the close suspended on the lock and was then cancelled by
     * `super.onDestroy()` one statement later, leaking the engine for the life
     * of the process. Which is precisely the case it was added to handle.
     */
    private val teardown = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _latest = MutableStateFlow<ReasonedWindow?>(null)
    val latest: StateFlow<ReasonedWindow?> = _latest.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Measured generation time, which sets the cadence. */
    private val _strideSeconds = MutableStateFlow(INITIAL_STRIDE_SECONDS)
    val strideSeconds: StateFlow<Double> = _strideSeconds.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Timing from real runs over real evidence.
     *
     * There was a synthetic benchmark here with a hardcoded prompt. It measured
     * a sentence nobody had said, over a window that did not exist, and the
     * number it produced was not the number that matters. Every figure below
     * now comes from analysing whatever the detectors actually found.
     */
    private val generateLatency = org.lsrith.acousticmonitor.metrics.LatencyTracker(64)

    private val _stats = MutableStateFlow(org.lsrith.acousticmonitor.metrics.LatencyTracker.Stats())
    val stats: StateFlow<org.lsrith.acousticmonitor.metrics.LatencyTracker.Stats> = _stats.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * What the reasoning layer is doing right now, in words.
     *
     * A boolean was not enough. Loading the model takes about 21 s and judging
     * takes the best part of a minute, and busy was only set once the load had
     * finished — so pressing Analyse now produced twenty seconds of nothing at
     * all, which is indistinguishable from a dead button.
     */
    private val _phase = MutableStateFlow<String?>(null)
    val phase: StateFlow<String?> = _phase.asStateFlow()

    /**
     * Runs one analysis now, over the current evidence window.
     *
     * Ad hoc rather than waiting for the interval, so the model can be exercised
     * on demand — but on the same real timeline the loop would have used, not a
     * stand-in.
     */
    suspend fun analyseNow(): ReasonedWindow? {
        val window = _strideSeconds.value * WINDOW_TO_STRIDE
        val now = System.currentTimeMillis()

        // Checked before the model is opened, not after. Loading a reasoning
        // model takes tens of seconds and gigabytes; paying that to then say
        // "nothing to judge" is the wrong order.

        // The same refusal the interval loop makes. Judging an empty window
        // produced an "ordinary" verdict on the Live screen derived from no
        // evidence at all — a signal with nothing behind it — and, being the
        // shortest prompt the device will ever see, it also dragged down the
        // very latency figures this button exists to measure.
        if (timeline.isEmpty(now, window)) {
            val why = "Nothing detected in the last ${window.toInt()} s — nothing to judge. " +
                "Make a noise or speak, then try again."
            Log.i(TAG, why)
            _error.value = why
            return null
        }

        _busy.value = true
        try {
            // Covers the load, which is the slow part and was previously
            // invisible.
            _phase.value = "Loading ${llm.model.value.displayName}…"
            if (!llm.open()) {
                _error.value = llm.error.value ?: "Model did not load"
                return null
            }

            val evidence = timeline.render(now, window)
            _phase.value = "Judging ${window.toInt()} s of evidence…"
            val verdict = llm.reason(evidence) ?: return null
            record(verdict, now, window, evidence)
            return _latest.value
        } finally {
            _busy.value = false
            _phase.value = null
        }
    }

    private fun record(verdict: ReasonedWindow, atEpochMs: Long, windowSeconds: Double, evidence: String) {
        // A run that worked clears whatever the last failure said, or the card
        // shows a stale red line above a fresh verdict until the app restarts.
        _error.value = null
        generateLatency.record(verdict.generateMs)
        _stats.value = generateLatency.snapshot()
        _strideSeconds.value = (verdict.generateMs / 1000.0)
            .coerceIn(MIN_STRIDE_SECONDS, MAX_STRIDE_SECONDS)
        _latest.value = verdict.copy(
            atEpochMs = atEpochMs,
            windowSeconds = windowSeconds,
            evidence = evidence,
        )
    }

    /**
     * Runs while monitoring runs, but only generates while the gate allows.
     *
     * When the walkthrough moves off the reasoning page the loop stops and
     * the engine is released, the same as at Stop: a mapped multi-gigabyte
     * model is the wrong thing to keep resident behind a page that is
     * demonstrating something else. Coming back reloads it, at the same cost
     * as the first load — that cost is shown on the page rather than hidden.
     */
    fun start(scope: CoroutineScope) {
        if (gateJob != null) return
        gateJob = scope.launch {
            gate.stages
                .map { it.reasoning }
                .distinctUntilChanged()
                .collect { allowed ->
                    if (allowed) launchLoop(scope) else pause()
                }
        }
    }

    private fun launchLoop(scope: CoroutineScope) {
        if (job != null) return
        job = scope.launch {
            _busy.value = true
            _phase.value = "Loading ${llm.model.value.displayName}…"
            val opened = try { llm.open() } finally { _busy.value = false; _phase.value = null }
            if (!opened) {
                _error.value = llm.error.value ?: "Reasoning unavailable: model did not load"
                Log.w(TAG, "reasoning loop not starting — model unavailable")
                return@launch
            }
            _error.value = null
            _running.value = true

            while (isActive) {
                val stride = _strideSeconds.value
                val window = stride * WINDOW_TO_STRIDE
                val now = System.currentTimeMillis()

                // An empty window is not worth a generation. Any evidence at
                // all qualifies — including speech with no acoustic event,
                // which is exactly the quiet-coercion case that most needs
                // reasoning and would be lost to a louder threshold.
                if (timeline.isEmpty(now, window)) {
                    delay((stride * 1000).toLong())
                    continue
                }

                val evidence = timeline.render(now, window)
                // Shared with the on-demand path so the button is disabled
                // while the interval run holds the engine. Without this the
                // button looked available during a generation that could last
                // two minutes, and the tap simply queued behind it.
                _busy.value = true
                _phase.value = "Judging ${window.toInt()} s of evidence…"
                val verdict = try {
                    llm.reason(evidence)
                } finally {
                    _busy.value = false
                    _phase.value = null
                }

                if (verdict != null) {
                    // The cadence follows what the device actually managed, so
                    // a slow run widens the next window rather than letting the
                    // loop fall behind and reason about stale audio.
                    record(verdict, now, window, evidence)
                } else {
                    delay((stride * 1000).toLong())
                }
            }
        }
    }

    /** Clears measured timing, so a reset in Diagnostics means what it says. */
    fun resetStats() {
        generateLatency.reset()
        _stats.value = org.lsrith.acousticmonitor.metrics.LatencyTracker.Stats()
        _strideSeconds.value = INITIAL_STRIDE_SECONDS
    }

    fun stop() {
        gateJob?.cancel()
        gateJob = null
        pause()
    }

    private fun pause() {
        job?.cancel()
        job = null
        _running.value = false
        // Release the engine with the loop that needed it. A reasoning model
        // is the largest thing this app holds — gigabytes for the bigger ones
        // — and keeping it mapped after monitoring stops is the surest way to
        // be the process the OEM's memory manager picks off.
        teardown.launch { llm.close() }
    }

    private companion object {
        const val TAG = "ReasoningLoop"

        /** Before anything has been measured. Replaced after the first run. */
        const val INITIAL_STRIDE_SECONDS = 20.0

        /** Windows overlap by half a stride. */
        const val WINDOW_TO_STRIDE = 1.5

        const val MIN_STRIDE_SECONDS = 3.0

        /**
         * Beyond this the verdict is too stale to mean anything, and the loop
         * is better off reasoning about recent evidence than finishing an old
         * window.
         */
        const val MAX_STRIDE_SECONDS = 120.0
    }
}
