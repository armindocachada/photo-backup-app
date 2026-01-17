package com.photobackup.worker

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service declaration for backup operations.
 * The actual work is done by BackupWorker, this service is just
 * required for the foreground service type declaration in manifest.
 */
class BackupForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // This service is just for manifest declaration
        // Actual work is done through WorkManager
        stopSelf()
        return START_NOT_STICKY
    }
}
