// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.monitor

import android.content.Context

/**
 * Persists the learned room across app restarts and updates.
 *
 * Calibration is a deliberate act — someone judged the conditions
 * representative and spent five minutes on it — so it must not evaporate
 * because the app was reinstalled with a new build. Survives `adb install -r`
 * for the same reason model weights do; an uninstall clears it, which is
 * correct, since a fresh install has no claim to know the room.
 */
class CalibrationStore(context: Context) {

    private val prefs = context.getSharedPreferences("calibration", Context.MODE_PRIVATE)

    data class Saved(
        val micBaselineDbfs: Float,
        val micBaselineHfRatio: Float,
        val ambientBaselineDbfs: Float,
        /** Observation already accumulated, so confidence resumes rather than restarts. */
        val ageSeconds: Double,
        val completedAtEpochMs: Long,
    )

    fun save(saved: Saved) {
        prefs.edit()
            .putFloat(KEY_MIC_DB, saved.micBaselineDbfs)
            .putFloat(KEY_MIC_HF, saved.micBaselineHfRatio)
            .putFloat(KEY_AMBIENT_DB, saved.ambientBaselineDbfs)
            .putFloat(KEY_AGE, saved.ageSeconds.toFloat())
            .putLong(KEY_COMPLETED_AT, saved.completedAtEpochMs)
            .apply()
    }

    /** Null when the room has never been calibrated on this install. */
    fun load(): Saved? {
        val completedAt = prefs.getLong(KEY_COMPLETED_AT, 0L)
        if (completedAt == 0L) return null
        return Saved(
            micBaselineDbfs = prefs.getFloat(KEY_MIC_DB, 0f),
            micBaselineHfRatio = prefs.getFloat(KEY_MIC_HF, 0f),
            ambientBaselineDbfs = prefs.getFloat(KEY_AMBIENT_DB, 0f),
            ageSeconds = prefs.getFloat(KEY_AGE, 0f).toDouble(),
            completedAtEpochMs = completedAt,
        )
    }

    fun clear() = prefs.edit().clear().apply()

    private companion object {
        const val KEY_MIC_DB = "mic_baseline_dbfs"
        const val KEY_MIC_HF = "mic_baseline_hf"
        const val KEY_AMBIENT_DB = "ambient_baseline_dbfs"
        const val KEY_AGE = "age_seconds"
        const val KEY_COMPLETED_AT = "completed_at"
    }
}
