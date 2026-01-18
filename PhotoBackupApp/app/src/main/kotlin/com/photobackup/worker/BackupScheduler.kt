package com.photobackup.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val workManager = WorkManager.getInstance(context)

    /**
     * Schedule periodic backup every 15 minutes when on WiFi.
     */
    fun schedulePeriodicBackup() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)
            .setRequiresBatteryNotLow(true)
            .build()

        val backupRequest = PeriodicWorkRequestBuilder<BackupWorker>(
            repeatInterval = 15,
            repeatIntervalTimeUnit = TimeUnit.MINUTES
        )
            .setConstraints(constraints)
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                1,
                TimeUnit.MINUTES
            )
            .build()

        workManager.enqueueUniquePeriodicWork(
            BackupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            backupRequest
        )
    }

    /**
     * Cancel periodic backup.
     */
    fun cancelPeriodicBackup() {
        workManager.cancelUniqueWork(BackupWorker.WORK_NAME)
    }

    /**
     * Run backup immediately.
     * Uses the same work name as periodic backup to ensure only one backup runs at a time.
     */
    fun runBackupNow() {
        Log.d(TAG, "Running backup now")

        // Start the foreground service to keep the backup alive when app is closed
        startForegroundService()

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .build()

        // Cancel periodic work first to avoid conflicts, then run immediate
        // Using a separate name for immediate work but observing both in UI
        workManager.enqueueUniqueWork(
            IMMEDIATE_WORK_NAME,
            ExistingWorkPolicy.KEEP,
            backupRequest
        )
    }

    /**
     * Start the foreground service to keep backup running when app is closed.
     */
    private fun startForegroundService() {
        val intent = Intent(context, BackupForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.startService(intent)
            }
            Log.d(TAG, "Foreground service started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start foreground service: ${e.message}")
        }
    }

    /**
     * Stop the foreground service.
     */
    fun stopForegroundService() {
        val intent = Intent(context, BackupForegroundService::class.java)
        context.stopService(intent)
        Log.d(TAG, "Foreground service stopped")
    }

    /**
     * Cancel any running immediate backup.
     */
    fun cancelBackup() {
        workManager.cancelUniqueWork(IMMEDIATE_WORK_NAME)
    }

    /**
     * Check if any backup (periodic or immediate) is currently running.
     */
    fun isBackupRunning(): Boolean {
        val periodicWork = workManager.getWorkInfosForUniqueWork(BackupWorker.WORK_NAME).get()
        val immediateWork = workManager.getWorkInfosForUniqueWork(IMMEDIATE_WORK_NAME).get()

        return periodicWork.any { it.state == androidx.work.WorkInfo.State.RUNNING } ||
               immediateWork.any { it.state == androidx.work.WorkInfo.State.RUNNING }
    }

    companion object {
        private const val TAG = "BackupScheduler"
        const val IMMEDIATE_WORK_NAME = "${BackupWorker.WORK_NAME}_immediate"
    }
}
