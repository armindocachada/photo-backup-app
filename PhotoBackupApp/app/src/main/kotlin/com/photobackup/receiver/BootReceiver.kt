package com.photobackup.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.photobackup.util.PreferencesManager
import com.photobackup.worker.BackupScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * BroadcastReceiver that runs on device boot to schedule periodic backups.
 * This ensures backups continue even if the user never opens the app after a reboot.
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var backupScheduler: BackupScheduler

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            Log.d(TAG, "Received ${intent.action}")

            // Use goAsync() to extend the time we have to complete work
            val pendingResult = goAsync()

            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val settings = preferencesManager.getSettingsSnapshot()

                    if (settings.autoBackup && settings.apiKey.isNotEmpty()) {
                        Log.d(TAG, "Scheduling periodic backup after boot")
                        backupScheduler.schedulePeriodicBackup()

                        // Also trigger an immediate backup with less strict constraints
                        // This gives faster retry on boot when server might not be ready yet
                        Log.d(TAG, "Triggering immediate backup after boot")
                        backupScheduler.runBackupNow()
                    } else {
                        Log.d(TAG, "Auto-backup disabled or API key not set, skipping schedule")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error scheduling backup after boot", e)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }
}
