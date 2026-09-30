// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.metrics

import kotlin.math.sqrt


class LatencyTracker(
    private val capacity: Int = 256,
    private val budgetMs: Double = 0.0,
) {

    private val samples = DoubleArray(capacity)
    private var count = 0
    private var index = 0
    private var overBudget = 0
    private var totalSeen = 0L

    @Synchronized
    fun record(millis: Double) {
        samples[index] = millis
        index = (index + 1) % capacity
        if (count < capacity) count++
        totalSeen++
        // Counted over everything ever seen, not just the retained window: a
        // stall that has scrolled out of the ring still happened, and a
        // compliance figure that quietly forgets its breaches is worthless.
        if (budgetMs > 0 && millis > budgetMs) overBudget++
    }

    @Synchronized
    fun snapshot(): Stats {
        if (count == 0) return Stats(budgetMs = budgetMs)
        val sorted = samples.copyOf(count).sortedArray()
        val mean = sorted.average()
        val variance = sorted.sumOf { (it - mean) * (it - mean) } / sorted.size
        return Stats(
            count = count,
            min = sorted.first(),
            p50 = percentile(sorted, 0.50),
            p90 = percentile(sorted, 0.90),
            p95 = percentile(sorted, 0.95),
            p99 = percentile(sorted, 0.99),
            max = sorted.last(),
            mean = mean,
            stdDev = sqrt(variance),
            last = samples[(index - 1 + capacity) % capacity],
            budgetMs = budgetMs,
            overBudget = overBudget,
            totalSeen = totalSeen,
        )
    }

    @Synchronized
    fun reset() {
        count = 0
        index = 0
        overBudget = 0
        totalSeen = 0
    }

    private fun percentile(sorted: DoubleArray, q: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val pos = (q * (sorted.size - 1)).toInt().coerceIn(0, sorted.size - 1)
        return sorted[pos]
    }

    data class Stats(
        val count: Int = 0,
        val min: Double = 0.0,
        val p50: Double = 0.0,
        val p90: Double = 0.0,
        val p95: Double = 0.0,
        val p99: Double = 0.0,
        val max: Double = 0.0,
        val mean: Double = 0.0,
        /** Spread. A pipeline that jitters is harder to reason about than a slow one. */
        val stdDev: Double = 0.0,
        val last: Double = 0.0,
        val budgetMs: Double = 0.0,
        val overBudget: Int = 0,
        val totalSeen: Long = 0,
    ) {
        /** Fraction of everything measured that met the budget. Null when none is set. */
        val withinBudget: Double?
            get() = if (budgetMs <= 0.0 || totalSeen == 0L) null
            else (totalSeen - overBudget).toDouble() / totalSeen
    }
}
