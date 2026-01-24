package com.photobackup.ui.main

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.photobackup.data.repository.BackupRepository
import com.photobackup.network.ServerDiscovery
import com.photobackup.network.ServerInfo
import com.photobackup.network.WifiConnectionState
import com.photobackup.network.WifiStateMonitor
import com.photobackup.util.PreferencesManager
import com.photobackup.worker.BackupScheduler
import com.photobackup.worker.BackupWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object CheckingWifi : BackupState()
    data object DiscoveringServer : BackupState()
    data object Scanning : BackupState()
    data object BackupRunning : BackupState()  // WorkManager is running the backup
    data class Uploading(
        val currentMonth: String,
        val totalMonths: Int,
        val currentFile: String,
        val fileIndex: Int,
        val filesInMonth: Int,
        val successCount: Int,
        val skippedCount: Int,
        val failCount: Int
    ) : BackupState()
    data object Completed : BackupState()
    data class Error(val message: String) : BackupState()
}

data class MainUiState(
    val backupState: BackupState = BackupState.Idle,
    val backedUpCount: Int = 0,
    val pendingCount: Int = 0,
    val wifiState: WifiConnectionState = WifiConnectionState.Disconnected,
    val serverInfo: ServerInfo? = null,
    val isConfigured: Boolean = false
)

private const val TAG = "MainViewModel"

