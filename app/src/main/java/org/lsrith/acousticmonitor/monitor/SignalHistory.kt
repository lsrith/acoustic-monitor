// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.lsrith.acousticmonitor.detect.WindowVerdict

/**
 * A short rolling history of the decision signals, so they can be drawn.
 *
 * A single instantaneous number cannot show what this system is actually
 * doing. "Loud" means nothing without the room's normal beside it; a fused
 * score means nothing without the moving threshold it is being compared to;
 * accumulation is *by definition* a shape over time. Reading them as three
 * static digits was the whole problem.
 *
 * Every field here is copied straight off a real [WindowVerdict] — this
 * records, it does not compute, and it never synthesises a sample for a window
 * that did not happen.
 */
class SignalHistory(private val capacity: Int = 240) {

    /** One decision window. Times are wall-clock so gaps stay visible. */
    data class Sample(
        val atEpochMs: Long,
        val currentDbfs: Float,
        val baselineDbfs: Float,
        val currentTreble: Float,
        val baselineTreble: Float,
        val fusedScore: Float,
        val mediumThreshold: Float,
        val highThreshold: Float,
        val cuePath: Float,
        val loudnessPath: Float,
        val threatPath: Float,
        val mediaScore: Float,
        val speechScore: Float,
        val laughterScore: Float,
        val accumulation: Float,
        /** Vocal strain, 0..1. Zero over unvoiced windows, by definition. */
        val vocalStress: Float = 0f,
    )

    private val _samples = MutableStateFlow<List<Sample>>(emptyList())
    val samples: StateFlow<List<Sample>> = _samples.asStateFlow()

    fun record(verdict: WindowVerdict, atEpochMs: Long) {
        val sample = Sample(
            atEpochMs = atEpochMs,
            currentDbfs = verdict.ambient.currentDbfs,
            baselineDbfs = verdict.ambient.baselineDbfs,
            currentTreble = verdict.ambient.currentTreble,
            baselineTreble = verdict.ambient.baselineTreble,
            fusedScore = verdict.fusedScore,
            mediumThreshold = verdict.effectiveMediumThreshold,
            highThreshold = verdict.effectiveHighThreshold,
            cuePath = verdict.cuePathScore,
            loudnessPath = verdict.ambientPathScore,
            threatPath = verdict.threatPathScore,
            mediaScore = verdict.mediaScore,
            speechScore = verdict.speechScore,
            laughterScore = verdict.laughterScore,
            accumulation = verdict.accumulation.level,
            vocalStress = verdict.vocalStress,
        )
        // update{} rather than read-modify-write: this is called from the
        // inference thread and cleared from another, and a lost sample would
        // put a hole in a trace that is meant to be a faithful record.
        _samples.update { current ->
            val next = current + sample
            if (next.size > capacity) next.takeLast(capacity) else next
        }
    }

    fun clear() {
        _samples.value = emptyList()
    }
}
