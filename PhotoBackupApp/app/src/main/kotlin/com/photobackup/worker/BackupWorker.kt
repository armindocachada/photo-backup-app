package com.photobackup.worker

import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.photobackup.PhotoBackupApplication
import com.photobackup.R
import com.photobackup.data.repository.BackupRepository
import com.photobackup.data.repository.BackupResult
import com.photobackup.network.ServerDiscovery
import com.photobackup.network.WifiConnectionState
import com.photobackup.network.WifiStateMonitor
import com.photobackup.util.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first

@HiltWorker
class BackupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val backupRepository: BackupRepository,
    private val serverDiscovery: ServerDiscovery,
    private val wifiStateMonitor: WifiStateMonitor,
    private val preferencesManager: PreferencesManager
) : CoroutineWorker(context, params) {

    companion object {
        const val WORK_NAME = "photo_backup_work"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result {
        // Check if we're on home WiFi
        val settings = preferencesManager.getSettingsSnapshot()
        wifiStateMonitor.setHomeNetworks(settings.homeSSIDs)

        if (wifiStateMonitor.wifiState.value !is WifiConnectionState.ConnectedToHome) {
            // Not on home WiFi, retry later
            return Result.retry()
        }

        // Check if API key is configured
        if (settings.apiKey.isEmpty()) {
            return Result.failure()
        }

        // Discover server
        val serverInfo = if (settings.serverHost.isNotEmpty()) {
            serverDiscovery.verifyServer(
                settings.serverHost,
                settings.serverPort.toIntOrNull() ?: 8080
            )
        } else {
            serverDiscovery.discoverServer()
        }

        if (serverInfo == null) {
            // Server not found, retry later
            return Result.retry()
        }

        // Configure repository with server
        backupRepository.setServer(serverInfo, settings.apiKey)

        // Verify connection
        if (!backupRepository.verifyConnection(settings.apiKey)) {
            return Result.retry()
        }

        // Get files to backup
        val filesToBackup = backupRepository.getFilesToBackup(
            includeImages = settings.backupPhotos,
            includeVideos = settings.backupVideos
        )

        if (filesToBackup.isEmpty()) {
            return Result.success()
        }

        // Check with server which files are really missing
        val missingFiles = backupRepository.checkFilesWithServer(
            filesToBackup,
            settings.apiKey
        )

        if (missingFiles.isEmpty()) {
            return Result.success()
        }

        // Set foreground for long-running operation
        setForeground(createForegroundInfo(missingFiles.size, 0, ""))

        var successCount = 0
        var failCount = 0

        for ((index, file) in missingFiles.withIndex()) {
            // Check if still on home WiFi
            if (wifiStateMonitor.wifiState.value !is WifiConnectionState.ConnectedToHome) {
                // Lost WiFi connection
                break
            }

            // Update notification
            setForeground(createForegroundInfo(
                total = missingFiles.size,
                current = index + 1,
                fileName = file.displayName
            ))

            // Upload file
            when (backupRepository.uploadFile(file, settings.apiKey)) {
                is BackupResult.Success -> successCount++
                is BackupResult.AlreadyExists -> successCount++
                is BackupResult.Error -> failCount++
            }
        }

        // Show completion notification
        showCompletionNotification(successCount, failCount)

        return if (failCount > 0) Result.retry() else Result.success()
    }

    private fun createForegroundInfo(
        total: Int,
        current: Int,
        fileName: String
    ): ForegroundInfo {
        val title = applicationContext.getString(R.string.notification_title_backup)
        val text = if (current > 0) {
            applicationContext.getString(
                R.string.notification_text_progress,
                fileName,
                current,
                total
            )
        } else {
            "Preparing backup..."
        }

        val notification = NotificationCompat.Builder(
            applicationContext,
            PhotoBackupApplication.CHANNEL_BACKUP
        )
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setProgress(total, current, current == 0)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun showCompletionNotification(successCount: Int, failCount: Int) {
        val notificationManager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val title = applicationContext.getString(R.string.notification_title_complete)
        val text = if (failCount > 0) {
            "$successCount files backed up, $failCount failed"
        } else {
            "$successCount files backed up"
        }

        val notification = NotificationCompat.Builder(
            applicationContext,
            PhotoBackupApplication.CHANNEL_BACKUP
        )
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }
}
