// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

/**
 * Every tunable in the decision layer, in one place.
 *
 * These values are guesses until they are calibrated against real rooms, which
 * is the point of the Calibration screen. Nothing here is derived from data
 * yet; treat the defaults as a starting position, not a recommendation.
 */
data class DecisionConfig(
    /** Weighted cue score at which an event is worth recording. */
    val mediumThreshold: Float = 0.35f,

    /** Weighted cue score treated as high severity. */
    val highThreshold: Float = 0.60f,

    /**
     * A cue must clear threshold in [persistenceK] of the last [persistenceM]
     * windows. Single-window spikes are overwhelmingly false positives — a door
     * slam or a laugh — so requiring persistence is the cheapest accuracy win
     * available.
     */
    val persistenceK: Int = 2,
    val persistenceM: Int = 4,

    /** Context adjusts acoustic thresholds; it cannot suppress them without limit. */
    val mediaPenalty: Float = 0.5f,
    val maxMediaFactor: Float = 1.6f,

    /** Cap relative to the maximum baseline-deviation contribution. */
    val demotionCeilingFraction: Float = 0.95f,

    /**
     * How hard laughter raises the bar. Capped together with media by
     * [maxMediaFactor], for the same reason: a household can laugh during a
     * real incident, so this must demote and never silence.
     */
    val laughterPenalty: Float = 0.4f,

    /**
     * Weight on the loudness-deviation path.
     *
     * Deliberately below 1.0 — being much louder than the room's normal is real
     * evidence but weak on its own, since a vacuum cleaner clears it easily.
     * It matters most when it accumulates or corroborates a cue.
     */
    val ambientWeight: Float = 0.55f,

    /**
     * Weight on the threat-language path.
     *
     * Full weight, because this is the path that carries the quiet cases. A
     * calm room with explicit threats in it is not a low-severity situation,
     * and a loudness-driven system structurally cannot see it.
     */
    val threatWeight: Float = 1.0f,

    /**
     * Seconds after which a threat reading has decayed to about a third.
     *
     * Language is evidence while it is recent and context afterwards; latching
     * it would keep an alert live long after the moment passed.
     */
    val threatDecaySeconds: Double = 90.0,

    /** Vocal strain amplifies existing acoustic cues; it cannot trigger alone. */
    val stressBoost: Float = 0.35f,

    /** Consecutive non-firing windows before an open event is considered over. */
    val quietWindowsToClose: Int = 6,

    /** Per-group confidence multipliers. A scream matters more than a thud. */
    val weights: Map<CueGroup, Float> = mapOf(
        CueGroup.SCREAM to 1.00f,
        CueGroup.GUNSHOT to 1.00f,
        CueGroup.CRY to 0.90f,
        CueGroup.SHOUT to 0.85f,
        CueGroup.GLASS to 0.80f,
        CueGroup.IMPACT to 0.70f,
    ),
) {
    fun weightOf(group: CueGroup): Float = weights[group] ?: 1f
}
