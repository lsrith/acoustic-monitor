// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.audio

/**
 * Fixed-capacity circular buffer of mono float samples.
 *
 * Holds the most recent [capacity] samples so that, when the decision engine
 * fires, we can write out the audio that led up to the trigger. Nothing here
 * ever leaves the device.
 *
 * Single writer (the capture thread), multiple readers; all access is
 * synchronised because reads copy out whole windows.
 */
class AudioRingBuffer(private val capacity: Int) {

    private val buffer = FloatArray(capacity)
    private var writeIndex = 0

    /** Total samples ever written. Doubles as a monotonic sample clock. */
    var totalWritten: Long = 0
        private set

    @Synchronized
    fun write(samples: FloatArray, length: Int = samples.size) {
        var offset = 0
        var remaining = length
        while (remaining > 0) {
            val chunk = minOf(remaining, capacity - writeIndex)
            System.arraycopy(samples, offset, buffer, writeIndex, chunk)
            writeIndex = (writeIndex + chunk) % capacity
            offset += chunk
            remaining -= chunk
        }
        totalWritten += length
    }

    /**
     * Copies the most recent [length] samples in chronological order.
     * Returns null if the buffer has not yet accumulated that many.
     */
    @Synchronized
    fun readLast(length: Int): FloatArray? {
        if (length > capacity || totalWritten < length) return null
        val out = FloatArray(length)
        val start = ((writeIndex - length) % capacity + capacity) % capacity
        val firstChunk = minOf(length, capacity - start)
        System.arraycopy(buffer, start, out, 0, firstChunk)
        if (firstChunk < length) {
            System.arraycopy(buffer, 0, out, firstChunk, length - firstChunk)
        }
        return out
    }

    @Synchronized
    fun clear() {
        buffer.fill(0f)
        writeIndex = 0
        totalWritten = 0
    }
}
