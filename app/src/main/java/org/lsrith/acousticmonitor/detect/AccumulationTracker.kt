// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

data class AccumulationState(
    /** 0..1. Rises while concerning input persists, decays toward 0 when it stops. */
    val level: Float,
    val secondsSinceRecovery: Double,
    /** True once [level] crosses [AccumulationTracker.Config.escalateAt] without recovering. */
    val escalated: Boolean,
    val contributingWindows: Int,
)

/**
 * Accumulates sub-threshold concern over time so that a slow, sustained rise
 * can escalate on its own, without any single window ever crossing a line.
 *
 * An evening of quietly worsening distress looks, window by window, like
 * nothing worth reporting. Only the trend across many windows tells the
 * story, so this integrates strength instead of judging each window alone.
 *
 * Not thread-safe: drive from a single consumer.
 */
class AccumulationTracker(private val config: Config = Config()) {

    data class Config(
        /** Per-window rise applied at full input strength. */
        val riseRate: Float = 0.02f,
        /** Per-window decay when input is at zero. Deliberately slower than the rise. */
        val decayRate: Float = 0.008f,
        val escalateAt: Float = 0.75f,
        /** Level below which the situation counts as recovered. */
        val recoveredBelow: Float = 0.15f,
    )

    private var level = 0f
    private var secondsSinceRecovery = 0.0
    private var escalated = false
    private var contributingWindows = 0

    fun reset() {
        level = 0f
        secondsSinceRecovery = 0.0
        escalated = false
        contributingWindows = 0
    }

    /** @param strength 0..1 concern for this window; 0 means nothing of note. */
    fun accept(strength: Float, secondsElapsed: Double = 0.48): AccumulationState {
        val s = strength.coerceIn(0f, 1f)

        // Decay is slower than rise, so a burst that fades before the next
        // one arrives is not fully forgotten — repeated sub-threshold bursts
        // accumulate instead of cancelling each other out.
        level = (level + config.riseRate * s - config.decayRate * (1f - s)).coerceIn(0f, 1f)

        if (level < config.recoveredBelow) {
            secondsSinceRecovery = 0.0
            contributingWindows = 0
            escalated = false
        } else {
            secondsSinceRecovery += secondsElapsed
            if (s > 0f) contributingWindows++
        }

        if (!escalated && level >= config.escalateAt) escalated = true

        return AccumulationState(
            level = level,
            secondsSinceRecovery = secondsSinceRecovery,
            escalated = escalated,
            contributingWindows = contributingWindows,
        )
    }
}
