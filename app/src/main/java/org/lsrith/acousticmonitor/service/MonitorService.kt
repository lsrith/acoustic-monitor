// SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0
// Copyright 2026 Samrith Ly

package org.lsrith.acousticmonitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import org.lsrith.acousticmonitor.AcousticMonitorApplication
import org.lsrith.acousticmonitor.MainActivity
import org.lsrith.acousticmonitor.R

/**
 * Keeps capture and inference alive while the app is backgrounded.
 *
 * Android requires a microphone-typed foreground service for this; the
 * persistent notification is not optional and is arguably a feature, since the
 * household can see that monitoring is running.
 */
class MonitorService : LifecycleService() {

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.action) {
            ACTION_STOP -> {
                stopEngineAndSelf()
                return START_NOT_STICKY
            }
        }

        createChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val app = application as AcousticMonitorApplication
        app.engine.start(lifecycleScope)
        // Started after the engine so the timeline exists to read.
        app.reasoning.start(lifecycleScope)
        return START_STICKY
    }

    override fun onDestroy() {
        (application as AcousticMonitorApplication).reasoning.stop()
        (application as AcousticMonitorApplication).engine.stop()
        super.onDestroy()
    }

    private fun stopEngineAndSelf() {
        (application as AcousticMonitorApplication).reasoning.stop()
        (application as AcousticMonitorApplication).engine.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.channel_monitoring),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.channel_monitoring_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, MonitorService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_monitoring_title))
            .setContentText(getString(R.string.notif_monitoring_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(open)
            .addAction(0, getString(R.string.action_stop), stop)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "acoustic-monitor_monitoring"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "org.lsrith.acousticmonitor.STOP"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, MonitorService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, MonitorService::class.java).setAction(ACTION_STOP))
        }
    }
}
