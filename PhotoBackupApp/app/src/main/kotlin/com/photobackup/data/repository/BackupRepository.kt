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
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSink
import okio.source
import java.io.InputStream
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.YearMonth
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

sealed class BackupResult {
    data object Success : BackupResult()
    data object AlreadyExists : BackupResult()
    data class Error(val message: String) : BackupResult()
}

/**
 * A RequestBody that streams from an InputStream to avoid loading large files into memory.
 */
private class StreamingRequestBody(
    private val inputStream: InputStream,
    private val contentType: MediaType?,
    private val contentLength: Long
) : RequestBody() {
    override fun contentType(): MediaType? = contentType
    override fun contentLength(): Long = contentLength

    override fun writeTo(sink: BufferedSink) {
        inputStream.source().use { source ->
            sink.writeAll(source)
        }
    }
}

@Singleton
class BackupRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backedUpFileDao: BackedUpFileDao,
    private val mediaRepository: MediaRepository,
    private val whatsAppRepository: WhatsAppRepository,
    private val weChatRepository: WeChatRepository,
    private val downloadsRepository: DownloadsRepository
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
            android.util.Log.d("BackupRepository", "verifyConnection: baseUrl=${currentServerInfo?.baseUrl}, apiKey=$apiKey")
            val response = apiService?.healthCheck(apiKey)
            android.util.Log.d("BackupRepository", "verifyConnection: response code=${response?.code()}, body=${response?.body()}")
            response?.isSuccessful == true
        } catch (e: Exception) {
            android.util.Log.e("BackupRepository", "verifyConnection failed", e)
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
     * Get list of media files for a specific month that need to be backed up.
     */
    suspend fun getFilesToBackupByMonth(
        yearMonth: YearMonth,
        includeImages: Boolean = true,
        includeVideos: Boolean = true
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val monthMedia = mediaRepository.getMediaFilesForMonth(yearMonth, includeImages, includeVideos)
        val backedUpIds = backedUpFileDao.getBackedUpMediaIds().toSet()

        monthMedia.filter { it.id !in backedUpIds }
    }

    /**
     * Get the date range of all media files.
     */
    suspend fun getMediaDateRange(
        includeImages: Boolean = true,
        includeVideos: Boolean = true
    ): Pair<Long, Long>? {
        return mediaRepository.getMediaDateRange(includeImages, includeVideos)
    }

    /**
     * Get date range for a specific backup source.
     */
    suspend fun getSourceDateRange(source: BackupSource): Pair<Long, Long>? {
        return when (source) {
            BackupSource.CAMERA -> mediaRepository.getMediaDateRange()
            BackupSource.WHATSAPP -> whatsAppRepository.getMediaDateRange()
            BackupSource.WECHAT -> weChatRepository.getMediaDateRange()
            BackupSource.DOWNLOADS -> downloadsRepository.getMediaDateRange()
        }
    }

    /**
     * Get files to backup for a specific source and month.
     */
    suspend fun getFilesToBackupBySourceAndMonth(
        source: BackupSource,
        yearMonth: YearMonth
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        android.util.Log.d("BackupRepository", "getFilesToBackupBySourceAndMonth: source=$source, month=$yearMonth")
        val monthMedia = when (source) {
            BackupSource.CAMERA -> mediaRepository.getMediaFilesForMonth(yearMonth)
            BackupSource.WHATSAPP -> whatsAppRepository.getMediaFilesForMonth(yearMonth)
            BackupSource.WECHAT -> weChatRepository.getMediaFilesForMonth(yearMonth)
            BackupSource.DOWNLOADS -> downloadsRepository.getMediaFilesForMonth(yearMonth)
        }
        android.util.Log.d("BackupRepository", "getFilesToBackupBySourceAndMonth: found ${monthMedia.size} files for $source in $yearMonth")

        // For non-camera sources, filter by both ID and file path to avoid duplicates
        val backedUpIds = backedUpFileDao.getBackedUpMediaIds().toSet()
        val result = monthMedia.filter { it.id !in backedUpIds }
        android.util.Log.d("BackupRepository", "getFilesToBackupBySourceAndMonth: ${result.size} files need backup after filtering")
        result
    }

    /**
     * Generate list of months from oldest to newest.
     */
    fun generateMonthsInRange(oldestMillis: Long, newestMillis: Long): List<YearMonth> {
        return mediaRepository.generateMonthsInRange(oldestMillis, newestMillis)
    }

    /**
     * Compute file hash based on source.
     */
    private suspend fun computeFileHash(file: MediaFile): String? {
        return when (file.source) {
            BackupSource.CAMERA -> mediaRepository.computeFileHash(file.contentUri)
            BackupSource.WHATSAPP -> whatsAppRepository.computeFileHash(file.contentUri)
            BackupSource.WECHAT -> weChatRepository.computeFileHash(file.contentUri)
            BackupSource.DOWNLOADS -> downloadsRepository.computeFileHash(file.contentUri)
        }
    }

    /**
     * Open input stream based on source.
     */
    private fun openInputStream(file: MediaFile): java.io.InputStream? {
        return when (file.source) {
            BackupSource.CAMERA -> mediaRepository.openInputStream(file.contentUri)
            BackupSource.WHATSAPP -> whatsAppRepository.openInputStream(file.contentUri)
            BackupSource.WECHAT -> weChatRepository.openInputStream(file.contentUri)
            BackupSource.DOWNLOADS -> downloadsRepository.openInputStream(file.contentUri)
        }
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
            android.util.Log.d("BackupRepository", "uploadFile: Starting ${file.displayName} from ${file.source}")

            // Compute file hash using source-appropriate method
            val fileHash = computeFileHash(file)
            if (fileHash == null) {
                android.util.Log.e("BackupRepository", "uploadFile: Could not compute hash for ${file.displayName}")
                return@withContext BackupResult.Error("Could not compute file hash")
            }
            android.util.Log.d("BackupRepository", "uploadFile: Hash=${fileHash.take(16)}...")

            // Check if already backed up locally
            if (backedUpFileDao.isHashBackedUp(fileHash)) {
                android.util.Log.d("BackupRepository", "uploadFile: Already in local DB")
                return@withContext BackupResult.AlreadyExists
            }

            // Open file stream for upload using source-appropriate method
            android.util.Log.d("BackupRepository", "uploadFile: Opening file stream...")
            val inputStream = openInputStream(file)
            if (inputStream == null) {
                android.util.Log.e("BackupRepository", "uploadFile: Could not open file")
                return@withContext BackupResult.Error("Could not open file")
            }

            android.util.Log.d("BackupRepository", "uploadFile: File size=${file.size} bytes")
            val mediaType = file.mimeType.toMediaType()

            // Use streaming request body to avoid loading entire file into memory
            val streamingBody = StreamingRequestBody(inputStream, mediaType, file.size)
            val filePart = MultipartBody.Part.createFormData(
                "file",
                file.displayName,
                streamingBody
            )

            // Map BackupSource to server source string
            val sourceString = when (file.source) {
                BackupSource.CAMERA -> "camera"
                BackupSource.WHATSAPP -> "whatsapp"
                BackupSource.WECHAT -> "wechat"
                BackupSource.DOWNLOADS -> "downloads"
            }

            android.util.Log.d("BackupRepository", "uploadFile: Sending to server with source=$sourceString...")
            val response = api.uploadFile(
                apiKey = apiKey,
                file = filePart,
                fileHash = fileHash.toRequestBody("text/plain".toMediaType()),
                originalPath = file.filePath.toRequestBody("text/plain".toMediaType()),
                dateTaken = file.dateTaken.toString().toRequestBody("text/plain".toMediaType()),
                mimeType = file.mimeType.toRequestBody("text/plain".toMediaType()),
                deviceName = deviceName.toRequestBody("text/plain".toMediaType()),
                source = sourceString.toRequestBody("text/plain".toMediaType())
            )

            android.util.Log.d("BackupRepository", "uploadFile: Response code=${response.code()}, body=${response.body()}")

            if (response.isSuccessful) {
                val body = response.body()
                when (body?.status) {
                    "success" -> {
                        android.util.Log.d("BackupRepository", "uploadFile: Success! Path=${body.path}")
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
                    "exists" -> {
                        android.util.Log.d("BackupRepository", "uploadFile: Server says file exists")
                        BackupResult.AlreadyExists
                    }
                    else -> {
                        android.util.Log.e("BackupRepository", "uploadFile: Unexpected status: ${body?.status}, message: ${body?.message}")
                        BackupResult.Error(body?.message ?: "Unknown error")
                    }
                }
            } else {
                android.util.Log.e("BackupRepository", "uploadFile: HTTP error ${response.code()}: ${response.errorBody()?.string()}")
                BackupResult.Error("Upload failed: ${response.code()}")
            }
        } catch (e: Exception) {
            android.util.Log.e("BackupRepository", "uploadFile: Exception", e)
            BackupResult.Error(e.message ?: "Unknown error")
        }
    }

    // Flow for UI updates
    fun getBackedUpCountFlow(): Flow<Int> = backedUpFileDao.getBackedUpCountFlow()
    fun getPendingCountFlow(): Flow<Int> = backedUpFileDao.getPendingCountFlow()

    suspend fun getBackedUpCount(): Int = backedUpFileDao.getBackedUpCount()
    suspend fun getPendingCount(): Int = backedUpFileDao.getPendingCount()
}
