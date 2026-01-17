package com.photobackup.util

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
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
        private val KEY_SERVER_HOST = stringPreferencesKey("server_host")
        private val KEY_SERVER_PORT = stringPreferencesKey("server_port")
        private val KEY_AUTO_BACKUP = booleanPreferencesKey("auto_backup")
        private val KEY_BACKUP_PHOTOS = booleanPreferencesKey("backup_photos")
        private val KEY_BACKUP_VIDEOS = booleanPreferencesKey("backup_videos")
        private val KEY_SETUP_COMPLETE = booleanPreferencesKey("setup_complete")
        private val KEY_ALLOW_UNKNOWN_NETWORK = booleanPreferencesKey("allow_unknown_network")
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

    // Get all settings as a snapshot
    suspend fun getSettingsSnapshot(): SettingsSnapshot {
        val prefs = context.dataStore.data.first()
        return SettingsSnapshot(
            homeSSIDs = prefs[KEY_HOME_SSIDS] ?: emptySet(),
            apiKey = prefs[KEY_API_KEY] ?: "",
            serverHost = prefs[KEY_SERVER_HOST] ?: "",
            serverPort = prefs[KEY_SERVER_PORT] ?: "8080",
            autoBackup = prefs[KEY_AUTO_BACKUP] ?: false,
            backupPhotos = prefs[KEY_BACKUP_PHOTOS] ?: true,
            backupVideos = prefs[KEY_BACKUP_VIDEOS] ?: true,
            setupComplete = prefs[KEY_SETUP_COMPLETE] ?: false,
            allowUnknownNetwork = prefs[KEY_ALLOW_UNKNOWN_NETWORK] ?: false
        )
    }
}

data class SettingsSnapshot(
    val homeSSIDs: Set<String>,
    val apiKey: String,
    val serverHost: String,
    val serverPort: String,
    val autoBackup: Boolean,
    val backupPhotos: Boolean,
    val backupVideos: Boolean,
    val setupComplete: Boolean,
    val allowUnknownNetwork: Boolean
)