@HiltViewModel
class MainViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backupRepository: BackupRepository,
    private val serverDiscovery: ServerDiscovery,
    private val wifiStateMonitor: WifiStateMonitor,
    private val preferencesManager: PreferencesManager,
    private val backupScheduler: BackupScheduler
) : ViewModel() {

    private val workManager = WorkManager.getInstance(context)
    private val _backupState = MutableStateFlow<BackupState>(BackupState.Idle)
    private val _serverInfo = MutableStateFlow<ServerInfo?>(null)

    val uiState: StateFlow<MainUiState> = combine(
        _backupState,
        backupRepository.getBackedUpCountFlow(),
        backupRepository.getPendingCountFlow(),
        wifiStateMonitor.wifiState,
        preferencesManager.apiKey
    ) { backupState, backedUp, pending, wifiState, apiKey ->
        MainUiState(
            backupState = backupState,
            backedUpCount = backedUp,
            pendingCount = pending,
            wifiState = wifiState,
            serverInfo = _serverInfo.value,
            isConfigured = apiKey.isNotEmpty()
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState()
    )

    init {
        // Start WiFi monitoring
        viewModelScope.launch {
            val settings = preferencesManager.getSettingsSnapshot()
            wifiStateMonitor.setHomeNetworks(settings.homeSSIDs)
            wifiStateMonitor.startMonitoring()

            // Schedule or cancel periodic backup based on settings
            if (settings.autoBackup && settings.apiKey.isNotEmpty()) {
                backupScheduler.schedulePeriodicBackup()
            } else {
                // Cancel any previously scheduled backups (both periodic and immediate)
                backupScheduler.cancelPeriodicBackup()
                backupScheduler.cancelBackup()
                Log.d(TAG, "Cancelled all scheduled backups (auto-backup disabled)")
            }

            // Clear any stale persisted progress if no backup is actually running
            // This handles cases where the app was killed mid-backup without proper cleanup
            if (!backupScheduler.isBackupRunning()) {
                val staleProgress = preferencesManager.getBackupProgress()
                if (staleProgress != null) {
                    Log.d(TAG, "Clearing stale backup progress (no backup running)")
                    preferencesManager.clearBackupProgress()
                }
            }
        }

        // Observe WorkManager for backup status
        observeBackupWork()
    }

    private fun observeBackupWork() {
        // Observe immediate backup work
        workManager.getWorkInfosForUniqueWorkLiveData("${BackupWorker.WORK_NAME}_immediate")
            .observeForever { workInfos ->
                handleWorkInfoUpdate(workInfos?.firstOrNull(), "immediate")
            }

        // Also observe periodic backup work so UI shows progress for scheduled backups
        workManager.getWorkInfosForUniqueWorkLiveData(BackupWorker.WORK_NAME)
            .observeForever { workInfos ->
                handleWorkInfoUpdate(workInfos?.firstOrNull(), "periodic")
            }
    }

    private fun handleWorkInfoUpdate(workInfo: WorkInfo?, source: String) {
        Log.d(TAG, "WorkInfo update ($source): state=${workInfo?.state}, id=${workInfo?.id}")

        when (workInfo?.state) {
            WorkInfo.State.RUNNING -> {
                // Check for progress data from WorkManager
                val progress = workInfo.progress
                val currentMonth = progress.getString(BackupWorker.PROGRESS_CURRENT_MONTH)

                if (currentMonth != null) {
                    // We have progress data from WorkManager, show detailed state
                    _backupState.value = BackupState.Uploading(
                        currentMonth = currentMonth,
                        totalMonths = progress.getInt(BackupWorker.PROGRESS_TOTAL_MONTHS, 0),
                        currentFile = progress.getString(BackupWorker.PROGRESS_CURRENT_FILE) ?: "",
                        fileIndex = progress.getInt(BackupWorker.PROGRESS_FILE_INDEX, 0),
                        filesInMonth = progress.getInt(BackupWorker.PROGRESS_FILES_IN_MONTH, 0),
                        successCount = progress.getInt(BackupWorker.PROGRESS_SUCCESS_COUNT, 0),
                        skippedCount = progress.getInt(BackupWorker.PROGRESS_SKIPPED_COUNT, 0),
                        failCount = progress.getInt(BackupWorker.PROGRESS_FAIL_COUNT, 0)
                    )
                } else if (_backupState.value !is BackupState.Uploading) {
                    // WorkManager progress is empty, try to load from persisted preferences
                    // (this happens when app restarts while backup is running)
                    viewModelScope.launch {
                        val savedProgress = preferencesManager.getBackupProgress()
                        if (savedProgress != null) {
                            Log.d(TAG, "Loaded persisted progress: $savedProgress")
                            _backupState.value = BackupState.Uploading(
                                currentMonth = savedProgress.currentMonth,
                                totalMonths = savedProgress.totalMonths,
                                currentFile = savedProgress.currentFile,
                                fileIndex = savedProgress.fileIndex,
                                filesInMonth = savedProgress.filesInMonth,
                                successCount = savedProgress.successCount,
                                skippedCount = savedProgress.skippedCount,
                                failCount = savedProgress.failCount
                            )
                        } else {
                            // No saved progress, show generic running state
                            _backupState.value = BackupState.BackupRunning
                        }
                    }
                }
            }
            WorkInfo.State.SUCCEEDED -> {
                // Only update to Completed if we were showing this backup's progress
                if (_backupState.value is BackupState.BackupRunning || _backupState.value is BackupState.Uploading) {
                    _backupState.value = BackupState.Completed
                    // Clear any persisted progress since backup is done
                    viewModelScope.launch {
                        preferencesManager.clearBackupProgress()
                    }
                    // Reset to Idle after a short delay
                    viewModelScope.launch {
                        kotlinx.coroutines.delay(3000)
                        if (_backupState.value is BackupState.Completed) {
                            _backupState.value = BackupState.Idle
                        }
                    }
                }
            }
            WorkInfo.State.FAILED -> {
                // Clear any persisted progress since backup ended
                viewModelScope.launch {
                    preferencesManager.clearBackupProgress()
                }
                if (_backupState.value is BackupState.BackupRunning || _backupState.value is BackupState.Uploading) {
                    _backupState.value = BackupState.Error("Backup failed")
                }
            }
            WorkInfo.State.ENQUEUED -> {
                // Work is queued but not running yet
                // For periodic work, don't show as running until it actually starts
                // Only show for immediate work that was just triggered
                if (source == "immediate" && _backupState.value == BackupState.Idle) {
                    _backupState.value = BackupState.BackupRunning
                }
            }
            WorkInfo.State.CANCELLED -> {
                // Work was cancelled (e.g., app force-stopped), clear progress and reset state
                Log.d(TAG, "WorkInfo CANCELLED ($source), clearing progress")
                viewModelScope.launch {
                    preferencesManager.clearBackupProgress()
                }
                if (_backupState.value is BackupState.BackupRunning || _backupState.value is BackupState.Uploading) {
                    _backupState.value = BackupState.Idle
                }
            }
            WorkInfo.State.BLOCKED -> {
                // Work is blocked by constraints, don't change UI state
                Log.d(TAG, "WorkInfo BLOCKED ($source)")
            }
            null -> {
                // No work info - work was never scheduled or was cleared
                Log.d(TAG, "WorkInfo null ($source)")
            }
        }
    }

    fun startBackup() {
        // Guard against multiple calls when backup is already running
        val currentState = _backupState.value
        if (currentState is BackupState.BackupRunning ||
            currentState is BackupState.CheckingWifi ||
            currentState is BackupState.DiscoveringServer ||
            currentState is BackupState.Scanning ||
            currentState is BackupState.Uploading) {
            Log.d(TAG, "startBackup: Ignoring call - backup already in progress (state=$currentState)")
            return
        }

        viewModelScope.launch {
            try {
                Log.d(TAG, "startBackup: Starting backup process")
                _backupState.value = BackupState.CheckingWifi

                // Check WiFi
                val wifiState = wifiStateMonitor.checkCurrentConnection()
                Log.d(TAG, "startBackup: WiFi state = $wifiState")

                // Get settings first to check if we can proceed
                val settings = preferencesManager.getSettingsSnapshot()
                Log.d(TAG, "startBackup: Settings - apiKey=${if (settings.apiKey.isNotEmpty()) "(set)" else "(empty)"}, serverHost=${settings.serverHost}, serverPort=${settings.serverPort}, allowUnknownNetwork=${settings.allowUnknownNetwork}")

                // Check WiFi connection and permissions
                when (wifiState) {
                    is WifiConnectionState.Disconnected -> {
                        _backupState.value = BackupState.Error("Not connected to WiFi")
                        return@launch
                    }
                    is WifiConnectionState.ConnectedNoPermission -> {
                        _backupState.value = BackupState.Error("Location permission required to detect WiFi network")
                        return@launch
                    }
                    is WifiConnectionState.ConnectedLocationOff -> {
                        _backupState.value = BackupState.Error("Please enable Location services to detect WiFi network")
                        return@launch
                    }
                    is WifiConnectionState.ConnectedToOther -> {
                        val isUnknownNetwork = wifiState.ssid == "Unknown Network" || wifiState.ssid == "<unknown ssid>"
                        if (isUnknownNetwork) {
                            // Check if we can proceed on unknown network
                            if (!settings.allowUnknownNetwork) {
                                _backupState.value = BackupState.Error("WiFi name cannot be detected. Enable 'Allow backup on unknown network' in Settings.")
                                return@launch
                            }
                            if (settings.serverHost.isEmpty()) {
                                _backupState.value = BackupState.Error("Manual server address required when WiFi name cannot be detected")
                                return@launch
                            }
                            Log.d(TAG, "startBackup: Proceeding on unknown network with manual server")
                        } else {
                            // Connected to a known non-home network
                            _backupState.value = BackupState.Error("Not on home WiFi (connected to: ${wifiState.ssid})")
                            return@launch
                        }
                    }
                    is WifiConnectionState.ConnectedToHome -> {
                        Log.d(TAG, "startBackup: Connected to home WiFi")
                    }
                }

                if (settings.apiKey.isEmpty()) {
                    _backupState.value = BackupState.Error("API key not configured")
                    return@launch
                }

                // Discover server
                _backupState.value = BackupState.DiscoveringServer
                Log.d(TAG, "startBackup: Starting server discovery (manual host: ${settings.serverHost}, serverId: ${settings.serverId})")
                val serverInfo = if (settings.serverHost.isNotEmpty()) {
                    serverDiscovery.verifyServer(
                        settings.serverHost,
                        settings.serverPort.toIntOrNull() ?: 8080
                    )
                } else {
                    // Pass the expected server ID to filter discovery results
                    val expectedServerId = settings.serverId.ifEmpty { null }
                    serverDiscovery.discoverServer(expectedServerId)
                }
                Log.d(TAG, "startBackup: Server discovery result = $serverInfo")

                if (serverInfo == null) {
                    _backupState.value = BackupState.Error("Could not find backup server")
                    return@launch
                }

                _serverInfo.value = serverInfo
                val expectedServerId = settings.serverId.ifEmpty { null }
                backupRepository.setServer(serverInfo, settings.apiKey, expectedServerId)

                // Verify connection
                Log.d(TAG, "startBackup: Verifying connection to ${serverInfo.baseUrl}")
                if (!backupRepository.verifyConnection(settings.apiKey)) {
                    Log.e(TAG, "startBackup: Connection verification failed")
                    _backupState.value = BackupState.Error("Could not connect to server")
                    return@launch
                }
                Log.d(TAG, "startBackup: Connection verified successfully")

                // Use WorkManager for actual backup
                Log.d(TAG, "startBackup: Scheduling backup via WorkManager")
                backupScheduler.runBackupNow()
                _backupState.value = BackupState.BackupRunning

            } catch (e: Exception) {
                Log.e(TAG, "startBackup: Exception occurred", e)
                _backupState.value = BackupState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun stopBackup() {
        Log.d(TAG, "stopBackup: Cancelling backup")
        backupScheduler.cancelBackup()
        backupScheduler.cancelPeriodicBackup()
        _backupState.value = BackupState.Idle
        Log.d(TAG, "stopBackup: State set to Idle")
    }

    fun refreshStatus() {
        wifiStateMonitor.checkCurrentConnection()
    }

    override fun onCleared() {
        super.onCleared()
        wifiStateMonitor.stopMonitoring()
    }
}
