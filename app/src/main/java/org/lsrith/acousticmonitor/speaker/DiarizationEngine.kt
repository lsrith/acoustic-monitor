// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.speaker

import android.util.Log
import com.k2fsa.sherpa.onnx.FastClusteringConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarization
import com.k2fsa.sherpa.onnx.OfflineSpeakerDiarizationConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeakerSegmentationPyannoteModelConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.models.ModelRepository
import java.io.File

/** One continuous stretch where [speaker] was the only (or dominant) voice. */
data class VoiceTurn(val speaker: Int, val startSec: Float, val endSec: Float) {
    val durationSec: Float get() = endSec - startSec
}

/**
 * A full diarization pass over one clip.
 *
 * [turnsPerMinute] and [overlapRatio] exist because raw sound level cannot
 * distinguish a heated argument from an ordinary conversation, but turn-taking
 * rhythm can: arguments interrupt more and hand off the floor faster.
 */
data class DiarizationResult(
    val turns: List<VoiceTurn>,
    val speakerCount: Int,
    val totalSpeechSec: Float,
    /** Turns per minute of speech — rises in an argument. */
    val turnsPerMinute: Float,
    /** Fraction of turn boundaries where a new speaker starts before the previous ends. */
    val overlapRatio: Float,
    val processingMs: Double,
)

/**
 * Offline speaker diarization: how many voices are in a clip, who spoke when,
 * and how much they talk over each other.
 *
 * The acoustic and speech paths know *that* something was said and *how
 * loud*; only diarization knows whether it was one voice escalating or two
 * voices cutting each other off, which is what separates a shouted monologue
 * from a shouting match.
 *
 * Driven from a single consumer, same as [SpeakerEngine] — no locking is
 * added, and none should be.
 */
class DiarizationEngine(private val models: ModelRepository) {

    private var diarizer: OfflineSpeakerDiarization? = null

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _lastResult = MutableStateFlow<DiarizationResult?>(null)
    val lastResult: StateFlow<DiarizationResult?> = _lastResult.asStateFlow()

    fun open(): Boolean {
        // Idempotent, for the same reason as SpeakerEngine.open: this is now
        // called per speech segment, and each construction leaked the last.
        if (diarizer != null) return true
        val segmentationSpec = ModelCatalog.SPEAKER_SEGMENTATION
        val embeddingSpec = ModelCatalog.SPEAKER_EMBEDDING
        if (!models.isCached(segmentationSpec)) {
            _error.value = "${segmentationSpec.displayName} is not downloaded"
            return false
        }
        if (!models.isCached(embeddingSpec)) {
            _error.value = "${embeddingSpec.displayName} is not downloaded"
            return false
        }

        return try {
            val segmentationModel = File(models.dirFor(segmentationSpec), "model.int8.onnx").absolutePath
            val newDiarizer = OfflineSpeakerDiarization(
                assetManager = null,
                config = OfflineSpeakerDiarizationConfig(
                    segmentation = OfflineSpeakerSegmentationModelConfig(
                        pyannote = OfflineSpeakerSegmentationPyannoteModelConfig(model = segmentationModel),
                        numThreads = 1,
                        debug = false,
                        provider = "cpu",
                    ),
                    embedding = SpeakerEmbeddingExtractorConfig(
                        model = models.fileFor(embeddingSpec).absolutePath,
                        numThreads = 1,
                        debug = false,
                        provider = "cpu",
                    ),
                    clustering = FastClusteringConfig(
                        numClusters = -1,
                        threshold = CLUSTERING_THRESHOLD,
                    ),
                ),
            )
            diarizer = newDiarizer
            _error.value = null
            _ready.value = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not open diarization model", t)
            _error.value = t.message ?: "Could not load diarization model"
            close()
            false
        }
    }

    fun close() {
        runCatching { diarizer?.release() }
        diarizer = null
        _ready.value = false
        _lastResult.value = null
    }

    /**
     * @param samples 16 kHz mono. Returns null if not open or the audio was
     * too short — diarization needs enough material to cluster voices.
     */
    fun process(samples: FloatArray): DiarizationResult? {
        val engine = diarizer ?: return null
        if (samples.size < AudioSpec.SAMPLE_RATE * MIN_AUDIO_SEC) return null

        val startNanos = System.nanoTime()
        val segments = try {
            engine.process(samples)
        } catch (t: Throwable) {
            Log.e(TAG, "diarization failed", t)
            _error.value = t.message ?: "Diarization failed"
            return null
        }
        val processingMs = (System.nanoTime() - startNanos) / 1_000_000.0

        val turns = segments
            .map { VoiceTurn(speaker = it.speaker, startSec = it.start, endSec = it.end) }
            .sortedBy { it.startSec }

        val speakerCount = turns.map { it.speaker }.distinct().size
        val totalSpeechSec = turns.sumOf { it.durationSec.toDouble() }.toFloat()
        val turnsPerMinute = if (totalSpeechSec > 0f) {
            turns.size / (totalSpeechSec / 60f)
        } else {
            0f
        }

        var overlapBoundaries = 0
        for (i in 1 until turns.size) {
            if (turns[i].startSec < turns[i - 1].endSec) overlapBoundaries++
        }
        val overlapRatio = if (turns.size >= 2) {
            overlapBoundaries.toFloat() / (turns.size - 1)
        } else {
            0f
        }

        val result = DiarizationResult(
            turns = turns,
            speakerCount = speakerCount,
            totalSpeechSec = totalSpeechSec,
            turnsPerMinute = turnsPerMinute,
            overlapRatio = overlapRatio,
            processingMs = processingMs,
        )
        _lastResult.value = result
        return result
    }

    companion object {
        private const val TAG = "DiarizationEngine"

        /** Shortest clip worth clustering; below this there isn't enough material to tell voices apart. */
        private const val MIN_AUDIO_SEC = 3

        /**
         * Cosine-distance cutoff used to decide whether two segments belong
         * to the same speaker. Uncalibrated — chosen as a plausible starting
         * point, not measured against real multi-speaker recordings. Needs
         * tuning once real clips are available.
         */
        const val CLUSTERING_THRESHOLD = 0.5f
    }
}
