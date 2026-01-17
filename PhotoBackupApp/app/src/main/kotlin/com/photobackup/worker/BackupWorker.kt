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
import androidx.work.workDataOf
import com.photobackup.PhotoBackupApplication
import com.photobackup.R
import com.photobackup.data.repository.BackupRepository
import com.photobackup.data.repository.BackupResult
import com.photobackup.data.repository.BackupSource
import com.photobackup.network.ServerDiscovery
import com.photobackup.network.WifiConnectionState
import com.photobackup.network.WifiStateMonitor
import com.photobackup.util.PreferencesManager
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import java.time.YearMonth
import com.photobackup.data.repository.MediaFile
import com.photobackup.util.SettingsSnapshot

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

        // Progress data keys
        const val PROGRESS_CURRENT_MONTH = "current_month"
        const val PROGRESS_TOTAL_MONTHS = "total_months"
        const val PROGRESS_CURRENT_FILE = "current_file"
        const val PROGRESS_FILE_INDEX = "file_index"
        const val PROGRESS_FILES_IN_MONTH = "files_in_month"
        const val PROGRESS_SUCCESS_COUNT = "success_count"
        const val PROGRESS_SKIPPED_COUNT = "skipped_count"
        const val PROGRESS_FAIL_COUNT = "fail_count"
    }

    override suspend fun doWork(): Result {
        // Check if we're on home WiFi
        val settings = preferencesManager.getSettingsSnapshot()
        wifiStateMonitor.setHomeNetworks(settings.homeSSIDs)

        // Actively check current connection (don't rely on cached state which may be stale)
        val wifiState = wifiStateMonitor.checkCurrentConnection()
        android.util.Log.d("BackupWorker", "WiFi state after check: $wifiState")
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
            android.util.Log.e("BackupWorker", "API key is empty, cannot backup")
            return Result.failure()
        }

        // Log settings
        android.util.Log.d("BackupWorker", "Settings: backupPhotos=${settings.backupPhotos}, backupVideos=${settings.backupVideos}, backupWhatsApp=${settings.backupWhatsApp}, backupWeChat=${settings.backupWeChat}, backupDownloads=${settings.backupDownloads}")

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

        // Build list of sources to backup based on settings
        val sourcesToBackup = mutableListOf<BackupSource>()
        if (settings.backupPhotos || settings.backupVideos) {
            sourcesToBackup.add(BackupSource.CAMERA)
        }
        if (settings.backupWhatsApp) {
            sourcesToBackup.add(BackupSource.WHATSAPP)
        }
        if (settings.backupWeChat) {
            sourcesToBackup.add(BackupSource.WECHAT)
        }
        if (settings.backupDownloads) {
            sourcesToBackup.add(BackupSource.DOWNLOADS)
        }

        if (sourcesToBackup.isEmpty()) {
            android.util.Log.d("BackupWorker", "No backup sources enabled, completing successfully")
            return Result.success()
        }

        android.util.Log.d("BackupWorker", "Backup sources to process: $sourcesToBackup")

        // Set foreground for long-running operation
        setForeground(createForegroundInfo(sourcesToBackup.size, 0, "Starting..."))

        // Mutable state for tracking progress across all processing
        val backupState = BackupState()

        // Always generate months from current month down to oldest
        // This ensures new files are always backed up first, and each backup cycle
        // starts fresh from the current date
        val currentMonth = YearMonth.now()

        // Process each source
        for ((sourceIndex, source) in sourcesToBackup.withIndex()) {
            if (isStopped) {
                android.util.Log.d("BackupWorker", "Worker stopped by system at source ${sourceIndex + 1}/${sourcesToBackup.size}")
                backupState.wasInterrupted = true
                backupState.interruptReason = "Worker stopped by system"
                break
            }

            val sourceName = getSourceName(source)
            android.util.Log.d("BackupWorker", "Processing source ${sourceIndex + 1}/${sourcesToBackup.size}: $sourceName")

            // Get date range for this source to find the oldest file
            val dateRange = backupRepository.getSourceDateRange(source)
            if (dateRange == null) {
                android.util.Log.d("BackupWorker", "No files found for $sourceName")
                continue
            }

            val (oldestDate, _) = dateRange
            android.util.Log.d("BackupWorker", "$sourceName: oldest file date=$oldestDate")

            // Generate months from current month down to oldest file's month
            // This ensures we always start from current month and work backwards
            val months = backupRepository.generateMonthsInRange(oldestDate, System.currentTimeMillis()).reversed()
            android.util.Log.d("BackupWorker", "$sourceName: Processing ${months.size} months from $currentMonth down to oldest")

            // Process files month by month from current month to oldest
            for ((monthIndex, yearMonth) in months.withIndex()) {
                if (isStopped) {
                    android.util.Log.d("BackupWorker", "Worker stopped by system at month ${monthIndex + 1}/${months.size}")
                    backupState.wasInterrupted = true
                    backupState.interruptReason = "Worker stopped by system"
                    break
                }

                android.util.Log.d("BackupWorker", "$sourceName: Processing month ${monthIndex + 1}/${months.size}: $yearMonth")

                processMonthForSource(
                    source = source,
                    yearMonth = yearMonth,
                    settings = settings,
                    hasManualServer = hasManualServer,
                    sourcesToBackup = sourcesToBackup,
                    sourceIndex = sourceIndex,
                    totalMonths = months.size,
                    backupState = backupState
                )

                if (backupState.wasInterrupted) {
                    break
                }

                android.util.Log.d("BackupWorker", "$sourceName: Completed month $yearMonth: ${backupState.successCount} uploaded, ${backupState.skippedCount} skipped, ${backupState.failCount} failed so far")
            }

            if (backupState.wasInterrupted) {
                break
            }

            android.util.Log.d("BackupWorker", "Completed source $sourceName: ${backupState.successCount} uploaded, ${backupState.skippedCount} skipped, ${backupState.failCount} failed so far")
        }

        // Clear persisted progress since backup is done
        preferencesManager.clearBackupProgress()

        if (backupState.wasInterrupted) {
            android.util.Log.d("BackupWorker", "Backup interrupted (${backupState.interruptReason}): ${backupState.successCount} uploaded, ${backupState.skippedCount} already backed up, ${backupState.failCount} failed")
            showInterruptedNotification(backupState.successCount, backupState.skippedCount, backupState.failCount, backupState.interruptReason)
            return Result.retry()
        } else {
            android.util.Log.d("BackupWorker", "Backup complete: ${backupState.successCount} uploaded, ${backupState.skippedCount} already backed up, ${backupState.failCount} failed")
            showCompletionNotification(backupState.successCount, backupState.skippedCount, backupState.failCount)
            return Result.success()
        }
    }

    /**
     * Mutable state class to track backup progress across helper functions.
     */
    private class BackupState {
        var successCount = 0
        var skippedCount = 0
        var failCount = 0
        var totalProcessedFiles = 0
        var wasInterrupted = false
        var interruptReason = ""
    }

    private fun getSourceName(source: BackupSource): String {
        return when (source) {
            BackupSource.CAMERA -> "Camera"
            BackupSource.WHATSAPP -> "WhatsApp"
            BackupSource.WECHAT -> "WeChat"
            BackupSource.DOWNLOADS -> "Downloads"
        }
    }

    /**
     * Process all files for a given source and month.
     */
    private suspend fun processMonthForSource(
        source: BackupSource,
        yearMonth: YearMonth,
        settings: SettingsSnapshot,
        hasManualServer: Boolean,
        sourcesToBackup: List<BackupSource>,
        sourceIndex: Int,
        totalMonths: Int,
        backupState: BackupState
    ) {
        val sourceName = getSourceName(source)

        // Get files for this source and month that need backup
        val filesToBackup = if (source == BackupSource.CAMERA) {
            backupRepository.getFilesToBackupByMonth(
                yearMonth = yearMonth,
                includeImages = settings.backupPhotos,
                includeVideos = settings.backupVideos
            )
        } else {
            backupRepository.getFilesToBackupBySourceAndMonth(source, yearMonth)
        }

        if (filesToBackup.isEmpty()) {
            android.util.Log.d("BackupWorker", "$sourceName: No files to backup for $yearMonth")
            return
        }

        android.util.Log.d("BackupWorker", "$sourceName: Found ${filesToBackup.size} files to backup for $yearMonth")

        for ((fileIndex, file) in filesToBackup.withIndex()) {
            // Check if worker has been stopped by the system
            if (isStopped) {
                android.util.Log.d("BackupWorker", "Worker stopped by system during file upload")
                backupState.wasInterrupted = true
                backupState.interruptReason = "Worker stopped by system"
                return
            }

            backupState.totalProcessedFiles++

            // Check if still on acceptable WiFi (actively check, don't use cached state)
            val currentWifiState = wifiStateMonitor.checkCurrentConnection()
            val stillOnHomeWifi = currentWifiState is WifiConnectionState.ConnectedToHome
            val stillOnUnknownNetwork = currentWifiState is WifiConnectionState.ConnectedToOther &&
                (currentWifiState.ssid == "Unknown Network" || currentWifiState.ssid == "<unknown ssid>")
            val canContinue = stillOnHomeWifi ||
                (stillOnUnknownNetwork && hasManualServer && settings.allowUnknownNetwork)

            if (!canContinue) {
                // Lost WiFi connection
                android.util.Log.d("BackupWorker", "WiFi connection changed during backup, stopping: $currentWifiState")
                backupState.wasInterrupted = true
                backupState.interruptReason = "WiFi disconnected"
                return
            }

            // Update notification with current file info
            setForeground(createForegroundInfo(
                total = sourcesToBackup.size,
                current = sourceIndex + 1,
                fileName = "$sourceName: $yearMonth: ${file.displayName} (${fileIndex + 1}/${filesToBackup.size})"
            ))

            // Report progress for UI observation
            try {
                setProgress(workDataOf(
                    PROGRESS_CURRENT_MONTH to yearMonth.toString(),
                    PROGRESS_TOTAL_MONTHS to totalMonths,
                    PROGRESS_CURRENT_FILE to file.displayName,
                    PROGRESS_FILE_INDEX to (fileIndex + 1),
                    PROGRESS_FILES_IN_MONTH to filesToBackup.size,
                    PROGRESS_SUCCESS_COUNT to backupState.successCount,
                    PROGRESS_SKIPPED_COUNT to backupState.skippedCount,
                    PROGRESS_FAIL_COUNT to backupState.failCount
                ))

                // Also persist to preferences so UI can recover progress after app restart
                preferencesManager.saveBackupProgress(
                    com.photobackup.util.BackupProgress(
                        currentMonth = yearMonth.toString(),
                        totalMonths = totalMonths,
                        currentFile = file.displayName,
                        fileIndex = fileIndex + 1,
                        filesInMonth = filesToBackup.size,
                        successCount = backupState.successCount,
                        skippedCount = backupState.skippedCount,
                        failCount = backupState.failCount
                    )
                )
            } catch (e: Exception) {
                android.util.Log.e("BackupWorker", "Failed to set progress: ${e.message}")
            }

            // Upload file - uploadFile handles hash computation and server-side dedup check
            android.util.Log.d("BackupWorker", "Processing file: ${file.displayName} from $sourceName (${file.size} bytes)")
            val result = backupRepository.uploadFile(file, settings.apiKey)
            when (result) {
                is BackupResult.Success -> {
                    android.util.Log.d("BackupWorker", "Upload success: ${file.displayName}")
                    backupState.successCount++
                }
                is BackupResult.AlreadyExists -> {
                    android.util.Log.d("BackupWorker", "Already backed up: ${file.displayName}")
                    backupState.skippedCount++
                }
                is BackupResult.Error -> {
                    android.util.Log.e("BackupWorker", "Upload failed: ${file.displayName} - ${result.message}")
                    backupState.failCount++
                    // Don't let too many failures derail the whole backup
                    if (backupState.failCount > 10) {
                        android.util.Log.e("BackupWorker", "Too many failures, stopping backup")
                        backupState.wasInterrupted = true
                        backupState.interruptReason = "Too many failures"
                        return
                    }
                }
            }

            // Log progress every 10 files
            if (backupState.totalProcessedFiles % 10 == 0) {
                android.util.Log.d("BackupWorker", "Progress: ${backupState.totalProcessedFiles} files processed, ${backupState.successCount} uploaded, ${backupState.skippedCount} skipped, ${backupState.failCount} failed")
            }
        }
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

    private fun showInterruptedNotification(successCount: Int, skippedCount: Int, failCount: Int, reason: String) {
        val notificationManager = applicationContext.getSystemService(
            Context.NOTIFICATION_SERVICE
        ) as NotificationManager

        val title = "Backup Paused"
        val text = "$reason. $successCount uploaded so far. Will resume automatically."

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
