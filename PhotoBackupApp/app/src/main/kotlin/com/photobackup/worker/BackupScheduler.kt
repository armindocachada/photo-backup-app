package com.photobackup.worker

import android.content.Context
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
     */
    fun runBackupNow() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val backupRequest = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(constraints)
            .build()

        // Use KEEP policy to prevent cancelling an already running backup
        workManager.enqueueUniqueWork(
            "${BackupWorker.WORK_NAME}_immediate",
            ExistingWorkPolicy.KEEP,
            backupRequest
        )
    }

    /**
     * Cancel any running backup.
     */
    fun cancelBackup() {
        workManager.cancelUniqueWork("${BackupWorker.WORK_NAME}_immediate")
    }
}
