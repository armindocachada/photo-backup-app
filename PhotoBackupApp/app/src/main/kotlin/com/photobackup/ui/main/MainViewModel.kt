package com.photobackup.ui.main

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.photobackup.data.repository.BackupRepository
import com.photobackup.network.ServerDiscovery
import com.photobackup.network.ServerInfo
import com.photobackup.network.WifiConnectionState
import com.photobackup.network.WifiStateMonitor
import com.photobackup.util.PreferencesManager
import com.photobackup.worker.BackupScheduler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed class BackupState {
    data object Idle : BackupState()
    data object CheckingWifi : BackupState()
    data object DiscoveringServer : BackupState()
    data object Scanning : BackupState()
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

@HiltViewModel
class MainViewModel @Inject constructor(
    private val backupRepository: BackupRepository,
    private val serverDiscovery: ServerDiscovery,
    private val wifiStateMonitor: WifiStateMonitor,
    private val preferencesManager: PreferencesManager,
    private val backupScheduler: BackupScheduler
) : ViewModel() {

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

            // Schedule periodic backup if auto-backup is enabled
            if (settings.autoBackup && settings.apiKey.isNotEmpty()) {
                backupScheduler.schedulePeriodicBackup()
            }
        }
    }

    fun startBackup() {
        viewModelScope.launch {
            try {
                _backupState.value = BackupState.CheckingWifi

                // Check WiFi
                val wifiState = wifiStateMonitor.checkCurrentConnection()
                if (wifiState !is WifiConnectionState.ConnectedToHome &&
                    wifiState !is WifiConnectionState.ConnectedToOther
                ) {
                    _backupState.value = BackupState.Error("Not connected to WiFi")
                    return@launch
                }

                // Get settings
                val settings = preferencesManager.getSettingsSnapshot()
                if (settings.apiKey.isEmpty()) {
                    _backupState.value = BackupState.Error("API key not configured")
                    return@launch
                }

                // Discover server
                _backupState.value = BackupState.DiscoveringServer
                val serverInfo = if (settings.serverHost.isNotEmpty()) {
                    serverDiscovery.verifyServer(
                        settings.serverHost,
                        settings.serverPort.toIntOrNull() ?: 8080
                    )
                } else {
                    serverDiscovery.discoverServer()
                }

                if (serverInfo == null) {
                    _backupState.value = BackupState.Error("Could not find backup server")
                    return@launch
                }

                _serverInfo.value = serverInfo
                backupRepository.setServer(serverInfo, settings.apiKey)

                // Verify connection
                if (!backupRepository.verifyConnection(settings.apiKey)) {
                    _backupState.value = BackupState.Error("Could not connect to server")
                    return@launch
                }

                // Use WorkManager for actual backup
                backupScheduler.runBackupNow()
                _backupState.value = BackupState.Idle

            } catch (e: Exception) {
                _backupState.value = BackupState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun stopBackup() {
        backupScheduler.cancelBackup()
        _backupState.value = BackupState.Idle
    }

    fun refreshStatus() {
        wifiStateMonitor.checkCurrentConnection()
    }

    override fun onCleared() {
        super.onCleared()
        wifiStateMonitor.stopMonitoring()
    }
}
