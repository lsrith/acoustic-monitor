// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.classify

/** One AudioSet class and its probability for a single window. */
data class ScoredClass(val label: String, val score: Float)


data class FrameResult(
    val endSampleIndex: Long,
    /** Highest-scoring classes, for display. */
    val top: List<ScoredClass>,
    /** Full label → score map, so the decision engine can look up specific cues. */
    val scores: Map<String, Float>,
    /** Time spent inside the model. */
    val inferenceMs: Double,
    /** Mic-to-verdict, including windowing and dispatch. This is the number that matters. */
    val captureToResultMs: Double,
)
