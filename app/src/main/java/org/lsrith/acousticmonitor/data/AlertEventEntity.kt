// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A single detected distress episode, persisted so it survives process death
 * and can be reviewed after the fact. Columns capture the fused decision
 * (severity, peak score, cue) plus the individual media/speech contributions,
 * since the fusion weighting may need to be re-examined later against real
 * cases.
 */
@Entity(tableName = "alert_events")
data class AlertEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long,
    val severity: String, // "MEDIUM" or "HIGH"
    val peakFusedScore: Float,
    val peakCueGroup: String, // e.g. "SCREAM"
    val peakCueLabel: String, // e.g. "Screaming"
    val cueSummary: String, // human-readable, e.g. "Screaming 0.82, Crying 0.61"
    val mediaScoreAtPeak: Float,
    val speechScoreAtPeak: Float,
    val windowCount: Int,
    val snippetPath: String?,
    val review: String = "UNREVIEWED", // "UNREVIEWED" | "CONFIRMED" | "FALSE_POSITIVE"
    val note: String? = null,
)
