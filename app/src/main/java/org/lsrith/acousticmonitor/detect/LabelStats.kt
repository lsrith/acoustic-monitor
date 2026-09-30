// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import org.lsrith.acousticmonitor.data.AlertEventEntity

/** Review outcomes for one cue group. */
data class CuePrecision(
    val cueGroup: String,
    val confirmed: Int,
    val falsePositive: Int,
) {
    val reviewed: Int get() = confirmed + falsePositive

    /** Null until something has been reviewed — an unknown rate, not a zero one. */
    val precision: Float? get() = if (reviewed == 0) null else confirmed.toFloat() / reviewed
}

data class LabelSummary(
    val total: Int,
    val confirmed: Int,
    val falsePositive: Int,
    val unreviewed: Int,
    val overallPrecision: Float?,
    val byCue: List<CuePrecision>,
) {
    val reviewed: Int get() = confirmed + falsePositive
}


object LabelStats {

    fun summarise(events: List<AlertEventEntity>): LabelSummary {
        var confirmed = 0
        var falsePositive = 0
        var unreviewed = 0

        val perCue = HashMap<String, IntArray>()

        for (event in events) {
            val counts = perCue.getOrPut(event.peakCueGroup) { IntArray(2) }
            when (event.review) {
                ReviewLabel.CONFIRMED.name -> {
                    confirmed++
                    counts[0]++
                }
                ReviewLabel.FALSE_POSITIVE.name -> {
                    falsePositive++
                    counts[1]++
                }
                else -> unreviewed++
            }
        }

        val reviewed = confirmed + falsePositive
        return LabelSummary(
            total = events.size,
            confirmed = confirmed,
            falsePositive = falsePositive,
            unreviewed = unreviewed,
            overallPrecision = if (reviewed == 0) null else confirmed.toFloat() / reviewed,
            byCue = perCue
                .map { (group, counts) -> CuePrecision(group, counts[0], counts[1]) }
                .sortedByDescending { it.reviewed },
        )
    }
}
