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

        // Get date range of all media to process month by month
        android.util.Log.d("BackupWorker", "Getting media date range (photos=${settings.backupPhotos}, videos=${settings.backupVideos})")
        val dateRange = backupRepository.getMediaDateRange(
            includeImages = settings.backupPhotos,
            includeVideos = settings.backupVideos
        )

        if (dateRange == null) {
            android.util.Log.d("BackupWorker", "No media files found, completing successfully")
            return Result.success()
        }

        val (oldestDate, newestDate) = dateRange
        android.util.Log.d("BackupWorker", "Media date range: oldest=$oldestDate, newest=$newestDate")

        // Generate list of months from oldest to newest, then reverse to process newest first
        val months = backupRepository.generateMonthsInRange(oldestDate, newestDate).reversed()
        android.util.Log.d("BackupWorker", "Processing ${months.size} months from newest to oldest")

        // Set foreground for long-running operation
        setForeground(createForegroundInfo(months.size, 0, "Starting..."))

        var successCount = 0
        var skippedCount = 0
        var failCount = 0
        var totalProcessedFiles = 0
        var tooManyFailures = false

        // Process files month by month from newest to oldest
        for ((monthIndex, yearMonth) in months.withIndex()) {
            android.util.Log.d("BackupWorker", "Processing month ${monthIndex + 1}/${months.size}: $yearMonth")

            // Get files for this month that need backup
            val filesToBackup = backupRepository.getFilesToBackupByMonth(
                yearMonth = yearMonth,
                includeImages = settings.backupPhotos,
                includeVideos = settings.backupVideos
            )

            if (filesToBackup.isEmpty()) {
                android.util.Log.d("BackupWorker", "No files to backup for $yearMonth")
                continue
            }

            android.util.Log.d("BackupWorker", "Found ${filesToBackup.size} files to backup for $yearMonth")

            for ((fileIndex, file) in filesToBackup.withIndex()) {
                totalProcessedFiles++

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
                    tooManyFailures = true
                    break
                }

                // Update notification with current file info
                setForeground(createForegroundInfo(
                    total = months.size,
                    current = monthIndex + 1,
                    fileName = "${yearMonth}: ${file.displayName} (${fileIndex + 1}/${filesToBackup.size})"
                ))

                // Upload file - uploadFile handles hash computation and server-side dedup check
                android.util.Log.d("BackupWorker", "Processing file: ${file.displayName} (${file.size} bytes)")
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
                            tooManyFailures = true
                            break
                        }
                    }
                }

                // Log progress every 10 files
                if (totalProcessedFiles % 10 == 0) {
                    android.util.Log.d("BackupWorker", "Progress: $totalProcessedFiles files processed, $successCount uploaded, $skippedCount skipped, $failCount failed")
                }
            }

            if (tooManyFailures) {
                break
            }

            android.util.Log.d("BackupWorker", "Completed month $yearMonth: $successCount uploaded, $skippedCount skipped, $failCount failed so far")
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
