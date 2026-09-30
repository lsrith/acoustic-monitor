// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * App-local store for alert history. Version 1 has no prior schema to
 * migrate from, so there is deliberately no destructive-migration fallback:
 * a mismatch here should fail loudly rather than silently drop history.
 */
@Database(entities = [AlertEventEntity::class], version = 1, exportSchema = true)
abstract class AcousticMonitorDatabase : RoomDatabase() {

    abstract fun alertEvents(): AlertEventDao

    companion object {
        fun create(context: Context): AcousticMonitorDatabase =
            Room.databaseBuilder(context.applicationContext, AcousticMonitorDatabase::class.java, "acoustic-monitor.db").build()
    }
}
