// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.dsp

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt


data class ProsodyFrame(
    val voiced: Boolean,
    val f0Hz: Float,
    val jitter: Float,
    val shimmer: Float,
    val harmonicity: Float,
    val vocalEffortDb: Float,
    val spectralTilt: Float,
)

/**
 * Extracts vocal-stress prosody from short frames of mono float PCM.
 *
 * Under strain, a voice does not just get louder — it gets less *stable*.
 * The glottis loses fine control of both timing and amplitude, so pitch
 * period and peak amplitude both start to wobble frame to frame even when
 * the speaker is holding a steady note. Jitter and shimmer capture that
 * wobble directly, and rise well before loudness does. Spectral tilt adds a
 * third, independent cue: shouting recruits more high-frequency energy than
 * raising volume alone would predict, so a rising treble share is itself a
 * strain signal, not just a loudness one.
 *
 * All of this comes from a single autocorrelation-based pitch estimate per
 * frame, plus rolling history of pitch and amplitude — no FFT, no model.
 * Not thread-safe: drive from a single consumer, one frame at a time.
 */
class ProsodyAnalyzer(private val config: Config = Config()) {

    data class Config(
        val minF0Hz: Float = 70f,
        val maxF0Hz: Float = 450f,
        /** Min normalised autocorrelation peak to call a frame voiced. */
        val voicingThreshold: Float = 0.35f,
    )

    /** One-pole low-pass state for the ~1 kHz spectral split, carried across calls. */
    private var lowpass = 0f

    private val f0History = RollingHistory(HISTORY_SIZE)
    private val ampHistory = RollingHistory(HISTORY_SIZE)

    fun reset() {
        lowpass = 0f
        f0History.reset()
        ampHistory.reset()
    }

    fun accept(samples: FloatArray, sampleRate: Int = 16_000): ProsodyFrame {
        if (samples.isEmpty()) {
            return ProsodyFrame(false, 0f, 0f, 0f, 0f, SILENCE_DBFS, 0f)
        }

        // Single pass: mean (for DC removal ahead of autocorrelation), RMS
        // (for vocal effort), peak amplitude (for shimmer), and the low/high
        // split (for spectral tilt). Cheap enough to run per sample.
        val lpAlpha = (1.0 - exp(-2.0 * Math.PI * 1000.0 / sampleRate)).toFloat()
        var sum = 0.0
        var sumSq = 0.0
        var peakAmp = 0f
        var lowSq = 0.0
        var highSq = 0.0
        for (x in samples) {
            sum += x
            sumSq += x.toDouble() * x
            val ax = abs(x)
            if (ax > peakAmp) peakAmp = ax
            lowpass += lpAlpha * (x - lowpass)
            val high = x - lowpass
            lowSq += lowpass.toDouble() * lowpass
            highSq += high.toDouble() * high
        }

        val rms = sqrt(sumSq / samples.size)
        val vocalEffortDb = if (rms <= 1e-9) SILENCE_DBFS
        else (20.0 * log10(rms)).toFloat().coerceAtLeast(SILENCE_DBFS)

        val bandTotal = lowSq + highSq
        val spectralTilt = if (bandTotal <= 1e-12) 0f else (highSq / bandTotal).toFloat()

        // Search lags corresponding to [minF0Hz, maxF0Hz]. Too short to hold
        // even the shortest period at the top of the range: nothing to find.
        val minLag = max(1, (sampleRate / config.maxF0Hz).roundToInt())
        val maxLag = min((sampleRate / config.minF0Hz).roundToInt(), samples.size - 1)
        if (samples.size <= minLag || maxLag < minLag) {
            return ProsodyFrame(false, 0f, 0f, 0f, 0f, vocalEffortDb, spectralTilt)
        }

        val mean = (sum / samples.size).toFloat()
        var energy0 = 0.0
        for (x in samples) {
            val c = x - mean
            energy0 += c.toDouble() * c
        }

        var bestLag = -1
        var bestNorm = 0.0
        for (lag in minLag..maxLag) {
            var cross = 0.0
            val limit = samples.size - lag
            for (i in 0 until limit) {
                cross += (samples[i] - mean) * (samples[i + lag] - mean)
            }
            val norm = if (energy0 > 1e-9) cross / energy0 else 0.0
            if (norm > bestNorm) {
                bestNorm = norm
                bestLag = lag
            }
        }

        val harmonicity = bestNorm.toFloat().coerceIn(0f, 1f)
        val voiced = bestLag > 0 && bestNorm >= config.voicingThreshold
        if (!voiced) {
            return ProsodyFrame(false, 0f, 0f, 0f, harmonicity, vocalEffortDb, spectralTilt)
        }

        val f0 = sampleRate / bestLag.toFloat()
        f0History.push(f0)
        ampHistory.push(peakAmp)

        // Period-to-period and amplitude-to-amplitude variation, each
        // normalised by its own mean so jitter/shimmer read as fractions
        // regardless of pitch or volume.
        val jitter = f0History.relativeVariation { 1f / it }
        val shimmer = ampHistory.relativeVariation { it }

        return ProsodyFrame(true, f0, jitter, shimmer, harmonicity, vocalEffortDb, spectralTilt)
    }

    /**
     * Fixed-size circular buffer of recent frame values, used to turn a
     * single-frame measurement (pitch, amplitude) into a period-to-period
     * variation without allocating on every call.
     */
    private class RollingHistory(size: Int) {
        private val buffer = FloatArray(size)
        private var writeIndex = 0
        private var count = 0

        fun push(value: Float) {
            buffer[writeIndex] = value
            writeIndex = (writeIndex + 1) % buffer.size
            if (count < buffer.size) count++
        }

        fun reset() {
            writeIndex = 0
            count = 0
        }

        /**
         * Mean absolute difference between consecutive (transformed) entries,
         * divided by their mean. Needs at least 3 entries to say anything
         * about stability rather than noise from a single transition.
         */
        inline fun relativeVariation(transform: (Float) -> Float): Float {
            if (count < 3) return 0f
            val oldest = (writeIndex - count + buffer.size) % buffer.size
            var prev = transform(buffer[oldest])
            var sumAbsDiff = 0.0
            var sumVal = prev.toDouble()
            for (k in 1 until count) {
                val idx = (oldest + k) % buffer.size
                val v = transform(buffer[idx])
                sumAbsDiff += abs(v - prev)
                sumVal += v
                prev = v
            }
            val mean = sumVal / count
            return if (mean <= 1e-9) 0f else (sumAbsDiff / (count - 1) / mean).toFloat()
        }
    }

    companion object {
        private const val SILENCE_DBFS = -96f
        private const val HISTORY_SIZE = 8
    }
}
