package com.photobackup.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

sealed class WifiConnectionState {
    data object Disconnected : WifiConnectionState()
    data object ConnectedToHome : WifiConnectionState()
    data class ConnectedToOther(val ssid: String) : WifiConnectionState()
}

@Singleton
class WifiStateMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private val _wifiState = MutableStateFlow<WifiConnectionState>(WifiConnectionState.Disconnected)
    val wifiState: StateFlow<WifiConnectionState> = _wifiState.asStateFlow()

    private var targetSSIDs: Set<String> = emptySet()
    private var isMonitoring = false

    private val networkCallback = object : ConnectivityManager.NetworkCallback(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            FLAG_INCLUDE_LOCATION_INFO
        } else {
            0
        }
    ) {
        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            val currentSSID = extractSSID(capabilities)
            updateState(currentSSID)
        }

        override fun onLost(network: Network) {
            _wifiState.value = WifiConnectionState.Disconnected
        }

        override fun onUnavailable() {
            _wifiState.value = WifiConnectionState.Disconnected
        }
    }

    private fun extractSSID(capabilities: NetworkCapabilities): String? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            wifiInfo?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
        } else {
            @Suppress("DEPRECATION")
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            wifiManager.connectionInfo?.ssid?.removeSurrounding("\"")?.takeIf { it != "<unknown ssid>" }
        }
    }

    private fun updateState(currentSSID: String?) {
        _wifiState.value = when {
            currentSSID == null -> WifiConnectionState.Disconnected
            currentSSID in targetSSIDs -> WifiConnectionState.ConnectedToHome
            else -> WifiConnectionState.ConnectedToOther(currentSSID)
        }
    }

    fun setHomeNetworks(ssids: Set<String>) {
        targetSSIDs = ssids
        // Re-evaluate current state
        val currentState = _wifiState.value
        if (currentState is WifiConnectionState.ConnectedToOther) {
            if (currentState.ssid in targetSSIDs) {
                _wifiState.value = WifiConnectionState.ConnectedToHome
            }
        } else if (currentState is WifiConnectionState.ConnectedToHome) {
            // Check if still valid
            checkCurrentConnection()
        }
    }

    fun startMonitoring() {
        if (isMonitoring) return

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()

        try {
            connectivityManager.registerNetworkCallback(request, networkCallback)
            isMonitoring = true
        } catch (e: Exception) {
            // Handle case where callback is already registered
        }
    }

    fun stopMonitoring() {
        if (!isMonitoring) return

        try {
            connectivityManager.unregisterNetworkCallback(networkCallback)
            isMonitoring = false
        } catch (e: Exception) {
            // Handle case where callback is not registered
        }
    }

    fun checkCurrentConnection(): WifiConnectionState {
        val network = connectivityManager.activeNetwork
        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }

        if (capabilities == null || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            _wifiState.value = WifiConnectionState.Disconnected
            return WifiConnectionState.Disconnected
        }

        val ssid = extractSSID(capabilities)
        updateState(ssid)
        return _wifiState.value
    }

    fun isConnectedToHomeWifi(): Boolean {
        return _wifiState.value is WifiConnectionState.ConnectedToHome
    }

    fun getCurrentSSID(): String? {
        return when (val state = _wifiState.value) {
            is WifiConnectionState.ConnectedToHome -> {
                targetSSIDs.firstOrNull()
            }
            is WifiConnectionState.ConnectedToOther -> state.ssid
            is WifiConnectionState.Disconnected -> null
        }
    }
}
