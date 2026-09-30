// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.classify

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.tasks.audio.audioclassifier.AudioClassifier
import com.google.mediapipe.tasks.audio.core.RunningMode
import com.google.mediapipe.tasks.components.containers.AudioData
import com.google.mediapipe.tasks.core.BaseOptions
import org.lsrith.acousticmonitor.audio.AudioSpec
import org.lsrith.acousticmonitor.audio.AudioWindow
import org.lsrith.acousticmonitor.models.ModelCatalog
import org.lsrith.acousticmonitor.models.ModelRepository

/**
 * The acoustic fast path: stock YAMNet over 521 AudioSet classes.
 *
 * Runs in AUDIO_CLIPS mode with windows we cut ourselves rather than
 * AUDIO_STREAM, so the window/hop geometry is explicit and each inference can
 * be timed individually. That timing is the whole point of Phase 1.
 */
class YamnetClassifier(
    private val context: Context,
    private val models: ModelRepository,
) {

    private var classifier: AudioClassifier? = null

    private val format: AudioData.AudioDataFormat =
        AudioData.AudioDataFormat.builder()
            .setNumOfChannels(1)
            .setSampleRate(AudioSpec.SAMPLE_RATE.toFloat())
            .build()

    /** @throws IllegalStateException if the weights have not been downloaded yet. */
    fun open() {
        if (classifier != null) return
        val spec = ModelCatalog.YAMNET
        check(models.isCached(spec)) { "${spec.displayName} weights are not downloaded" }

        // Memory-mapped rather than bundled in assets: keeps the APK small and
        // scales to the much larger weights later phases need.
        val options = AudioClassifier.AudioClassifierOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(models.map(spec)).build())
            .setRunningMode(RunningMode.AUDIO_CLIPS)
            .setMaxResults(MAX_RESULTS)
            .build()
        classifier = AudioClassifier.createFromOptions(context, options)
        Log.i(TAG, "YAMNet ready (${spec.fileName}, ${spec.sizeBytes} bytes)")
    }

    fun close() {
        classifier?.close()
        classifier = null
    }

    /** Returns null if the classifier is closed or the model produced no result. */
    fun classify(window: AudioWindow, topN: Int = 8): FrameResult? {
        val active = classifier ?: return null

        val audio = AudioData.create(format, window.samples.size)
        audio.load(window.samples)

        val startNanos = SystemClock.elapsedRealtimeNanos()
        val result = try {
            active.classify(audio)
        } catch (t: Throwable) {
            Log.e(TAG, "classify failed", t)
            return null
        }
        val doneNanos = SystemClock.elapsedRealtimeNanos()

        // AUDIO_CLIPS returns one ClassificationResult per internal frame; a
        // 0.975 s clip yields a single frame, but take the last defensively.
        val categories = result.classificationResults()
            .lastOrNull()
            ?.classifications()
            ?.firstOrNull()
            ?.categories()
            ?: return null

        val scores = HashMap<String, Float>(categories.size)
        for (c in categories) scores[c.categoryName()] = c.score()

        val top = categories
            .sortedByDescending { it.score() }
            .take(topN)
            .map { ScoredClass(it.categoryName(), it.score()) }

        return FrameResult(
            endSampleIndex = window.endSampleIndex,
            top = top,
            scores = scores,
            inferenceMs = (doneNanos - startNanos) / 1_000_000.0,
            captureToResultMs = (doneNanos - window.capturedAtNanos) / 1_000_000.0,
        )
    }

    companion object {
        private const val TAG = "YamnetClassifier"

        /** YAMNet's full AudioSet ontology; the decision engine needs specific labels, not just the top few. */
        private const val MAX_RESULTS = 521
    }
}
