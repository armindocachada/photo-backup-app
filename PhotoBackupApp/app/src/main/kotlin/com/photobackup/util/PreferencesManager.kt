package com.photobackup.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class PreferencesManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private val KEY_HOME_SSIDS = stringSetPreferencesKey("home_ssids")
        private val KEY_API_KEY = stringPreferencesKey("api_key")
        private val KEY_SERVER_ID = stringPreferencesKey("server_id")
        private val KEY_SERVER_HOST = stringPreferencesKey("server_host")
        private val KEY_SERVER_PORT = stringPreferencesKey("server_port")
        private val KEY_AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        private val KEY_BACKUP_PHOTOS = booleanPreferencesKey("backup_photos")
        private val KEY_BACKUP_VIDEOS = booleanPreferencesKey("backup_videos")
        private val KEY_BACKUP_WHATSAPP = booleanPreferencesKey("backup_whatsapp")
        private val KEY_BACKUP_WECHAT = booleanPreferencesKey("backup_wechat")
        private val KEY_BACKUP_DOWNLOADS = booleanPreferencesKey("backup_downloads")
        private val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        private val KEY_ALLOW_UNKNOWN_NETWORK = booleanPreferencesKey("allow_unknown_network")

        // Backup progress keys (for UI to show progress after app restart)
        private val KEY_BACKUP_PROGRESS_CURRENT_MONTH = stringPreferencesKey("backup_progress_current_month")
        private val KEY_BACKUP_PROGRESS_TOTAL_MONTHS = intPreferencesKey("backup_progress_total_months")
        private val KEY_BACKUP_PROGRESS_CURRENT_FILE = stringPreferencesKey("backup_progress_current_file")
        private val KEY_BACKUP_PROGRESS_FILE_INDEX = intPreferencesKey("backup_progress_file_index")
        private val KEY_BACKUP_PROGRESS_FILES_IN_MONTH = intPreferencesKey("backup_progress_files_in_month")
        private val KEY_BACKUP_PROGRESS_SUCCESS_COUNT = intPreferencesKey("backup_progress_success_count")
        private val KEY_BACKUP_PROGRESS_SKIPPED_COUNT = intPreferencesKey("backup_progress_skipped_count")
        private val KEY_BACKUP_PROGRESS_FAIL_COUNT = intPreferencesKey("backup_progress_fail_count")
    }

    // Home WiFi SSIDs
    val homeSSIDs: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_HOME_SSIDS] ?: emptySet()
    }

    suspend fun setHomeSSIDs(ssids: Set<String>) {
        context.dataStore.edit { prefs ->
            prefs[KEY_HOME_SSIDS] = ssids
        }
    }

    suspend fun addHomeSSID(ssid: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_HOME_SSIDS] ?: emptySet()
            prefs[KEY_HOME_SSIDS] = current + ssid
        }
    }

    // API Key
    val apiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_API_KEY] ?: ""
    }

    suspend fun setApiKey(key: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_API_KEY] = key
        }
    }

    suspend fun getApiKeySync(): String {
        return context.dataStore.data.first()[KEY_API_KEY] ?: ""
    }

    // Server ID (from QR code pairing)
    val serverId: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_ID] ?: ""
    }

    suspend fun setServerId(id: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_ID] = id
        }
    }

    suspend fun getServerIdSync(): String {
        return context.dataStore.data.first()[KEY_SERVER_ID] ?: ""
    }

    // Server address (optional, for manual configuration)
    val serverHost: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_HOST] ?: ""
    }

    val serverPort: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SERVER_PORT] ?: "8080"
    }

    suspend fun setServerAddress(host: String, port: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_HOST] = host
            prefs[KEY_SERVER_PORT] = port
        }
    }

    /**
     * Save pairing data from QR code scan.
     * Only stores serverId and apiKey - the server IP is discovered via mDNS.
     */
    suspend fun savePairingData(serverId: String, apiKey: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SERVER_ID] = serverId
            prefs[KEY_API_KEY] = apiKey
        }
    }

    /**
     * Check if the app has been paired with a server.
     */
    suspend fun isPaired(): Boolean {
        val prefs = context.dataStore.data.first()
        val serverId = prefs[KEY_SERVER_ID] ?: ""
        val apiKey = prefs[KEY_API_KEY] ?: ""
        return serverId.isNotEmpty() && apiKey.isNotEmpty()
    }

    // Auto backup setting (default to false - user must explicitly enable)
    val autoBackup: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_AUTO_BACKUP] ?: false
    }

    suspend fun setAutoBackup(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_AUTO_BACKUP] = enabled
        }
    }

    // Backup photos setting
    val backupPhotos: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_PHOTOS] ?: true
    }

    suspend fun setBackupPhotos(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_PHOTOS] = enabled
        }
    }

    // Backup videos setting
    val backupVideos: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_VIDEOS] ?: true
    }

    suspend fun setBackupVideos(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_VIDEOS] = enabled
        }
    }

    // Backup WhatsApp setting
    val backupWhatsApp: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_WHATSAPP] ?: false
    }

    suspend fun setBackupWhatsApp(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_WHATSAPP] = enabled
        }
    }

    // Backup WeChat setting
    val backupWeChat: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_WECHAT] ?: false
    }

    suspend fun setBackupWeChat(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_WECHAT] = enabled
        }
    }

    // Backup Downloads setting
    val backupDownloads: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BACKUP_DOWNLOADS] ?: false
    }

    suspend fun setBackupDownloads(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_DOWNLOADS] = enabled
        }
    }

    // Setup complete flag
    val setupComplete: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SETUP_COMPLETE] ?: false
    }

    suspend fun setSetupComplete(complete: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SETUP_COMPLETE] = complete
        }
    }

    // Allow backup on unknown network (when manual server is configured)
    val allowUnknownNetwork: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ALLOW_UNKNOWN_NETWORK] ?: false
    }

    suspend fun setAllowUnknownNetwork(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ALLOW_UNKNOWN_NETWORK] = enabled
        }
    }

    // Backup progress (for UI to show progress after app restart)
    suspend fun saveBackupProgress(progress: BackupProgress) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BACKUP_PROGRESS_CURRENT_MONTH] = progress.currentMonth
            prefs[KEY_BACKUP_PROGRESS_TOTAL_MONTHS] = progress.totalMonths
            prefs[KEY_BACKUP_PROGRESS_CURRENT_FILE] = progress.currentFile
            prefs[KEY_BACKUP_PROGRESS_FILE_INDEX] = progress.fileIndex
            prefs[KEY_BACKUP_PROGRESS_FILES_IN_MONTH] = progress.filesInMonth
            prefs[KEY_BACKUP_PROGRESS_SUCCESS_COUNT] = progress.successCount
            prefs[KEY_BACKUP_PROGRESS_SKIPPED_COUNT] = progress.skippedCount
            prefs[KEY_BACKUP_PROGRESS_FAIL_COUNT] = progress.failCount
        }
    }

    suspend fun clearBackupProgress() {
        context.dataStore.edit { prefs ->
            prefs.remove(KEY_BACKUP_PROGRESS_CURRENT_MONTH)
            prefs.remove(KEY_BACKUP_PROGRESS_TOTAL_MONTHS)
            prefs.remove(KEY_BACKUP_PROGRESS_CURRENT_FILE)
            prefs.remove(KEY_BACKUP_PROGRESS_FILE_INDEX)
            prefs.remove(KEY_BACKUP_PROGRESS_FILES_IN_MONTH)
            prefs.remove(KEY_BACKUP_PROGRESS_SUCCESS_COUNT)
            prefs.remove(KEY_BACKUP_PROGRESS_SKIPPED_COUNT)
            prefs.remove(KEY_BACKUP_PROGRESS_FAIL_COUNT)
        }
    }

    suspend fun getBackupProgress(): BackupProgress? {
        val prefs = context.dataStore.data.first()
        val currentMonth = prefs[KEY_BACKUP_PROGRESS_CURRENT_MONTH] ?: return null
        return BackupProgress(
            currentMonth = currentMonth,
            totalMonths = prefs[KEY_BACKUP_PROGRESS_TOTAL_MONTHS] ?: 0,
            currentFile = prefs[KEY_BACKUP_PROGRESS_CURRENT_FILE] ?: "",
            fileIndex = prefs[KEY_BACKUP_PROGRESS_FILE_INDEX] ?: 0,
            filesInMonth = prefs[KEY_BACKUP_PROGRESS_FILES_IN_MONTH] ?: 0,
            successCount = prefs[KEY_BACKUP_PROGRESS_SUCCESS_COUNT] ?: 0,
            skippedCount = prefs[KEY_BACKUP_PROGRESS_SKIPPED_COUNT] ?: 0,
            failCount = prefs[KEY_BACKUP_PROGRESS_FAIL_COUNT] ?: 0
        )
    }

    // Get all settings as a snapshot
    suspend fun getSettingsSnapshot(): SettingsSnapshot {
        val prefs = context.dataStore.data.first()
        return SettingsSnapshot(
            homeSSIDs = prefs[KEY_HOME_SSIDS] ?: emptySet(),
            apiKey = prefs[KEY_API_KEY] ?: "",
            serverId = prefs[KEY_SERVER_ID] ?: "",
            serverHost = prefs[KEY_SERVER_HOST] ?: "",
            serverPort = prefs[KEY_SERVER_PORT] ?: "8080",
            autoBackup = prefs[KEY_AUTO_BACKUP] ?: false,
            backupPhotos = prefs[KEY_BACKUP_PHOTOS] ?: true,
            backupVideos = prefs[KEY_BACKUP_VIDEOS] ?: true,
            backupWhatsApp = prefs[KEY_BACKUP_WHATSAPP] ?: false,
            backupWeChat = prefs[KEY_BACKUP_WECHAT] ?: false,
            backupDownloads = prefs[KEY_BACKUP_DOWNLOADS] ?: false,
            setupComplete = prefs[KEY_SETUP_COMPLETE] ?: false,
            allowUnknownNetwork = prefs[KEY_ALLOW_UNKNOWN_NETWORK] ?: false
        )
    }
}

data class SettingsSnapshot(
    val homeSSIDs: Set<String>,
    val apiKey: String,
    val serverId: String,
    val serverHost: String,
    val serverPort: String,
    val autoBackup: Boolean,
    val backupPhotos: Boolean,
    val backupVideos: Boolean,
    val backupWhatsApp: Boolean,
    val backupWeChat: Boolean,
    val backupDownloads: Boolean,
    val setupComplete: Boolean,
    val allowUnknownNetwork: Boolean
)

data class BackupProgress(
    val currentMonth: String,
    val totalMonths: Int,
    val currentFile: String,
    val fileIndex: Int,
    val filesInMonth: Int,
    val successCount: Int,
    val skippedCount: Int,
    val failCount: Int
)
