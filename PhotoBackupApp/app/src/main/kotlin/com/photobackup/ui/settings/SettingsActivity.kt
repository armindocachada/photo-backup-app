package com.photobackup.ui.settings

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.photobackup.databinding.ActivitySettingsBinding
import com.photobackup.network.WifiStateMonitor
import com.photobackup.util.PreferencesManager
import com.photobackup.worker.BackupScheduler
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding

    @Inject
    lateinit var preferencesManager: PreferencesManager

    @Inject
    lateinit var wifiStateMonitor: WifiStateMonitor

    @Inject
    lateinit var backupScheduler: BackupScheduler

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupToolbar()
        loadSettings()
        setupListeners()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun loadSettings() {
        lifecycleScope.launch {
            val settings = preferencesManager.getSettingsSnapshot()

            binding.apiKeyInput.setText(settings.apiKey)
            binding.homeWifiInput.setText(settings.homeSSIDs.firstOrNull() ?: "")

            if (settings.serverHost.isNotEmpty()) {
                val address = if (settings.serverPort != "8080") {
                    "${settings.serverHost}:${settings.serverPort}"
                } else {
                    settings.serverHost
                }
                binding.serverAddressInput.setText(address)
            }

            binding.autoBackupSwitch.isChecked = settings.autoBackup
            binding.backupPhotosSwitch.isChecked = settings.backupPhotos
            binding.backupVideosSwitch.isChecked = settings.backupVideos
        }
    }

    private fun setupListeners() {
        binding.useCurrentWifiButton.setOnClickListener {
            val currentSSID = wifiStateMonitor.getCurrentSSID()
            if (currentSSID != null) {
                binding.homeWifiInput.setText(currentSSID)
            } else {
                Toast.makeText(this, "Not connected to WiFi", Toast.LENGTH_SHORT).show()
            }
        }

        binding.saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun saveSettings() {
        lifecycleScope.launch {
            val apiKey = binding.apiKeyInput.text?.toString()?.trim() ?: ""
            val homeWifi = binding.homeWifiInput.text?.toString()?.trim() ?: ""
            val serverAddress = binding.serverAddressInput.text?.toString()?.trim() ?: ""
            val autoBackup = binding.autoBackupSwitch.isChecked
            val backupPhotos = binding.backupPhotosSwitch.isChecked
            val backupVideos = binding.backupVideosSwitch.isChecked

            // Parse server address
            val (host, port) = parseServerAddress(serverAddress)

            // Save all settings
            preferencesManager.setApiKey(apiKey)

            if (homeWifi.isNotEmpty()) {
                preferencesManager.setHomeSSIDs(setOf(homeWifi))
                wifiStateMonitor.setHomeNetworks(setOf(homeWifi))
            }

            preferencesManager.setServerAddress(host, port)
            preferencesManager.setAutoBackup(autoBackup)
            preferencesManager.setBackupPhotos(backupPhotos)
            preferencesManager.setBackupVideos(backupVideos)

            if (apiKey.isNotEmpty()) {
                preferencesManager.setSetupComplete(true)
            }

            // Update backup scheduler
            if (autoBackup && apiKey.isNotEmpty()) {
                backupScheduler.schedulePeriodicBackup()
            } else {
                backupScheduler.cancelPeriodicBackup()
            }

            Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun parseServerAddress(address: String): Pair<String, String> {
        if (address.isEmpty()) return "" to "8080"

        return if (address.contains(":")) {
            val parts = address.split(":")
            parts[0] to (parts.getOrNull(1) ?: "8080")
        } else {
            address to "8080"
        }
    }
}
