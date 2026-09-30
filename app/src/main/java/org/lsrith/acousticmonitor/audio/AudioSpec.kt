// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.audio

/**
 * Fixed audio geometry for the fast path.
 *
 * These numbers are not arbitrary: YAMNet's TFLite graph consumes exactly
 * [WINDOW_SAMPLES] samples of 16 kHz mono float PCM, and the 0.48 s hop gives
 * two overlapping decisions per window.
 */
object AudioSpec {
    const val SAMPLE_RATE = 16_000

    /** 0.975 s — YAMNet's native input length. */
    const val WINDOW_SAMPLES = 15_600

    /** 0.48 s — one hop, so windows overlap by ~50%. */
    const val HOP_SAMPLES = 7_680

    /** Size of one AudioRecord read. Small enough to keep the UI responsive. */
    const val READ_SAMPLES = 1_600

    /** Rolling history retained in memory, for writing a snippet once a trigger fires. */
    const val RING_SECONDS = 60
    const val RING_SAMPLES = SAMPLE_RATE * RING_SECONDS

    val windowSeconds: Double get() = WINDOW_SAMPLES.toDouble() / SAMPLE_RATE
    val hopSeconds: Double get() = HOP_SAMPLES.toDouble() / SAMPLE_RATE
}
