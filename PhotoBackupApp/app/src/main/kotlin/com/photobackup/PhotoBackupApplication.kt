package com.photobackup

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.photobackup.receiver.WifiConnectivityReceiver
import com.photobackup.util.PreferencesManager
import com.photobackup.worker.BackupScheduler
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class PhotoBackupApplication : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var backupScheduler: BackupScheduler

    private var wifiReceiver: WifiConnectivityReceiver? = null

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        registerWifiReceiver()
        scheduleBackupIfEnabled()
    }

    private fun scheduleBackupIfEnabled() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = preferencesManager.getSettingsSnapshot()
                if (settings.autoBackup && settings.apiKey.isNotEmpty()) {
                    Log.d(TAG, "Scheduling periodic backup on app start")
                    backupScheduler.schedulePeriodicBackup()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling backup on app start", e)
            }
        }
    }

    private fun registerWifiReceiver() {
        wifiReceiver = WifiConnectivityReceiver()
        val intentFilter = IntentFilter().apply {
            addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION)
            @Suppress("DEPRECATION")
            addAction(ConnectivityManager.CONNECTIVITY_ACTION)
        }
        registerReceiver(wifiReceiver, intentFilter)
        Log.d(TAG, "WiFi connectivity receiver registered")
    }

    override fun onTerminate() {
        super.onTerminate()
        wifiReceiver?.let {
            unregisterReceiver(it)
            Log.d(TAG, "WiFi connectivity receiver unregistered")
        }
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val backupChannel = NotificationChannel(
                CHANNEL_BACKUP,
                getString(R.string.notification_channel_backup),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_backup_desc)
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(backupChannel)
        }
    }

    companion object {
        private const val TAG = "PhotoBackupApplication"
        const val CHANNEL_BACKUP = "backup_channel"
    }
}
