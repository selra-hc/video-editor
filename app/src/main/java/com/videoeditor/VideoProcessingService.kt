package com.videoeditor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Foreground service that shows a persistent progress notification during video processing.
 *
 * Running as a foreground service prevents Android from killing the process due to memory
 * pressure while a long encode/mux operation is in progress.
 *
 * Lifecycle (from MainActivity):
 *  1. [startProcessing] — starts the service, posts the initial notification.
 *  2. [updateProgress]  — updates the notification progress bar (call from any thread).
 *  3. [stopProcessing]  — removes the notification and stops the service.
 *
 * The service stays in the same process as the activity, so the companion-object calls are
 * simple in-process method invocations (no IPC overhead).
 */
class VideoProcessingService : Service() {

    // ── Notification constants ────────────────────────────────────────────────

    companion object {
        private const val CHANNEL_ID      = "video_processing"
        private const val NOTIFICATION_ID = 1001

        /** Start the foreground service and show the initial indeterminate notification. */
        fun startProcessing(context: Context, label: String) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_LABEL, label)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Update the progress bar in the notification (0-100). Pass -1 for indeterminate. */
        fun updateProgress(context: Context, percent: Int, label: String) {
            val intent = Intent(context, VideoProcessingService::class.java).apply {
                action = ACTION_PROGRESS
                putExtra(EXTRA_LABEL, label)
                putExtra(EXTRA_PERCENT, percent)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        /** Remove the notification and stop the service. */
        fun stopProcessing(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, VideoProcessingService::class.java).apply { action = ACTION_STOP },
            )
        }

        private const val ACTION_START    = "com.videoeditor.START"
        private const val ACTION_PROGRESS = "com.videoeditor.PROGRESS"
        private const val ACTION_STOP     = "com.videoeditor.STOP"
        private const val EXTRA_LABEL     = "label"
        private const val EXTRA_PERCENT   = "percent"
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    private lateinit var nm: NotificationManager

    override fun onCreate() {
        super.onCreate()
        nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val label = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.processing_video)
                startForeground(NOTIFICATION_ID, buildNotification(label, -1))
            }
            ACTION_PROGRESS -> {
                val label   = intent.getStringExtra(EXTRA_LABEL) ?: getString(R.string.processing_video)
                val percent = intent.getIntExtra(EXTRA_PERCENT, -1)
                nm.notify(NOTIFICATION_ID, buildNotification(label, percent))
            }
            ACTION_STOP -> {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ── Notification helpers ──────────────────────────────────────────────────

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video Processing",
            NotificationManager.IMPORTANCE_LOW,   // silent, but visible in status bar
        ).apply {
            description = "Progress of ongoing video processing operations"
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    private fun buildNotification(label: String, percent: Int): Notification {
        // Tapping the notification brings the app back to the foreground.
        val tapIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE,
        )

        val indeterminate = percent < 0
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_apply)          // reuse the check-circle icon
            .setContentTitle(getString(R.string.app_name))
            .setContentText(if (indeterminate) label else "$label  $percent%")
            .setProgress(100, if (indeterminate) 0 else percent, indeterminate)
            .setOngoing(true)                            // user cannot swipe it away
            .setOnlyAlertOnce(true)                      // no repeated sounds/vibrations
            .setContentIntent(tapIntent)
            .build()
    }
}
