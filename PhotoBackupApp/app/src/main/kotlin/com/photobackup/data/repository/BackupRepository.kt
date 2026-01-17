package com.photobackup.data.repository

import android.content.Context
import android.os.Build
import com.photobackup.data.local.BackedUpFileDao
import com.photobackup.data.local.entities.BackedUpFile
import com.photobackup.data.local.entities.BackupStatus
import com.photobackup.data.remote.BackupApiService
import com.photobackup.data.remote.FileCheckRequest
import com.photobackup.network.ServerInfo
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class BackupResult {
    data object Success : BackupResult()
    data object AlreadyExists : BackupResult()
    data class Error(val message: String) : BackupResult()
}

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backedUpFileDao: BackedUpFileDao,
    private val mediaRepository: MediaRepository
) {
    private var apiService: BackupApiService? = null
    private var currentServerInfo: ServerInfo? = null

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    private val deviceName: String by lazy {
        "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    fun setServer(serverInfo: ServerInfo, apiKey: String) {
        currentServerInfo = serverInfo
        apiService = Retrofit.Builder()
            .baseUrl(serverInfo.baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(BackupApiService::class.java)
    }

    suspend fun verifyConnection(apiKey: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val response = apiService?.healthCheck(apiKey)
            response?.isSuccessful == true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get list of media files that need to be backed up.
     */
    suspend fun getFilesToBackup(
        includeImages: Boolean = true,
        includeVideos: Boolean = true
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val allMedia = mediaRepository.getAllMediaFiles(includeImages, includeVideos)
        val backedUpIds = backedUpFileDao.getBackedUpMediaIds().toSet()

        allMedia.filter { it.id !in backedUpIds }
    }

    /**
     * Check with server which files are already backed up.
     */
    suspend fun checkFilesWithServer(
        files: List<MediaFile>,
        apiKey: String
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val api = apiService ?: return@withContext files

        // Compute hashes for files
        val fileHashes = files.mapNotNull { file ->
            mediaRepository.computeFileHash(file.contentUri)?.let { hash ->
                file to hash
            }
        }

        if (fileHashes.isEmpty()) return@withContext emptyList()

        try {
            val response = api.checkFiles(
                apiKey = apiKey,
                request = FileCheckRequest(fileHashes.map { it.second })
            )

            if (response.isSuccessful) {
                val existingHashes = response.body()?.existing?.toSet() ?: emptySet()
                fileHashes
                    .filter { (_, hash) -> hash !in existingHashes }
                    .map { (file, _) -> file }
            } else {
                files
            }
        } catch (e: Exception) {
            files
        }
    }

    /**
     * Upload a single file to the backup server.
     */
    suspend fun uploadFile(
        file: MediaFile,
        apiKey: String,
        onProgress: ((Int) -> Unit)? = null
    ): BackupResult = withContext(Dispatchers.IO) {
        val api = apiService ?: return@withContext BackupResult.Error("Server not configured")

        try {
            // Compute file hash
            val fileHash = mediaRepository.computeFileHash(file.contentUri)
                ?: return@withContext BackupResult.Error("Could not compute file hash")

            // Check if already backed up locally
            if (backedUpFileDao.isHashBackedUp(fileHash)) {
                return@withContext BackupResult.AlreadyExists
            }

            // Read file content
            val inputStream = mediaRepository.openInputStream(file.contentUri)
                ?: return@withContext BackupResult.Error("Could not open file")

            val fileBytes = inputStream.use { it.readBytes() }
            val mediaType = file.mimeType.toMediaType()

            val filePart = MultipartBody.Part.createFormData(
                "file",
                file.displayName,
                fileBytes.toRequestBody(mediaType)
            )

            val response = api.uploadFile(
                apiKey = apiKey,
                file = filePart,
                fileHash = fileHash.toRequestBody("text/plain".toMediaType()),
                originalPath = file.filePath.toRequestBody("text/plain".toMediaType()),
                dateTaken = file.dateTaken.toString().toRequestBody("text/plain".toMediaType()),
                mimeType = file.mimeType.toRequestBody("text/plain".toMediaType()),
                deviceName = deviceName.toRequestBody("text/plain".toMediaType())
            )

            if (response.isSuccessful) {
                val body = response.body()
                when (body?.status) {
                    "success" -> {
                        // Record in local database
                        backedUpFileDao.insert(
                            BackedUpFile(
                                mediaStoreId = file.id,
                                contentUri = file.contentUri.toString(),
                                filePath = file.filePath,
                                fileName = file.displayName,
                                fileSize = file.size,
                                mimeType = file.mimeType,
                                dateTaken = file.dateTaken,
                                dateModified = file.dateModified,
                                sha256Hash = fileHash,
                                backedUpAt = System.currentTimeMillis(),
                                serverPath = body.path ?: "",
                                backupStatus = BackupStatus.COMPLETED
                            )
                        )
                        BackupResult.Success
                    }
                    "exists" -> BackupResult.AlreadyExists
                    else -> BackupResult.Error(body?.message ?: "Unknown error")
                }
            } else {
                BackupResult.Error("Upload failed: ${response.code()}")
            }
        } catch (e: Exception) {
            BackupResult.Error(e.message ?: "Unknown error")
        }
    }

    // Flow for UI updates
    fun getBackedUpCountFlow(): Flow<Int> = backedUpFileDao.getBackedUpCountFlow()
    fun getPendingCountFlow(): Flow<Int> = backedUpFileDao.getPendingCountFlow()

    suspend fun getBackedUpCount(): Int = backedUpFileDao.getBackedUpCount()
    suspend fun getPendingCount(): Int = backedUpFileDao.getPendingCount()
}
