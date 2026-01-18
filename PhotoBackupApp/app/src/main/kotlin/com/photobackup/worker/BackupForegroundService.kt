package com.photobackup.worker

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.photobackup.PhotoBackupApplication

/**
 * Foreground service to keep backup running when app is closed.
 *
 * This service ensures the backup worker continues running even when
 * the user swipes the app away from recent apps.
 */
class BackupForegroundService : Service() {

    companion object {
        private const val TAG = "BackupForegroundService"
        private const val NOTIFICATION_ID = 1002
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "Service started")

        // Start as foreground service immediately
        val notification = createNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        // Return START_STICKY so the service restarts if killed
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "Task removed (app swiped away) - backup will continue")

        // The WorkManager job should continue running
        // This callback just logs that the app was swiped away
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, PhotoBackupApplication.CHANNEL_BACKUP)
            .setContentTitle("Photo Backup")
            .setContentText("Backup is running in background...")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}
