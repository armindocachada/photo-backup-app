package com.photobackup.ui.main

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.photobackup.R
import com.photobackup.databinding.ActivityMainBinding
import com.photobackup.network.WifiConnectionState
import com.photobackup.ui.settings.SettingsActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var pendingBackupAfterPermission = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        // Refresh WiFi status after permission grant
        viewModel.refreshStatus()
        if (allGranted && pendingBackupAfterPermission) {
            pendingBackupAfterPermission = false
            viewModel.startBackup()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        observeState()
    }

    private fun setupUI() {
        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.backupButton.setOnClickListener {
            val currentState = viewModel.uiState.value.backupState
            if (currentState is BackupState.BackupRunning || currentState is BackupState.Uploading) {
                // Stop the backup
                viewModel.stopBackup()
            } else {
                // Start the backup
                if (checkPermissions()) {
                    viewModel.startBackup()
                } else {
                    pendingBackupAfterPermission = true
                    requestPermissions()
                }
            }
        }
    }

    private fun checkAndRequestPermissionsForWifi() {
        if (!hasLocationPermission()) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION))
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { state ->
                    updateUI(state)
                }
            }
        }
    }

    private fun updateUI(state: MainUiState) {
        // Update backup status
        when (val backupState = state.backupState) {
            is BackupState.Idle -> {
                binding.statusText.text = getString(R.string.status_idle)
                binding.progressBar.visibility = View.GONE
                binding.backupButton.isEnabled = true
                binding.backupButton.text = getString(R.string.btn_backup_now)
            }
            is BackupState.CheckingWifi -> {
                binding.statusText.text = "Checking WiFi..."
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = true
                binding.backupButton.isEnabled = false
            }
            is BackupState.DiscoveringServer -> {
                binding.statusText.text = getString(R.string.status_discovering)
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = true
                binding.backupButton.isEnabled = false
            }
            is BackupState.Scanning -> {
                binding.statusText.text = getString(R.string.status_scanning)
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = true
                binding.backupButton.isEnabled = false
            }
            is BackupState.BackupRunning -> {
                binding.statusText.text = "Backup in progress..."
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = true
                binding.backupButton.isEnabled = true
                binding.backupButton.text = getString(R.string.btn_stop)
            }
            is BackupState.Uploading -> {
                binding.statusText.text = getString(
                    R.string.status_uploading,
                    backupState.current,
                    backupState.total
                )
                binding.progressBar.visibility = View.VISIBLE
                binding.progressBar.isIndeterminate = false
                binding.progressBar.max = backupState.total
                binding.progressBar.progress = backupState.current
                binding.backupButton.isEnabled = true
                binding.backupButton.text = getString(R.string.btn_stop)
            }
            is BackupState.Completed -> {
                binding.statusText.text = getString(R.string.status_completed)
                binding.progressBar.visibility = View.GONE
                binding.backupButton.isEnabled = true
                binding.backupButton.text = getString(R.string.btn_backup_now)
            }
            is BackupState.Error -> {
                binding.statusText.text = getString(R.string.status_error, backupState.message)
                binding.statusText.setTextColor(ContextCompat.getColor(this, R.color.error))
                binding.progressBar.visibility = View.GONE
                binding.backupButton.isEnabled = true
                binding.backupButton.text = getString(R.string.btn_backup_now)
            }
        }

        // Reset text color if not error
        if (state.backupState !is BackupState.Error) {
            binding.statusText.setTextColor(
                ContextCompat.getColor(this, android.R.color.primary_text_light)
            )
        }

        // Update WiFi status
        when (val wifiState = state.wifiState) {
            is WifiConnectionState.Disconnected -> {
                binding.wifiStatusText.text = "Not connected to WiFi"
                binding.wifiIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.gray)
                )
                binding.wifiStatusText.setOnClickListener(null)
            }
            is WifiConnectionState.ConnectedNoPermission -> {
                binding.wifiStatusText.text = "WiFi connected - tap to grant location permission"
                binding.wifiIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.warning)
                )
                binding.wifiStatusText.setOnClickListener {
                    checkAndRequestPermissionsForWifi()
                }
            }
            is WifiConnectionState.ConnectedLocationOff -> {
                binding.wifiStatusText.text = "WiFi connected - enable Location in Settings"
                binding.wifiIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.warning)
                )
                binding.wifiStatusText.setOnClickListener {
                    // Open location settings
                    startActivity(Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                }
            }
            is WifiConnectionState.ConnectedToHome -> {
                binding.wifiStatusText.text = "Connected to home WiFi"
                binding.wifiIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.success)
                )
                binding.wifiStatusText.setOnClickListener(null)
            }
            is WifiConnectionState.ConnectedToOther -> {
                binding.wifiStatusText.text = "Connected to: ${wifiState.ssid}"
                binding.wifiIcon.setColorFilter(
                    ContextCompat.getColor(this, R.color.warning)
                )
                binding.wifiStatusText.setOnClickListener(null)
            }
        }

        // Update stats
        binding.backedUpCount.text = state.backedUpCount.toString()
        binding.pendingCount.text = state.pendingCount.toString()

        // Show configuration warning if not configured
        binding.configWarningCard.visibility = if (state.isConfigured) View.GONE else View.VISIBLE
    }

    private fun checkPermissions(): Boolean {
        val permissions = getRequiredPermissions()
        return permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(getRequiredPermissions())
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()

        // Media permissions
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        // Location permission (for WiFi SSID)
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)

        return permissions.toTypedArray()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus()
    }
}
