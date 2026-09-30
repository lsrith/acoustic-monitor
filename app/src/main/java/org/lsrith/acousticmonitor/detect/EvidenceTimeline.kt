// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One thing a detector noticed, at a moment. */
data class EvidenceEntry(
    val atEpochMs: Long,
    val source: String,
    val detail: String,
)

/**
 * A rolling log of what the cheap detectors found, for the reasoning layer.
 *
 * The language model never sees audio — it sees this. Two reasons that is the
 * right unit rather than a single moment's scores:
 *
 * The cases that most need reasoning are **temporal**. Masking is "the
 * television jumped, *then* a cry appeared". Sustained escalation is "this
 * never recovered over twenty minutes". Neither is visible in an instant.
 *
 * And this is the auditable artefact. A reviewer can disagree with the
 * model's conclusion while the evidence it reasoned from stays fixed — which
 * is the only way a non-deterministic component survives a governance
 * requirement written for versioned thresholds.
 *
 * Deliberately sparse: entries are added on change, not per window, so an hour
 * of an empty room costs almost nothing.
 */
class EvidenceTimeline(private val capacity: Int = 400) {

    private val entries = ArrayDeque<EvidenceEntry>()

    @Synchronized
    fun add(atEpochMs: Long, source: String, detail: String) {
        entries.addLast(EvidenceEntry(atEpochMs, source, detail))
        while (entries.size > capacity) entries.removeFirst()
    }

    /** Entries within [windowSeconds] of [nowEpochMs], oldest first. */
    @Synchronized
    fun window(nowEpochMs: Long, windowSeconds: Double): List<EvidenceEntry> {
        val cutoff = nowEpochMs - (windowSeconds * 1000).toLong()
        return entries.filter { it.atEpochMs >= cutoff }
    }

    @Synchronized
    fun clear() = entries.clear()

    /**
     * Renders a window as the text the model reasons over.
     *
     * Times are relative to the window start rather than wall-clock, because
     * the ordering and the gaps are what carry meaning — "media rose, then two
     * seconds later a cry" is the signal, not the hour of the evening.
     */
    fun render(nowEpochMs: Long, windowSeconds: Double): String {
        val window = window(nowEpochMs, windowSeconds)
        if (window.isEmpty()) return ""
        val start = window.first().atEpochMs
        return buildString {
            appendLine("Window: ${STAMP.format(Date(start))}, ${windowSeconds.toInt()} s")
            window.forEach { e ->
                val offset = (e.atEpochMs - start) / 1000.0
                appendLine("T+%05.1f  %-12s %s".format(Locale.US, offset, e.source, e.detail))
            }
        }
    }

    /** True when a window holds nothing worth spending a generation on. */
    fun isEmpty(nowEpochMs: Long, windowSeconds: Double): Boolean =
        window(nowEpochMs, windowSeconds).isEmpty()

    private companion object {
        val STAMP = SimpleDateFormat("HH:mm:ss", Locale.US)
    }
}
