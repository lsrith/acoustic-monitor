// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.speech

import kotlin.math.abs

/**
 * Continuous gain for the speech path, applied before voice detection.
 *
 * The microphone is opened UNPROCESSED so automatic gain control cannot flatten
 * the loudness cues the acoustic path depends on. That leaves speech from
 * across a room arriving very quietly — quietly enough that the voice detector
 * never fires, so no segment is produced and nothing reaches the recogniser at
 * all. Normalising only the recogniser's input, as this originally did, fixes
 * the wrong stage: the gate in front of it is what was starving.
 *
 * A peak follower rather than per-buffer normalisation, because the detector
 * consumes a continuous stream and a gain that jumps between buffers would
 * look like amplitude modulation. Fast to rise so the start of an utterance is
 * not lost, slow to fall so a pause does not pump the noise floor up.
 *
 * The raw ring buffer the acoustic path reads is never touched by this.
 */
class AutoGain(
    /** Leaves headroom rather than driving to full scale, which clips consonants. */
    private val targetPeak: Float = 0.5f,
    private val maxGain: Float = 40f,
    /** Envelope rise per buffer when the signal is louder than the estimate. */
    private val attack: Float = 0.4f,
    /** Envelope fall per buffer when it is quieter. Deliberately slower. */
    private val release: Float = 0.02f,
    /** Below this the buffer is treated as silence and left alone. */
    private val noiseFloor: Float = 0.0005f,
) {

    private var envelope = 0f

    /** Gain applied to the most recent buffer. Reported for diagnosis. */
    var gain: Float = 1f
        private set

    /** Pre-gain peak of the most recent buffer, in linear amplitude. */
    var lastRawPeak: Float = 0f
        private set

    fun reset() {
        envelope = 0f
        gain = 1f
        lastRawPeak = 0f
    }

    /** Scales [buffer] in place. */
    fun process(buffer: FloatArray) {
        var peak = 0f
        for (s in buffer) {
            val a = abs(s)
            if (a > peak) peak = a
        }
        lastRawPeak = peak

        val rate = if (peak > envelope) attack else release
        envelope += rate * (peak - envelope)

        if (envelope < noiseFloor) {
            gain = 1f
            return
        }

        gain = (targetPeak / envelope).coerceIn(1f, maxGain)
        if (gain <= 1.01f) return

        for (i in buffer.indices) {
            buffer[i] = (buffer[i] * gain).coerceIn(-1f, 1f)
        }
    }
}
