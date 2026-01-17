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
    data class Uploading(val current: Int, val total: Int, val fileName: String) : BackupState()
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
        }

        // Observe WorkManager for backup status
        observeBackupWork()
    }

    private fun observeBackupWork() {
        workManager.getWorkInfosForUniqueWorkLiveData("${BackupWorker.WORK_NAME}_immediate")
            .observeForever { workInfos ->
                val workInfo = workInfos?.firstOrNull()
                Log.d(TAG, "WorkInfo update: state=${workInfo?.state}, id=${workInfo?.id}")

                when (workInfo?.state) {
                    WorkInfo.State.RUNNING -> {
                        if (_backupState.value !is BackupState.Uploading) {
                            _backupState.value = BackupState.BackupRunning
                        }
                    }
                    WorkInfo.State.SUCCEEDED -> {
                        _backupState.value = BackupState.Completed
                        // Reset to Idle after a short delay
                        viewModelScope.launch {
                            kotlinx.coroutines.delay(3000)
                            if (_backupState.value is BackupState.Completed) {
                                _backupState.value = BackupState.Idle
                            }
                        }
                    }
                    WorkInfo.State.FAILED -> {
                        _backupState.value = BackupState.Error("Backup failed")
                    }
                    WorkInfo.State.ENQUEUED -> {
                        // Work is queued but not running yet
                        if (_backupState.value == BackupState.Idle) {
                            _backupState.value = BackupState.BackupRunning
                        }
                    }
                    else -> {
                        // BLOCKED, CANCELLED, or null
                    }
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
                Log.d(TAG, "startBackup: Starting server discovery (manual host: ${settings.serverHost})")
                val serverInfo = if (settings.serverHost.isNotEmpty()) {
                    serverDiscovery.verifyServer(
                        settings.serverHost,
                        settings.serverPort.toIntOrNull() ?: 8080
                    )
                } else {
                    serverDiscovery.discoverServer()
                }
                Log.d(TAG, "startBackup: Server discovery result = $serverInfo")

                if (serverInfo == null) {
                    _backupState.value = BackupState.Error("Could not find backup server")
                    return@launch
                }

                _serverInfo.value = serverInfo
                backupRepository.setServer(serverInfo, settings.apiKey)

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
