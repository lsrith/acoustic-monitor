// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.speaker

import android.util.Log
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.models.ModelRepository

/**
 * A voiceprint comparison result.
 *
 * [similarity] is the threshold the match cleared, not the raw cosine score —
 * [SpeakerEmbeddingManager.search] reports only pass/fail against a threshold,
 * not the underlying value, so this is a conservative lower bound rather than
 * the true closeness of the match.
 */
data class SpeakerMatch(val name: String, val similarity: Float, val isMatch: Boolean)

/**
 * Voiceprint enrolment and identification.
 *
 * The acoustic and speech paths can tell that someone spoke; a
 * voiceprint can tell which enrolled speaker is the closest match.
 * Everything here exists to answer that one question cheaply enough to run
 * on every flagged utterance.
 *
 * Driven from a single consumer, same as [org.lsrith.acousticmonitor.speech.SpeechEngine] —
 * no locking is added, and none should be.
 */
class SpeakerEngine(private val models: ModelRepository) {

    private var extractor: SpeakerEmbeddingExtractor? = null
    private var manager: SpeakerEmbeddingManager? = null

    /**
     * Our own copy of each speaker's embeddings.
     *
     * sherpa's manager answers "which speaker, above this threshold" and
     * nothing else — `search` returns a name, `verify` returns a boolean, and
     * neither yields a score. The similarity this class used to report was the
     * threshold constant echoed back, which is a number that was never
     * measured. Keeping the vectors lets the cosine be computed here, so the
     * figure on screen and in the evidence timeline is a real one.
     */
    private val embeddings = mutableMapOf<String, MutableList<FloatArray>>()

    private val _enrolled = MutableStateFlow<List<String>>(emptyList())
    val enrolled: StateFlow<List<String>> = _enrolled.asStateFlow()

    private val _lastMatch = MutableStateFlow<SpeakerMatch?>(null)
    val lastMatch: StateFlow<SpeakerMatch?> = _lastMatch.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    /**
     * Whether the weights are present, regardless of whether the engine has
     * been opened.
     *
     * These are different questions, and conflating them deadlocked the UI:
     * "ready" meant "opened", the engine only opened during enrolment, and
     * enrolment was disabled until ready. Availability is what a caller needs
     * to decide whether to offer the feature.
     */
    fun modelAvailable(): Boolean = models.isCached(ModelCatalog.SPEAKER_EMBEDDING)

    fun open(): Boolean {
        // Idempotent. Called once per speech segment now, and without this
        // each call built a fresh native extractor over the top of the last
        // one — a steady native leak with nothing in the Java heap to show it.
        if (extractor != null) return true
        val spec = ModelCatalog.SPEAKER_EMBEDDING
        if (!models.isCached(spec)) {
            _error.value = "${spec.displayName} is not downloaded"
            return false
        }

        return try {
            val newExtractor = SpeakerEmbeddingExtractor(
                assetManager = null,
                config = SpeakerEmbeddingExtractorConfig(
                    model = models.fileFor(spec).absolutePath,
                    numThreads = 1,
                    debug = false,
                    provider = "cpu",
                ),
            )
            extractor = newExtractor
            manager = SpeakerEmbeddingManager(newExtractor.dim())
            _enrolled.value = emptyList()
            _error.value = null
            _ready.value = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not open speaker model", t)
            _error.value = t.message ?: "Could not load speaker model"
            close()
            false
        }
    }

    fun close() {
        runCatching { extractor?.release() }
        runCatching { manager?.release() }
        extractor = null
        manager = null
        _ready.value = false
        _enrolled.value = emptyList()
        _lastMatch.value = null
    }

    /**
     * Adds a voiceprint from several separate utterances.
     *
     * Several rather than one, because a single embedding captures one
     * moment's pitch, distance and phrasing, and the manager accepts an array
     * precisely so it does not have to. Utterances are speech that voice
     * detection has already isolated — enrolling a fixed slice of the
     * microphone captured whatever was in the room, including the silence
     * between sentences, and an embedding of room tone matches nothing or
     * everything.
     */
    fun enroll(name: String, utterances: List<FloatArray>): Boolean {
        val mgr = manager ?: return false
        val usable = utterances.filter { it.size >= MIN_UTTERANCE_SAMPLES }
        if (usable.isEmpty()) {
            _error.value = "No speech long enough to enrol from"
            return false
        }

        val vectors = usable.mapNotNull { embed(it) }
        if (vectors.isEmpty()) {
            _error.value = "Could not compute a voiceprint from that audio"
            return false
        }

        val added = mgr.add(name, vectors.toTypedArray())
        if (added) {
            embeddings[name] = vectors.toMutableList()
            _enrolled.value = mgr.allSpeakerNames().toList()
            _error.value = null
        }
        return added
    }

    /**
     * Identifies the closest enrolled speaker.
     *
     * Returns the best candidate and its real cosine similarity even when it
     * falls short — [SpeakerMatch.isMatch] carries whether it cleared the bar.
     * A near miss is information: an unrecognised voice in a home that has
     * enrolled its adults is itself worth recording, and discarding everything
     * below a threshold threw that away.
     */
    fun identify(samples: FloatArray): SpeakerMatch? {
        if (manager == null) return null
        val embedding = embed(samples) ?: return null
        if (embeddings.isEmpty()) return null

        val best = embeddings.mapValues { (_, vectors) ->
            vectors.maxOf { cosine(it, embedding) }
        }.maxByOrNull { it.value } ?: return null

        val match = SpeakerMatch(
            name = best.key,
            similarity = best.value,
            isMatch = best.value >= DEFAULT_THRESHOLD,
        )
        _lastMatch.value = match
        return match
    }

    /** Cosine similarity. The embeddings are not guaranteed unit length. */
    private fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return 0f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i].toDouble() * b[i]
            na += a[i].toDouble() * a[i]
            nb += b[i].toDouble() * b[i]
        }
        val denom = kotlin.math.sqrt(na) * kotlin.math.sqrt(nb)
        return if (denom <= 0.0) 0f else (dot / denom).toFloat()
    }

    fun remove(name: String) {
        val mgr = manager ?: return
        mgr.remove(name)
        embeddings.remove(name)
        _enrolled.value = mgr.allSpeakerNames().toList()
    }

    fun clearAll() {
        val mgr = manager ?: return
        mgr.allSpeakerNames().forEach { mgr.remove(it) }
        embeddings.clear()
        _enrolled.value = emptyList()
        _lastMatch.value = null
    }

    private fun embed(samples: FloatArray): FloatArray? {
        val ext = extractor ?: return null
        return try {
            val stream = ext.createStream()
            stream.acceptWaveform(samples, AudioSpec.SAMPLE_RATE)
            stream.inputFinished()
            val embedding = if (ext.isReady(stream)) ext.compute(stream) else null
            stream.release()
            embedding
        } catch (t: Throwable) {
            Log.e(TAG, "embedding failed", t)
            null
        }
    }

    companion object {
        private const val TAG = "SpeakerEngine"

        /**
         * Cosine-similarity cutoff for [identify]. Uncalibrated — chosen as a
         * plausible midpoint, not measured against real enrolled voices. Needs
         * tuning once real voiceprints are available.
         */
        const val DEFAULT_THRESHOLD = 0.5f

        /**
         * Shortest utterance worth embedding, in samples.
         *
         * Below about a second a speaker embedding is dominated by the
         * particular sounds spoken rather than the voice speaking them.
         */
        const val MIN_UTTERANCE_SAMPLES = AudioSpec.SAMPLE_RATE
    }
}
