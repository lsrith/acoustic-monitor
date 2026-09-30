// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.monitor

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.lsrith.acousticmonitor.detect.CueGroup
import org.lsrith.acousticmonitor.detect.WindowVerdict

/**
 * Where a cue died.
 *
 * Written for a specific unanswered question: someone knocked on the table
 * beside the phone and no event was recorded, and a single glance at the UI
 * could not tell whether YAMNet never scored the knock or the decision layer
 * rejected a score it did produce. Those need opposite fixes — one is a model
 * that cannot hear the event, the other is a bar set too high — and guessing
 * between them is how tuning goes wrong.
 *
 * So every group's best moment is kept, with the raw score, the weighted score
 * and the bar it was tested against. A group that never appears was never
 * detected; a group with a healthy raw score and a weighted score under the
 * bar was detected and rejected.
 *
 * Diagnostic only. Nothing here feeds a decision.
 */
class NearMissTracker(private val windowMs: Long = 120_000) {

    data class Peak(
        val group: CueGroup,
        val label: String,
        /** What the model scored, before any weighting. */
        val raw: Float,
        /** After the group weight and any vocal-strain boost. */
        val weighted: Float,
        /** The bar in force at that moment, after demotion. */
        val threshold: Float,
        val atEpochMs: Long,
    ) {
        val crossed: Boolean get() = weighted >= threshold
        /** How far short it fell. Negative once it crossed. */
        val shortfall: Float get() = threshold - weighted
    }

    private val _peaks = MutableStateFlow<Map<CueGroup, Peak>>(emptyMap())
    val peaks: StateFlow<Map<CueGroup, Peak>> = _peaks.asStateFlow()

    fun accept(verdict: WindowVerdict, nowEpochMs: Long) {
        _peaks.update { current ->
            val kept = current.filterValues { nowEpochMs - it.atEpochMs <= windowMs }.toMutableMap()
            verdict.groups.forEach { score ->
                if (score.raw <= 0f) return@forEach
                val existing = kept[score.group]
                // Ranked on the raw score: the question is what the model
                // heard, and a weighted comparison would hide a strong
                // detection that a low group weight pushed down.
                if (existing == null || score.raw > existing.raw) {
                    kept[score.group] = Peak(
                        group = score.group,
                        label = score.label,
                        raw = score.raw,
                        weighted = score.weighted,
                        threshold = verdict.effectiveMediumThreshold,
                        atEpochMs = nowEpochMs,
                    )
                }
            }
            kept
        }
    }

    fun clear() {
        _peaks.value = emptyMap()
    }
}
