// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import android.content.Context
import android.util.Log
import org.lsrith.acousticmonitor.audio.AudioRingBuffer
import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.audio.WavWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


class SnippetStore(context: Context) {

    private val dir = File(context.filesDir, "snippets").apply { mkdirs() }

    /**
     * Extracts the audio spanning [event] plus a short margin.
     *
     * The buffer can only be read backwards from the present, so this takes the
     * event's wall-clock duration plus [MARGIN_SECONDS] of lead-in and trailing
     * quiet, bounded by the buffer's capacity.
     *
     * @return absolute path, or null if nothing could be written.
     */
    fun write(ring: AudioRingBuffer, event: DetectedEvent): String? {
        val durationMs = (event.endedAtEpochMs - event.startedAtEpochMs).coerceAtLeast(0)
        val wantedSeconds = (durationMs / 1000.0 + MARGIN_SECONDS)
            .coerceAtMost(AudioSpec.RING_SECONDS.toDouble())
        val wantedSamples = (wantedSeconds * AudioSpec.SAMPLE_RATE).toInt()

        // Fall back to whatever the buffer actually holds if it is not yet full.
        val samples = ring.readLast(wantedSamples)
            ?: ring.readLast(ring.totalWritten.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
            ?: return null

        val name = "${STAMP.format(Date(event.startedAtEpochMs))}-${event.severity.name.lowercase()}.wav"
        val file = File(dir, name)
        return try {
            WavWriter.writeMonoPcm16(file, samples, AudioSpec.SAMPLE_RATE)
            file.absolutePath
        } catch (t: Throwable) {
            Log.e(TAG, "could not write snippet", t)
            null
        }
    }

    fun delete(path: String?) {
        if (path.isNullOrBlank()) return
        runCatching { File(path).delete() }
    }

    companion object {
        private const val TAG = "SnippetStore"
        private const val MARGIN_SECONDS = 6.0
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
    }
}
