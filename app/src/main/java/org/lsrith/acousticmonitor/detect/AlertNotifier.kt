// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.detect

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.lsrith.acousticmonitor.MainActivity
import org.lsrith.acousticmonitor.R

/**
 * Raises a local notification when the decision engine records an event.
 *
 * Local only — there is no cloud leg and no second recipient. What this
 * demonstrates is that the on-device path can reach a human at all; who that
 * human is, and how, is out of scope for the spike.
 */
class AlertNotifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.channel_alerts),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.channel_alerts_desc)
            enableVibration(true)
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /**
     * @param eventId row id, used as the notification id so an event updates
     *   rather than stacking.
     */
    fun notifyEvent(eventId: Long, event: DetectedEvent) {
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val title = when (event.severity) {
            Severity.HIGH -> context.getString(R.string.alert_high_title)
            else -> context.getString(R.string.alert_medium_title)
        }
        val seconds = ((event.endedAtEpochMs - event.startedAtEpochMs) / 1000).coerceAtLeast(1)
        val body = "${event.peakCueLabel} · ${"%.2f".format(event.peakFusedScore)} · ${seconds}s"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText("${event.cueSummary}\n$body"))
            .build()

        try {
            manager.notify(eventId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }

    /**
     * A microphone that has stopped hearing the room is its own alert.
     *
     * Phrased around the device rather than the household: nobody should read
     * this as a claim about what is happening in the home, only that the system
     * has stopped being able to tell.
     */
    fun notifyIntegrity(eventId: Long, state: String, severity: String) {
        if (!manager.areNotificationsEnabled()) return

        val open = PendingIntent.getActivity(
            context,
            eventId.toInt(),
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val body = when (state.uppercase()) {
            "MUTED" -> "No audio is reaching the app. The microphone may be disabled or taken by another app."
            else -> "The microphone may be covered or obstructed."
        }
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle("Monitoring degraded")
            .setContentText(body)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
            .setPriority(
                if (severity == "HIGH") NotificationCompat.PRIORITY_HIGH
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setAutoCancel(true)
            .setContentIntent(open)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .build()

        try {
            manager.notify(eventId.toInt(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and the call.
        }
    }

    companion object {
        private const val CHANNEL_ID = "acoustic-monitor_alerts"
    }
}
