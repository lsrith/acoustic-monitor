// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.nlp

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.models.ModelRepository
import java.nio.LongBuffer
import kotlin.math.exp

/**
 * Per-label toxicity probabilities for one piece of text, plus how long the
 * ONNX inference took.
 *
 * Each field is an independent sigmoid probability, not a distribution that
 * sums to 1 — this is multi-label classification, so a transcript can score
 * high on more than one label at once (or on none).
 */
data class ToxicityScores(
    val toxic: Float,
    val severeToxic: Float,
    val obscene: Float,
    val threat: Float,
    val insult: Float,
    val identityHate: Float,
    val inferenceMs: Double,
)


class ToxicityClassifier(private val models: ModelRepository) {

    private var session: OrtSession? = null
    private var tokenizer: WordPieceTokenizer? = null

    /** Names the loaded session actually declares — some exports omit token_type_ids. */
    private var declaredInputNames: Set<String> = emptySet()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    fun open(): Boolean {
        val modelSpec = ModelCatalog.TOXIC_BERT
        val vocabSpec = ModelCatalog.TOXIC_BERT_VOCAB
        if (!models.isCached(modelSpec) || !models.isCached(vocabSpec)) {
            _error.value = "${modelSpec.displayName} is not downloaded"
            return false
        }

        return try {
            val env = OrtEnvironment.getEnvironment()
            val newSession = env.createSession(
                models.fileFor(modelSpec).absolutePath,
                OrtSession.SessionOptions(),
            )
            val inputNames = newSession.inputNames
            Log.i(TAG, "Toxic-BERT session declares inputs: $inputNames")

            session = newSession
            declaredInputNames = inputNames
            tokenizer = WordPieceTokenizer(models.fileFor(vocabSpec))
            _error.value = null
            _ready.value = true
            true
        } catch (t: Throwable) {
            Log.e(TAG, "could not open toxicity model", t)
            _error.value = t.message ?: "Could not load toxicity model"
            close()
            false
        }
    }

    fun close() {
        runCatching { session?.close() }
        session = null
        tokenizer = null
        declaredInputNames = emptySet()
        _ready.value = false
    }

    /** Scores [text] against the six Jigsaw labels. Null if not open or [text] is blank. */
    fun classify(text: String): ToxicityScores? {
        val ortSession = session ?: return null
        val wp = tokenizer ?: return null
        if (text.isBlank()) return null

        val encoded = wp.tokenize(text)
        val shape = longArrayOf(1L, encoded.length.toLong())
        val env = OrtEnvironment.getEnvironment()

        val tensors = LinkedHashMap<String, OnnxTensor>()
        return try {
            if ("input_ids" in declaredInputNames) {
                tensors["input_ids"] = OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.inputIds), shape)
            }
            if ("attention_mask" in declaredInputNames) {
                tensors["attention_mask"] =
                    OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.attentionMask), shape)
            }
            if ("token_type_ids" in declaredInputNames) {
                tensors["token_type_ids"] =
                    OnnxTensor.createTensor(env, LongBuffer.wrap(encoded.tokenTypeIds), shape)
            }

            val startNs = System.nanoTime()
            ortSession.run(tensors).use { result ->
                val inferenceMs = (System.nanoTime() - startNs) / 1_000_000.0
                val logits = extractLogits(result)
                ToxicityScores(
                    toxic = sigmoid(logits[0]),
                    severeToxic = sigmoid(logits[1]),
                    obscene = sigmoid(logits[2]),
                    threat = sigmoid(logits[3]),
                    insult = sigmoid(logits[4]),
                    identityHate = sigmoid(logits[5]),
                    inferenceMs = inferenceMs,
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "classification failed", t)
            _error.value = t.message ?: "Toxicity classification failed"
            null
        } finally {
            tensors.values.forEach { runCatching { it.close() } }
        }
    }

    private fun extractLogits(result: OrtSession.Result): FloatArray {
        val raw = result.get(0).value
        @Suppress("UNCHECKED_CAST")
        val batch = raw as? Array<FloatArray> ?: error("unexpected toxic-bert output shape: $raw")
        return batch[0]
    }

    private fun sigmoid(x: Float): Float = (1.0 / (1.0 + exp(-x.toDouble()))).toFloat()

    private companion object {
        const val TAG = "ToxicityClassifier"
    }
}
