// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.monitor

import kotlin.math.abs
import kotlin.math.exp

/**
 * What this room normally sounds like, learned continuously.
 *
 * One profile, not two. The level baseline used to be learned twice — once for
 * the loudness path and once for microphone integrity — from the same window
 * RMS, with different adaptation rates. Two estimates of one quantity drift
 * apart, and neither is more correct than the other.
 *
 * Learning is continuous from the first window rather than gated behind a
 * fixed warm-up. A hard gate makes the system behave as though it knows
 * nothing at 4:59 and everything at 5:01, which is not how an estimate
 * actually improves. Instead the estimate is always available and carries a
 * [RoomReading.confidence] that rises with observation; consumers scale their
 * use of it accordingly.
 *
 * Calibration does not gate this either — it re-anchors it. Discarding the
 * estimate and restarting the ramp is how someone says "conditions are
 * representative now, learn from here".
 */
class RoomProfile(private val config: Config = Config()) {

    data class Config(
        /**
         * Time constant for the confidence ramp, in seconds.
         *
         * Confidence is 1 - exp(-t/τ): about 63% at one τ, 86% at two, 95% at
         * three. At 180 s that reaches useful confidence within a few minutes
         * while still climbing for a quarter of an hour.
         */
        val confidenceTauSeconds: Double = 180.0,

        /** Adaptation while the room looks normal. Slow: this is the norm, not the moment. */
        val adaptRate: Float = 0.003f,

        /**
         * Faster adaptation early on, decaying toward [adaptRate].
         *
         * A brand-new estimate should move quickly toward reality; a
         * well-established one should not be shifted by a single evening.
         */
        val earlyAdaptRate: Float = 0.05f,

        /** dB above baseline treated as maximum deviation. */
        val saturationDb: Float = 24f,

        /**
         * Deviation beyond this fraction of saturation freezes adaptation, so a
         * loud incident is not absorbed into the baseline meant to detect it.
         */
        val freezeAboveFraction: Float = 0.5f,
    )

    private var levelDbfs = 0f
    private var trebleShare = 0f
    private var observedSeconds = 0.0
    private var seenAnything = false

    /** Seconds of observation since the last deliberate re-anchor. */
    val ageSeconds: Double get() = observedSeconds

    fun reset() {
        levelDbfs = 0f
        trebleShare = 0f
        observedSeconds = 0.0
        seenAnything = false
    }

    /** Adopts a profile learned in an earlier session, with its accumulated age. */
    fun restore(levelDbfs: Float, trebleShare: Float, ageSeconds: Double) {
        this.levelDbfs = levelDbfs
        this.trebleShare = trebleShare
        this.observedSeconds = ageSeconds
        this.seenAnything = true
    }

    fun snapshot(): Saved = Saved(levelDbfs, trebleShare, observedSeconds)

    data class Saved(val levelDbfs: Float, val trebleShare: Float, val ageSeconds: Double)

    /**
     * @param currentDbfs window RMS in dBFS.
     * @param currentTreble share of energy above ~1 kHz, 0..1.
     */
    fun accept(currentDbfs: Float, currentTreble: Float, secondsElapsed: Double): RoomReading {
        if (!seenAnything) {
            // Seed from the first observation rather than from zero, so the
            // estimate starts somewhere plausible instead of climbing out of a
            // value no room has ever had.
            levelDbfs = currentDbfs
            trebleShare = currentTreble
            seenAnything = true
        }

        observedSeconds += secondsElapsed
        val confidence = (1.0 - exp(-observedSeconds / config.confidenceTauSeconds))
            .toFloat().coerceIn(0f, 1f)

        val deviationDb = currentDbfs - levelDbfs
        val deviationScore = (deviationDb / config.saturationDb).coerceIn(0f, 1f)

        // Blend from fast early adaptation to slow settled adaptation as
        // confidence rises.
        val rate = config.earlyAdaptRate +
            (config.adaptRate - config.earlyAdaptRate) * confidence

        val frozen = deviationDb > config.saturationDb * config.freezeAboveFraction
        if (!frozen) {
            levelDbfs += rate * (currentDbfs - levelDbfs)
            trebleShare += rate * (currentTreble - trebleShare)
        }

        return RoomReading(
            currentDbfs = currentDbfs,
            baselineDbfs = levelDbfs,
            deviationDb = deviationDb,
            deviationScore = deviationScore,
            currentTreble = currentTreble,
            baselineTreble = trebleShare,
            trebleRatio = if (trebleShare <= 1e-6f) 1f else currentTreble / trebleShare,
            confidence = confidence,
            ageSeconds = observedSeconds,
            adapting = !frozen,
        )
    }
}

/**
 * The room's normal and how far the present moment sits from it.
 *
 * [confidence] is the important field: it is never "not ready", only more or
 * less trusted, and consumers are expected to scale their use of the deviation
 * by it rather than waiting for a threshold.
 */
data class RoomReading(
    val currentDbfs: Float = -96f,
    val baselineDbfs: Float = -96f,
    val deviationDb: Float = 0f,
    val deviationScore: Float = 0f,
    val currentTreble: Float = 0f,
    val baselineTreble: Float = 0f,
    /** Current treble share as a fraction of the room's normal. Falls when covered. */
    val trebleRatio: Float = 1f,
    val confidence: Float = 0f,
    val ageSeconds: Double = 0.0,
    /** False while a loud event is holding adaptation still. */
    val adapting: Boolean = true,
) {
    /** Deviation weighted by how much the baseline can be trusted. */
    val weightedDeviation: Float get() = deviationScore * confidence

    fun confidencePercent(): Int = (confidence * 100).toInt().coerceIn(0, 100)

    companion object {
        /**
         * Below this, occlusion is not reported.
         *
         * A false tamper alert is expensive — it accuses someone — so unlike
         * the loudness path, which degrades gracefully, this one waits until
         * the room is genuinely known. Around four minutes at the default tau.
         */
        const val MIN_CONFIDENCE_FOR_OCCLUSION = 0.75f
    }
}
