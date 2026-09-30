// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import kotlinx.coroutines.flow.Flow
import org.lsrith.acousticmonitor.audio.AudioRingBuffer
import org.lsrith.acousticmonitor.data.AlertEventDao
import org.lsrith.acousticmonitor.data.AlertEventEntity

/** How a human judged an event after the fact. */
enum class ReviewLabel { UNREVIEWED, CONFIRMED, FALSE_POSITIVE }


class AlertRepository(
    private val dao: AlertEventDao,
    private val snippets: SnippetStore,
    private val notifier: AlertNotifier,
) {

    val events: Flow<List<AlertEventEntity>> = dao.observeAll()
    val unreviewedCount: Flow<Int> = dao.observeCountByReview(ReviewLabel.UNREVIEWED.name)

    fun observe(id: Long): Flow<AlertEventEntity?> = dao.observeById(id)

    /**
     * Persists a closed event, writes its audio snippet and raises the
     * notification. Returns the new row id.
     */
    suspend fun record(event: DetectedEvent, ring: AudioRingBuffer): Long {
        val snippetPath = snippets.write(ring, event)
        val id = dao.insert(
            AlertEventEntity(
                startedAtEpochMs = event.startedAtEpochMs,
                endedAtEpochMs = event.endedAtEpochMs,
                severity = event.severity.name,
                peakFusedScore = event.peakFusedScore,
                peakCueGroup = event.peakCueGroup.name,
                peakCueLabel = event.peakCueLabel,
                cueSummary = event.cueSummary,
                mediaScoreAtPeak = event.mediaScoreAtPeak,
                speechScoreAtPeak = event.speechScoreAtPeak,
                windowCount = event.windowCount,
                snippetPath = snippetPath,
            )
        )
        notifier.notifyEvent(id, event)
        return id
    }

    /**
     * Records a loss of microphone integrity as an event in its own right.
     *
     * A covered or silenced microphone reports a calm evening, and a calm
     * evening is exactly what a real incident would be mistaken for. Absence of
     * signal has to enter the alert stream rather than sit as a status nobody
     * is watching.
     *
     * No audio snippet: there is, by definition, nothing worth keeping.
     */
    suspend fun recordIntegrityFailure(
        state: String,
        severity: String,
        secondsInState: Double,
        levelDbfs: Float,
        baselineDbfs: Float,
        trebleShare: Float,
        baselineTrebleShare: Float,
        nowEpochMs: Long,
    ): Long {
        val id = dao.insert(
            AlertEventEntity(
                startedAtEpochMs = nowEpochMs - (secondsInState * 1000).toLong(),
                endedAtEpochMs = nowEpochMs,
                severity = severity,
                peakFusedScore = 1f,
                peakCueGroup = INTEGRITY_GROUP,
                peakCueLabel = "Microphone ${state.lowercase()}",
                cueSummary = "level %.1f dBFS vs %.1f baseline · treble %.3f vs %.3f"
                    .format(levelDbfs, baselineDbfs, trebleShare, baselineTrebleShare),
                mediaScoreAtPeak = 0f,
                speechScoreAtPeak = 0f,
                windowCount = 0,
                snippetPath = null,
            )
        )
        notifier.notifyIntegrity(id, state, severity)
        return id
    }

    suspend fun setReview(id: Long, label: ReviewLabel) = dao.setReview(id, label.name)

    suspend fun setNote(id: Long, note: String?) = dao.setNote(id, note?.takeIf { it.isNotBlank() })

    /** Clears history and the audio it referenced. */
    suspend fun clearAll(events: List<AlertEventEntity>) {
        events.forEach { snippets.delete(it.snippetPath) }
        dao.deleteAll()
    }

    companion object {
        /** Distinguishes integrity events from acoustic cues in the same table. */
        const val INTEGRITY_GROUP = "INTEGRITY"
    }
}
