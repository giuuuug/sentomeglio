package com.sentomeglio.app

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

class AudioService : Service() {

    companion object {
        private const val CHANNEL_ID = "sentomeglio_audio"
        private const val NOTIF_ID   = 1

        private const val ACTION_SHOW = "com.sentomeglio.app.SHOW"
        private const val ACTION_STOP = "com.sentomeglio.app.STOP"
        private const val ACTION_DISMISS = "com.sentomeglio.app.DISMISS"

        @Volatile var isRunning = false
            private set

        // Call after NativeBridge.startAudioEngine() succeeds — posts the notification.
        fun show(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, AudioService::class.java).apply { action = ACTION_SHOW }
            )
        }

        // Call when the user stops from the app UI — engine already stopped by caller.
        fun dismiss(context: Context) {
            context.startService(
                Intent(context, AudioService::class.java).apply { action = ACTION_DISMISS }
            )
        }
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                startForeground(NOTIF_ID, buildNotification())
                isRunning = true
            }
            ACTION_STOP -> {
                // Triggered by the notification Stop button — engine still running.
                NativeBridge.stopAudioEngine()
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
            ACTION_DISMISS -> {
                // Triggered by app UI stop — engine already stopped by the manager.
                isRunning = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // User swiped the app away from recents — kill everything.
        NativeBridge.stopAudioEngine()
        isRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    private fun buildNotification(): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopPi = PendingIntent.getService(
            this, 0,
            Intent(this, AudioService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SentoMeglio")
            .setContentText("Speech enhancement attivo")
            .setSmallIcon(R.drawable.ic_mic)
            .setContentIntent(openPi)
            .addAction(0, "Stop", stopPi)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Audio Processing", NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Mantiene attivo il processamento audio in background"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }
}
