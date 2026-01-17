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

        val wifiState = wifiStateMonitor.wifiState.value
        val isOnHomeWifi = wifiState is WifiConnectionState.ConnectedToHome
        val isOnUnknownNetwork = wifiState is WifiConnectionState.ConnectedToOther &&
            (wifiState.ssid == "Unknown Network" || wifiState.ssid == "<unknown ssid>")
        val hasManualServer = settings.serverHost.isNotEmpty()

        // Allow backup if:
        // 1. On home WiFi, OR
        // 2. On unknown network with manual server configured and allowUnknownNetwork enabled
        val canBackup = isOnHomeWifi ||
            (isOnUnknownNetwork && hasManualServer && settings.allowUnknownNetwork)

        if (!canBackup) {
            android.util.Log.d("BackupWorker", "Cannot backup: wifiState=$wifiState, hasManualServer=$hasManualServer, allowUnknownNetwork=${settings.allowUnknownNetwork}")
            // Not on home WiFi, retry later
            return Result.retry()
        }

        android.util.Log.d("BackupWorker", "Starting backup: isOnHomeWifi=$isOnHomeWifi, isOnUnknownNetwork=$isOnUnknownNetwork")

        // Check if API key is configured
        if (settings.apiKey.isEmpty()) {
            return Result.failure()
        }

        // Discover server
        android.util.Log.d("BackupWorker", "Discovering server: host=${settings.serverHost}, port=${settings.serverPort}")
        val serverInfo = if (settings.serverHost.isNotEmpty()) {
            serverDiscovery.verifyServer(
                settings.serverHost,
                settings.serverPort.toIntOrNull() ?: 8080
            )
        } else {
            serverDiscovery.discoverServer()
        }

        if (serverInfo == null) {
            android.util.Log.e("BackupWorker", "Server not found, will retry later")
            return Result.retry()
        }
        android.util.Log.d("BackupWorker", "Server found: ${serverInfo.baseUrl}")

        // Configure repository with server
        backupRepository.setServer(serverInfo, settings.apiKey)

        // Verify connection
        android.util.Log.d("BackupWorker", "Verifying connection...")
        if (!backupRepository.verifyConnection(settings.apiKey)) {
            android.util.Log.e("BackupWorker", "Connection verification failed, will retry later")
            return Result.retry()
        }
        android.util.Log.d("BackupWorker", "Connection verified successfully")

        // Get files to backup (already sorted newest first by MediaRepository)
        android.util.Log.d("BackupWorker", "Getting files to backup (photos=${settings.backupPhotos}, videos=${settings.backupVideos})")
        val filesToBackup = backupRepository.getFilesToBackup(
            includeImages = settings.backupPhotos,
            includeVideos = settings.backupVideos
        )
        android.util.Log.d("BackupWorker", "Found ${filesToBackup.size} files to potentially backup (newest first)")

        if (filesToBackup.isEmpty()) {
            android.util.Log.d("BackupWorker", "No files to backup, completing successfully")
            return Result.success()
        }

        // Set foreground for long-running operation
        // We don't know exact count yet since we check each file individually
        setForeground(createForegroundInfo(filesToBackup.size, 0, "Starting..."))

        var successCount = 0
        var skippedCount = 0
        var failCount = 0
        var processedCount = 0

        // Process files one at a time from newest to oldest
        // This avoids computing all hashes upfront
        for ((index, file) in filesToBackup.withIndex()) {
            processedCount++

            // Check if still on acceptable WiFi
            val currentWifiState = wifiStateMonitor.wifiState.value
            val stillOnHomeWifi = currentWifiState is WifiConnectionState.ConnectedToHome
            val stillOnUnknownNetwork = currentWifiState is WifiConnectionState.ConnectedToOther &&
                (currentWifiState.ssid == "Unknown Network" || currentWifiState.ssid == "<unknown ssid>")
            val canContinue = stillOnHomeWifi ||
                (stillOnUnknownNetwork && hasManualServer && settings.allowUnknownNetwork)

            if (!canContinue) {
                // Lost WiFi connection
                android.util.Log.d("BackupWorker", "WiFi connection changed during backup, stopping: $currentWifiState")
                break
            }

            // Update notification with current file info
            setForeground(createForegroundInfo(
                total = filesToBackup.size,
                current = index + 1,
                fileName = file.displayName
            ))

            // Upload file - uploadFile handles hash computation and server-side dedup check
            android.util.Log.d("BackupWorker", "Processing file ${index + 1}/${filesToBackup.size}: ${file.displayName} (${file.size} bytes)")
            val result = backupRepository.uploadFile(file, settings.apiKey)
            when (result) {
                is BackupResult.Success -> {
                    android.util.Log.d("BackupWorker", "Upload success: ${file.displayName}")
                    successCount++
                }
                is BackupResult.AlreadyExists -> {
                    android.util.Log.d("BackupWorker", "Already backed up: ${file.displayName}")
                    skippedCount++
                }
                is BackupResult.Error -> {
                    android.util.Log.e("BackupWorker", "Upload failed: ${file.displayName} - ${result.message}")
                    failCount++
                    // Don't let too many failures derail the whole backup
                    if (failCount > 10) {
                        android.util.Log.e("BackupWorker", "Too many failures, stopping backup")
                        break
                    }
                }
            }

            // Log progress every 10 files
            if (processedCount % 10 == 0) {
                android.util.Log.d("BackupWorker", "Progress: $processedCount/${filesToBackup.size} processed, $successCount uploaded, $skippedCount skipped, $failCount failed")
            }
        }

        android.util.Log.d("BackupWorker", "Backup complete: $successCount uploaded, $skippedCount already backed up, $failCount failed")
        // Show completion notification
        showCompletionNotification(successCount, skippedCount, failCount)

        return if (failCount > 0 && successCount == 0) Result.retry() else Result.success()
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

    private fun showCompletionNotification(successCount: Int, skippedCount: Int, failCount: Int) {
        val notificationManager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val title = applicationContext.getString(R.string.notification_title_complete)
        val text = when {
            failCount > 0 -> "$successCount uploaded, $skippedCount skipped, $failCount failed"
            successCount > 0 -> "$successCount files backed up"
            skippedCount > 0 -> "All files already backed up"
            else -> "No files to backup"
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
