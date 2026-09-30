// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.speech

/**
 * One recognised utterance.
 *
 * Carries its own timing so a later phase can align text against the acoustic
 * events on the fast path — the revised model's "silent incident" turns on
 * threat language arriving with *no* acoustic event alongside it, which is only
 * checkable if both streams share a clock.
 */
data class TranscriptSegment(
    val id: Long,
    val startEpochMs: Long,
    /** Length of the speech segment itself, not of processing. */
    val audioSeconds: Double,
    val text: String,
    /** Language tag when the model reports one; empty otherwise. */
    val language: String,
    /** Emotion tag when the model reports one (SenseVoice does; Whisper does not). */
    val emotion: String,
    val asrMs: Double,
    /** Rules-based threat reading over [text]. Never a verdict — one signal. */
    val threat: org.lsrith.acousticmonitor.detect.ThreatAssessment? = null,
    /** Which recogniser produced [text]. */
    val modelLabel: String = "",
    /**
     * The other recogniser's reading of the same audio, when there is one.
     *
     * Both are kept because nothing available can reliably choose between
     * them. Whisper's language identification is unreliable on Singapore
     * Chinese and Tamil, Omnilingual has no language conditioning at all, and
     * sherpa exposes no per-result confidence to compare — so a judge would
     * have to be invented rather than measured. Since a flagged recording goes
     * to a person to verify anyway, the honest thing is to show both readings
     * and let the reasoning layer and the reviewer weigh them.
     */
    val altText: String = "",
    val altModelLabel: String = "",
    /** Rules-based threat reading over [altText]. */
    val altThreat: org.lsrith.acousticmonitor.detect.ThreatAssessment? = null,
    /**
     * Gain applied before recognition, and the peak level before it.
     *
     * A large gain means the speech reached the microphone very quietly, which
     * is the first thing to check when transcription is poor — it points at
     * distance and placement rather than at the model.
     */
    val asrGain: Float = 1f,
    val rawPeak: Float = 0f,
) {
    /**
     * Real-time factor. Below 1.0 means the model transcribes faster than the
     * audio arrives — the condition for keeping up with a live stream at all.
     */
    val realTimeFactor: Double
        get() = if (audioSeconds <= 0.0) 0.0 else (asrMs / 1000.0) / audioSeconds
}
