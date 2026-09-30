// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.audio

import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Writes mono float PCM audio to a standard 16-bit PCM `.wav` file.
 *
 * Used to persist the "permitted audio snippet" — the ring buffer contents
 * around a detection trigger — to app-private storage for later review.
 */
object WavWriter {

    private const val HEADER_BYTES = 44
    private const val BITS_PER_SAMPLE = 16
    private const val CHANNELS = 1
    private const val CHUNK_SAMPLES = 4096

    /**
     * Writes [samples] as a 16-bit PCM mono WAV file.
     * Returns the number of audio bytes written (excluding the header).
     */
    fun writeMonoPcm16(file: File, samples: FloatArray, sampleRate: Int): Long {
        file.parentFile?.mkdirs()

        val dataBytes = samples.size.toLong() * 2
        val byteRate = sampleRate * CHANNELS * BITS_PER_SAMPLE / 8
        val blockAlign = CHANNELS * BITS_PER_SAMPLE / 8

        BufferedOutputStream(FileOutputStream(file)).use { out ->
            out.write(buildHeader(dataBytes, sampleRate, byteRate, blockAlign))

            // Stream in chunks rather than allocating one array for the whole
            // buffer — a full 60 s snippet at 16 kHz is fine, but there is no
            // reason to double memory usage for a straight serial write.
            val chunk = ByteArray(minOf(CHUNK_SAMPLES, samples.size) * 2)
            var i = 0
            while (i < samples.size) {
                val count = minOf(CHUNK_SAMPLES, samples.size - i)
                var pos = 0
                for (j in 0 until count) {
                    // Input samples are nominally in [-1.0, 1.0], but upstream
                    // gain/DSP can overshoot slightly. Clamp before scaling so
                    // an overshoot converts to full-scale instead of wrapping
                    // around through Short's two's-complement range.
                    val clamped = samples[i + j].coerceIn(-1f, 1f)
                    val pcm = (clamped * 32767f).toInt().toShort()
                    chunk[pos++] = (pcm.toInt() and 0xFF).toByte()
                    chunk[pos++] = ((pcm.toInt() shr 8) and 0xFF).toByte()
                }
                out.write(chunk, 0, pos)
                i += count
            }
        }

        return dataBytes
    }

    private fun buildHeader(dataBytes: Long, sampleRate: Int, byteRate: Int, blockAlign: Int): ByteArray {
        val header = ByteArray(HEADER_BYTES)
        var pos = 0

        fun ascii(s: String) {
            s.forEach { header[pos++] = it.code.toByte() }
        }
        fun u32(v: Long) {
            header[pos++] = (v and 0xFF).toByte()
            header[pos++] = ((v shr 8) and 0xFF).toByte()
            header[pos++] = ((v shr 16) and 0xFF).toByte()
            header[pos++] = ((v shr 24) and 0xFF).toByte()
        }
        fun u16(v: Int) {
            header[pos++] = (v and 0xFF).toByte()
            header[pos++] = ((v shr 8) and 0xFF).toByte()
        }

        ascii("RIFF")
        u32(36 + dataBytes)
        ascii("WAVE")

        ascii("fmt ")
        u32(16) // fmt subchunk size for PCM
        u16(1) // audio format 1 = PCM
        u16(CHANNELS)
        u32(sampleRate.toLong())
        u32(byteRate.toLong())
        u16(blockAlign)
        u16(BITS_PER_SAMPLE)

        ascii("data")
        u32(dataBytes)

        return header
    }
}
