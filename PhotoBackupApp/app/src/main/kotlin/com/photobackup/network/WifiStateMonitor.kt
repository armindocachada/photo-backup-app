package com.photobackup.network

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "WifiStateMonitor"

sealed class WifiConnectionState {
    data object Disconnected : WifiConnectionState()
    data object ConnectedNoPermission : WifiConnectionState()  // Connected but location permission missing
    data object ConnectedLocationOff : WifiConnectionState()   // Connected but location services off
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
        override fun onAvailable(network: Network) {
            Log.d(TAG, "onAvailable: network=$network")
            // When network becomes available, check its capabilities
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val ssid = extractSSID(capabilities)
                Log.d(TAG, "onAvailable: WiFi detected, SSID=$ssid")
                updateState(ssid, isConnectedToWifi = true)
            }
        }

        override fun onCapabilitiesChanged(
            network: Network,
            capabilities: NetworkCapabilities
        ) {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val currentSSID = extractSSID(capabilities)
                Log.d(TAG, "onCapabilitiesChanged: WiFi SSID=$currentSSID")
                updateState(currentSSID, isConnectedToWifi = true)
            }
        }

        override fun onLost(network: Network) {
            Log.d(TAG, "onLost: network=$network")
            _wifiState.value = WifiConnectionState.Disconnected
        }

        override fun onUnavailable() {
            Log.d(TAG, "onUnavailable")
            _wifiState.value = WifiConnectionState.Disconnected
        }
    }

    private fun extractSSID(capabilities: NetworkCapabilities): String? {
        // Try multiple methods to extract SSID
        var ssid: String? = null

        // Method 1: Extract from NetworkCapabilities (Android Q+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            ssid = wifiInfo?.ssid?.removeSurrounding("\"")?.takeIf { isValidSSID(it) }
            Log.d(TAG, "extractSSID method 1 (transportInfo): $ssid")
        }

        // Method 2: Use WifiManager (fallback)
        if (ssid == null) {
            ssid = getSSIDFromWifiManager()
            Log.d(TAG, "extractSSID method 2 (WifiManager): $ssid")
        }

        // Method 3: Try getting BSSID and matching (Android 12+)
        if (ssid == null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val wifiInfo = capabilities.transportInfo as? WifiInfo
            val bssid = wifiInfo?.bssid
            if (bssid != null && bssid != "02:00:00:00:00:00") {
                // We have a valid BSSID, the network is real even if SSID is hidden
                Log.d(TAG, "extractSSID: BSSID detected ($bssid), SSID may be hidden or location off")
            }
        }

        return ssid
    }

    private fun getSSIDFromWifiManager(): String? {
        return try {
            @Suppress("DEPRECATION")
            val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            @Suppress("DEPRECATION")
            val connectionInfo = wifiManager.connectionInfo
            val ssid = connectionInfo?.ssid?.removeSurrounding("\"")
            ssid?.takeIf { isValidSSID(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get SSID from WifiManager", e)
            null
        }
    }

    private fun isValidSSID(ssid: String?): Boolean {
        if (ssid == null) return false
        val invalidValues = setOf(
            "<unknown ssid>",
            "unknown ssid",
            "0x",
            "",
            "null"
        )
        return ssid.lowercase() !in invalidValues.map { it.lowercase() } && ssid.isNotBlank()
    }

    private fun updateState(currentSSID: String?, isConnectedToWifi: Boolean = true) {
        val hasLocPerm = hasLocationPermission()
        val locEnabled = isLocationEnabled()
        Log.d(TAG, "updateState: SSID=$currentSSID, isConnectedToWifi=$isConnectedToWifi, hasLocPerm=$hasLocPerm, locEnabled=$locEnabled")

        _wifiState.value = when {
            currentSSID == null && isConnectedToWifi && !hasLocPerm ->
                WifiConnectionState.ConnectedNoPermission
            currentSSID == null && isConnectedToWifi && !locEnabled ->
                WifiConnectionState.ConnectedLocationOff
            // If we're on WiFi but can't read SSID (even with permissions), treat as "connected to other"
            currentSSID == null && isConnectedToWifi ->
                WifiConnectionState.ConnectedToOther("Unknown Network")
            currentSSID == null -> WifiConnectionState.Disconnected
            currentSSID in targetSSIDs -> WifiConnectionState.ConnectedToHome
            else -> WifiConnectionState.ConnectedToOther(currentSSID)
        }
        Log.d(TAG, "updateState: new state=${_wifiState.value}")
    }

    private fun hasLocationPermission(): Boolean {
        val hasFine = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        val hasCoarse = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        Log.d(TAG, "hasLocationPermission: fine=$hasFine, coarse=$hasCoarse")
        return hasFine || hasCoarse
    }

    private fun isLocationEnabled(): Boolean {
        return try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager

            val gpsEnabled = try {
                locationManager.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                false
            }

            val networkEnabled = try {
                locationManager.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                false
            }

            // On Android 9+, also check if location mode is on
            val locationModeOn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                locationManager.isLocationEnabled
            } else {
                gpsEnabled || networkEnabled
            }

            Log.d(TAG, "isLocationEnabled: gps=$gpsEnabled, network=$networkEnabled, modeOn=$locationModeOn")
            locationModeOn
        } catch (e: Exception) {
            Log.e(TAG, "Error checking location enabled", e)
            false
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
            Log.d(TAG, "Started monitoring WiFi")
            // Do an immediate check of current connection
            checkCurrentConnection()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register network callback", e)
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
        Log.d(TAG, "checkCurrentConnection: activeNetwork=$network")

        val capabilities = network?.let { connectivityManager.getNetworkCapabilities(it) }
        Log.d(TAG, "checkCurrentConnection: capabilities=$capabilities")

        if (capabilities == null) {
            Log.d(TAG, "checkCurrentConnection: no capabilities, marking disconnected")
            _wifiState.value = WifiConnectionState.Disconnected
            return WifiConnectionState.Disconnected
        }

        val hasWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        Log.d(TAG, "checkCurrentConnection: hasWifi=$hasWifi")

        if (!hasWifi) {
            _wifiState.value = WifiConnectionState.Disconnected
            return WifiConnectionState.Disconnected
        }

        val ssid = extractSSID(capabilities)
        Log.d(TAG, "checkCurrentConnection: SSID=$ssid, hasLocationPerm=${hasLocationPermission()}")
        updateState(ssid, isConnectedToWifi = true)
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
            is WifiConnectionState.ConnectedNoPermission -> null
            is WifiConnectionState.ConnectedLocationOff -> null
        }
    }
}
