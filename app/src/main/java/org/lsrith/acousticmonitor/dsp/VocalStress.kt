// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.dsp

/**
 * Collapses the prosody measures into one 0..1 strain figure.
 *
 * Jitter, shimmer and loss of periodicity all rise together when a voice is
 * under stress — shouted, strained or breaking. Combining them is more robust
 * than trusting any one, since each is individually noisy at a distance and in
 * a reverberant room.
 *
 * **The normalising constants below are guesses.** They set what counts as "a
 * lot" of jitter or shimmer, and they have not been calibrated against real
 * voices in real rooms. They are the first thing to revisit once there is
 * recorded material to check them against.
 */
object VocalStress {

    /** Jitter at or above this reads as maximum strain. */
    private const val JITTER_FULL = 0.04f

    /** Shimmer at or above this reads as maximum strain. */
    private const val SHIMMER_FULL = 0.15f

    private const val W_JITTER = 0.40f
    private const val W_SHIMMER = 0.30f
    private const val W_ROUGHNESS = 0.30f

    /**
     * @return 0..1 strain, or 0 for unvoiced audio — these measures are only
     *   defined over voiced speech, and reporting a number for a door slam
     *   would be inventing one.
     */
    fun of(frame: ProsodyFrame): Float {
        if (!frame.voiced) return 0f
        val jitter = (frame.jitter / JITTER_FULL).coerceIn(0f, 1f)
        val shimmer = (frame.shimmer / SHIMMER_FULL).coerceIn(0f, 1f)
        val roughness = (1f - frame.harmonicity).coerceIn(0f, 1f)
        return (W_JITTER * jitter + W_SHIMMER * shimmer + W_ROUGHNESS * roughness)
            .coerceIn(0f, 1f)
    }
}
