package com.photobackup.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
            binding.backupWhatsAppSwitch.isChecked = settings.backupWhatsApp
            binding.backupWeChatSwitch.isChecked = settings.backupWeChat
            binding.backupDownloadsSwitch.isChecked = settings.backupDownloads
            binding.allowUnknownNetworkSwitch.isChecked = settings.allowUnknownNetwork
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

        // Check permissions when enabling external source backups
        val externalSourceSwitches = listOf(
            binding.backupWhatsAppSwitch,
            binding.backupWeChatSwitch,
            binding.backupDownloadsSwitch
        )

        for (switch in externalSourceSwitches) {
            switch.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked && !hasAllFilesAccess()) {
                    showAllFilesAccessDialog()
                }
            }
        }

        binding.saveButton.setOnClickListener {
            saveSettings()
        }
    }

    private fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true // On older versions, READ_EXTERNAL_STORAGE is enough
        }
    }

    private fun showAllFilesAccessDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("All Files Access Required")
                .setMessage("To backup WhatsApp, WeChat, or Downloads folders, you need to grant All Files Access permission. Would you like to open Settings to grant this permission?")
                .setPositiveButton("Open Settings") { _, _ ->
                    try {
                        val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                        intent.data = Uri.parse("package:$packageName")
                        startActivity(intent)
                    } catch (e: Exception) {
                        val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        startActivity(intent)
                    }
                }
                .setNegativeButton("Cancel", null)
                .show()
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
            val backupWhatsApp = binding.backupWhatsAppSwitch.isChecked
            val backupWeChat = binding.backupWeChatSwitch.isChecked
            val backupDownloads = binding.backupDownloadsSwitch.isChecked
            val allowUnknownNetwork = binding.allowUnknownNetworkSwitch.isChecked

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
            preferencesManager.setBackupWhatsApp(backupWhatsApp)
            preferencesManager.setBackupWeChat(backupWeChat)
            preferencesManager.setBackupDownloads(backupDownloads)
            preferencesManager.setAllowUnknownNetwork(allowUnknownNetwork)

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
