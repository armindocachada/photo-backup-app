package com.photobackup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.util.Log
import com.photobackup.network.WifiStateMonitor
import com.photobackup.util.PreferencesManager
import com.photobackup.worker.BackupScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Monitors WiFi connectivity changes and triggers backup when connected to home WiFi.
 *
 * This receiver is registered programmatically from the Application class to ensure
 * it stays active even when the app is not in the foreground.
 */
@AndroidEntryPoint
class WifiConnectivityReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var backupScheduler: BackupScheduler

    @Inject
    lateinit var wifiStateMonitor: WifiStateMonitor

    companion object {
        private const val TAG = "WifiConnectivityReceiver"

        // Track if we've already triggered backup for current connection
        @Volatile
        private var lastTriggeredSsid: String? = null
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WifiManager.NETWORK_STATE_CHANGED_ACTION,
            ConnectivityManager.CONNECTIVITY_ACTION -> {
                Log.d(TAG, "Received connectivity change: ${intent.action}")
                handleConnectivityChange(context)
            }
        }
    }

    private fun handleConnectivityChange(context: Context) {
        val pendingResult = goAsync()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = preferencesManager.getSettingsSnapshot()

                // Skip if auto-backup is disabled or not configured
                if (!settings.autoBackup || settings.apiKey.isEmpty()) {
                    Log.d(TAG, "Auto-backup disabled or not configured, skipping")
                    return@launch
                }

                // Update home networks in monitor
                wifiStateMonitor.setHomeNetworks(settings.homeSSIDs)

                // Check current connection
                val wifiState = wifiStateMonitor.checkCurrentConnection()
                Log.d(TAG, "Current WiFi state: $wifiState")

                when (wifiState) {
                    is com.photobackup.network.WifiConnectionState.ConnectedToHome -> {
                        // Get current SSID from monitor
                        val currentSsid = wifiStateMonitor.getCurrentSSID() ?: "home"

                        // Check if we already triggered for this SSID
                        if (lastTriggeredSsid != currentSsid) {
                            Log.d(TAG, "Connected to home WiFi: $currentSsid, triggering backup")
                            lastTriggeredSsid = currentSsid

                            // Ensure periodic backup is scheduled
                            backupScheduler.schedulePeriodicBackup()

                            // Also run an immediate backup
                            backupScheduler.runBackupNow()
                        } else {
                            Log.d(TAG, "Already triggered backup for $currentSsid, skipping")
                        }
                    }
                    is com.photobackup.network.WifiConnectionState.Disconnected -> {
                        // Reset the trigger so next connection will start backup
                        lastTriggeredSsid = null
                        Log.d(TAG, "Disconnected from WiFi, reset trigger")
                    }
                    else -> {
                        Log.d(TAG, "Not on home WiFi, skipping backup trigger")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling connectivity change", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
