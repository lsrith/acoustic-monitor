// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A contiguous run of samples exactly as read from the microphone. */
data class AudioChunk(
    val samples: FloatArray,
    val endSampleIndex: Long,
    val capturedAtNanos: Long,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioChunk && endSampleIndex == other.endSampleIndex)

    override fun hashCode(): Int = endSampleIndex.hashCode()
}

/**
 * One window of audio, ready for analysis.
 *
 * [capturedAtNanos] is stamped when the final sample of the window was read
 * from the mic, so downstream stages can measure true capture-to-decision
 * latency rather than just inference time.
 */
data class AudioWindow(
    val samples: FloatArray,
    val endSampleIndex: Long,
    val capturedAtNanos: Long,
) {
    // FloatArray gives identity semantics by default; windows are value-like here.
    override fun equals(other: Any?): Boolean =
        this === other || (other is AudioWindow && endSampleIndex == other.endSampleIndex)

    override fun hashCode(): Int = endSampleIndex.hashCode()
}

/**
 * The single audio source for the whole app.
 *
 * Owns one [AudioRecord], keeps a rolling [ring] of recent audio, and fans
 * overlapping windows out to any number of consumers via [windows]. Phase 1
 * has one consumer (YAMNet); later phases add VAD/ASR/speaker/prosody without
 * opening a second microphone stream — Android would not reliably allow that
 * anyway.
 */
class AudioPipeline {

    val ring = AudioRingBuffer(AudioSpec.RING_SAMPLES)

    private val _windows = MutableSharedFlow<AudioWindow>(
        replay = 0,
        // Consumers that stall drop windows rather than blocking capture.
        extraBufferCapacity = 16,
    )
    val windows: SharedFlow<AudioWindow> = _windows.asSharedFlow()

    private val _chunks = MutableSharedFlow<AudioChunk>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /**
     * Contiguous, non-overlapping audio as it is read.
     *
     * [windows] overlap by design, which is right for frame-wise classification
     * and wrong for anything that consumes a continuous stream — feeding VAD
     * overlapping windows would replay half of every buffer. Speech segmentation
     * and everything downstream of it reads this instead.
     */
    val chunks: SharedFlow<AudioChunk> = _chunks.asSharedFlow()

    private val _state = MutableStateFlow(CaptureState())
    val state: StateFlow<CaptureState> = _state.asStateFlow()

    private var job: Job? = null
    private var record: AudioRecord? = null

    data class CaptureState(
        val running: Boolean = false,
        val audioSourceName: String = "-",
        val startedAtNanos: Long = 0L,
        val windowsEmitted: Long = 0L,
        val droppedWindows: Long = 0L,
        val error: String? = null,
    )

    @SuppressLint("MissingPermission") // Caller must hold RECORD_AUDIO; checked in the service.
    fun start(scope: CoroutineScope) {
        if (job != null) return

        job = scope.launch(Dispatchers.IO) {
            val minBuffer = AudioRecord.getMinBufferSize(
                AudioSpec.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            if (minBuffer <= 0) {
                _state.value = _state.value.copy(error = "AudioRecord.getMinBufferSize failed ($minBuffer)")
                return@launch
            }

            val bufferBytes = maxOf(minBuffer, AudioSpec.READ_SAMPLES * 2 * 4)
            val opened = openRecord(bufferBytes)
            if (opened == null) {
                _state.value = _state.value.copy(
                    error = "Could not open AudioRecord on any source (tried ${SOURCES.joinToString { it.second }})"
                )
                return@launch
            }
            val (rec, sourceName) = opened
            record = rec
            rec.startRecording()
            _state.value = CaptureState(
                running = true,
                audioSourceName = sourceName,
                startedAtNanos = SystemClock.elapsedRealtimeNanos(),
            )
            Log.i(TAG, "capture started: source=$sourceName buffer=$bufferBytes")

            val shorts = ShortArray(AudioSpec.READ_SAMPLES)
            val floats = FloatArray(AudioSpec.READ_SAMPLES)
            var sinceLastWindow = 0

            try {
                while (isActive) {
                    val read = rec.read(shorts, 0, shorts.size)
                    if (read <= 0) {
                        if (read < 0) Log.w(TAG, "AudioRecord.read returned $read")
                        continue
                    }
                    for (i in 0 until read) floats[i] = shorts[i] / 32768f
                    ring.write(floats, read)

                    // Copied because `floats` is reused on the next read.
                    _chunks.tryEmit(
                        AudioChunk(
                            samples = floats.copyOf(read),
                            endSampleIndex = ring.totalWritten,
                            capturedAtNanos = SystemClock.elapsedRealtimeNanos(),
                        )
                    )

                    sinceLastWindow += read
                    if (sinceLastWindow < AudioSpec.HOP_SAMPLES) continue
                    sinceLastWindow = 0

                    val window = ring.readLast(AudioSpec.WINDOW_SAMPLES) ?: continue
                    val emitted = _windows.tryEmit(
                        AudioWindow(
                            samples = window,
                            endSampleIndex = ring.totalWritten,
                            capturedAtNanos = SystemClock.elapsedRealtimeNanos(),
                        )
                    )
                    _state.value = _state.value.let {
                        if (emitted) it.copy(windowsEmitted = it.windowsEmitted + 1)
                        else it.copy(droppedWindows = it.droppedWindows + 1)
                    }
                }
            } finally {
                runCatching { rec.stop() }
                runCatching { rec.release() }
                record = null
                _state.value = _state.value.copy(running = false)
                Log.i(TAG, "capture stopped")
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    /**
     * Opens the least-processed source the device will give us.
     *
     * Automatic gain control and noise suppression flatten exactly the loudness
     * and spectral cues that shout/scream detection relies on, so UNPROCESSED is
     * strongly preferred. Support is not universal — and a device may advertise
     * the constant yet fail to initialise — so we fall through to progressively
     * more processed sources and report which one we landed on.
     *
     * The emulator is the exception: UNPROCESSED initialises there but the host
     * microphone is only routed into the ordinary input path, so it delivers
     * silence. On an emulator we try the sources in the opposite order.
     */
    @SuppressLint("MissingPermission")
    private fun openRecord(bufferBytes: Int): Pair<AudioRecord, String>? {
        val sources = if (isEmulator) SOURCES.asReversed() else SOURCES
        for ((source, name) in sources) {
            val rec = try {
                AudioRecord(
                    source,
                    AudioSpec.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes,
                )
            } catch (t: Throwable) {
                Log.w(TAG, "source $name unavailable: ${t.message}")
                continue
            }
            if (rec.state == AudioRecord.STATE_INITIALIZED) return rec to name
            Log.w(TAG, "source $name did not initialise (state=${rec.state})")
            rec.release()
        }
        return null
    }

    companion object {
        private const val TAG = "AudioPipeline"

        /** Most faithful first. The chosen source is surfaced in the UI. */
        private val SOURCES = listOf(
            MediaRecorder.AudioSource.UNPROCESSED to "UNPROCESSED",
            MediaRecorder.AudioSource.VOICE_RECOGNITION to "VOICE_RECOGNITION",
            MediaRecorder.AudioSource.MIC to "MIC",
        )

        private val isEmulator: Boolean
            get() = Build.HARDWARE == "ranchu" || Build.HARDWARE == "goldfish" ||
                Build.FINGERPRINT.contains("generic") || Build.FINGERPRINT.contains("emulator")
    }
}
