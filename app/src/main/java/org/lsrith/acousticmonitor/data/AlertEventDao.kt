// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** CRUD surface for reviewed and unreviewed alert history. */
@Dao
interface AlertEventDao {

    @Insert
    suspend fun insert(event: AlertEventEntity): Long

    @Query("SELECT * FROM alert_events ORDER BY startedAtEpochMs DESC")
    fun observeAll(): Flow<List<AlertEventEntity>>

    @Query("SELECT * FROM alert_events WHERE id = :id")
    fun observeById(id: Long): Flow<AlertEventEntity?>

    @Query("UPDATE alert_events SET review = :review WHERE id = :id")
    suspend fun setReview(id: Long, review: String)

    @Query("UPDATE alert_events SET note = :note WHERE id = :id")
    suspend fun setNote(id: Long, note: String?)

    @Query("SELECT COUNT(*) FROM alert_events WHERE review = :review")
    fun observeCountByReview(review: String): Flow<Int>

    @Query("DELETE FROM alert_events")
    suspend fun deleteAll()
}
