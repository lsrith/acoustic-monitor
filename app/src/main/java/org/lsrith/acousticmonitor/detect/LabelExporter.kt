// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import android.content.Context
import android.util.Log
import org.lsrith.acousticmonitor.data.AlertEventEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes reviewed events out as CSV for offline analysis and retraining.
 *
 * **Metadata only — no audio.** Snippets stay on the device. Moving recordings
 * of a household off the handset is exactly the step the whole privacy design
 * exists to control, and it needs a governed, authorised process rather than a
 * button in a diagnostic app. The CSV names each snippet so a properly
 * authorised export could collect them later.
 */
class LabelExporter(private val context: Context) {

    private val dir = File(context.filesDir, "exports").apply { mkdirs() }

    /** @return the written file, or null if there was nothing reviewed to export. */
    fun export(events: List<AlertEventEntity>, lexiconVersion: String): File? {
        val reviewed = events.filter { it.review != ReviewLabel.UNREVIEWED.name }
        if (reviewed.isEmpty()) return null

        val file = File(dir, "acoustic-monitor-labels-${STAMP.format(Date())}.csv")
        return try {
            file.bufferedWriter().use { out ->
                out.appendLine(
                    listOf(
                        "id", "started_iso", "ended_iso", "duration_s", "severity", "review",
                        "peak_fused_score", "peak_cue_group", "peak_cue_label", "cue_summary",
                        "media_score", "speech_score", "window_count", "snippet_file",
                        "lexicon_version", "note",
                    ).joinToString(",")
                )
                for (e in reviewed) {
                    out.appendLine(
                        listOf(
                            e.id.toString(),
                            ISO.format(Date(e.startedAtEpochMs)),
                            ISO.format(Date(e.endedAtEpochMs)),
                            ((e.endedAtEpochMs - e.startedAtEpochMs) / 1000).toString(),
                            e.severity,
                            e.review,
                            "%.4f".format(Locale.US, e.peakFusedScore),
                            e.peakCueGroup,
                            e.peakCueLabel,
                            e.cueSummary,
                            "%.4f".format(Locale.US, e.mediaScoreAtPeak),
                            "%.4f".format(Locale.US, e.speechScoreAtPeak),
                            e.windowCount.toString(),
                            e.snippetPath?.let { File(it).name }.orEmpty(),
                            lexiconVersion,
                            e.note.orEmpty(),
                        ).joinToString(",") { escape(it) }
                    )
                }
            }
            file
        } catch (t: Throwable) {
            Log.e(TAG, "export failed", t)
            null
        }
    }

    fun existing(): List<File> = dir.listFiles()?.sortedByDescending { it.lastModified() }.orEmpty()

    /** Quotes any field containing a separator, quote or newline, per RFC 4180. */
    private fun escape(value: String): String =
        if (value.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) {
            "\"" + value.replace("\"", "\"\"") + "\""
        } else value

    companion object {
        private const val TAG = "LabelExporter"
        private val STAMP = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
    }
}
