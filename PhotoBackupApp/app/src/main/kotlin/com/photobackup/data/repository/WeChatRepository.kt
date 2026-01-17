package com.photobackup.data.repository

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WeChatRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val HASH_BUFFER_SIZE = 8192

        // WeChat media directories
        private val WECHAT_MEDIA_PATHS = listOf(
            "tencent/MicroMsg/WeiXin",
            "Android/data/com.tencent.mm/MicroMsg/WeiXin",
            "tencent/MicroMsg/Download",
            "Android/data/com.tencent.mm/MicroMsg/Download"
        )

        // Image extensions
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif")
        // Video extensions
        private val VIDEO_EXTENSIONS = setOf("mp4", "3gp", "mkv", "avi", "mov", "webm")
        // Document extensions
        private val DOCUMENT_EXTENSIONS = setOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "zip", "rar")
    }

    suspend fun getAllMediaFiles(): List<MediaFile> = withContext(Dispatchers.IO) {
        val mediaFiles = mutableListOf<MediaFile>()
        val externalStorage = Environment.getExternalStorageDirectory()
        android.util.Log.d("WeChatRepository", "Scanning for WeChat files in: $externalStorage")

        for (relativePath in WECHAT_MEDIA_PATHS) {
            val mediaDir = File(externalStorage, relativePath)
            android.util.Log.d("WeChatRepository", "Checking path: ${mediaDir.absolutePath}, exists=${mediaDir.exists()}, isDir=${mediaDir.isDirectory}")
            if (mediaDir.exists() && mediaDir.isDirectory) {
                scanDirectory(mediaDir, mediaFiles)
            }
        }

        android.util.Log.d("WeChatRepository", "Found ${mediaFiles.size} WeChat media files total")
        mediaFiles.sortedByDescending { it.dateTaken }
    }

    suspend fun getMediaFilesForMonth(yearMonth: YearMonth): List<MediaFile> = withContext(Dispatchers.IO) {
        val startOfMonth = yearMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfMonth = yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

        getAllMediaFiles().filter { file ->
            file.dateTaken >= startOfMonth && file.dateTaken < endOfMonth
        }
    }

    suspend fun getMediaDateRange(): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        val allFiles = getAllMediaFiles()
        if (allFiles.isEmpty()) return@withContext null

        val oldest = allFiles.minOf { it.dateTaken }
        val newest = allFiles.maxOf { it.dateTaken }
        Pair(oldest, newest)
    }

    fun generateMonthsInRange(oldestMillis: Long, newestMillis: Long): List<YearMonth> {
        val months = mutableListOf<YearMonth>()
        val oldestDate = LocalDate.ofEpochDay(oldestMillis / (24 * 60 * 60 * 1000))
        val newestDate = LocalDate.ofEpochDay(newestMillis / (24 * 60 * 60 * 1000))

        var current = YearMonth.of(oldestDate.year, oldestDate.month)
        val end = YearMonth.of(newestDate.year, newestDate.month)

        while (!current.isAfter(end)) {
            months.add(current)
            current = current.plusMonths(1)
        }

        return months
    }

    private fun scanDirectory(dir: File, mediaFiles: MutableList<MediaFile>) {
        val files = dir.listFiles() ?: return

        for (file in files) {
            if (file.isDirectory) {
                scanDirectory(file, mediaFiles)
            } else if (file.isFile && file.length() > 0) {
                val extension = file.extension.lowercase()
                val mediaType = getMediaType(extension)

                if (mediaType != null) {
                    val mimeType = getMimeType(file.name)
                    mediaFiles.add(
                        MediaFile(
                            id = file.absolutePath.hashCode().toLong(),
                            contentUri = file.toUri(),
                            displayName = file.name,
                            size = file.length(),
                            mimeType = mimeType,
                            dateTaken = file.lastModified(),
                            dateModified = file.lastModified(),
                            filePath = file.absolutePath,
                            mediaType = mediaType,
                            source = BackupSource.WECHAT
                        )
                    )
                }
            }
        }
    }

    private fun getMediaType(extension: String): MediaType? {
        return when {
            extension in IMAGE_EXTENSIONS -> MediaType.IMAGE
            extension in VIDEO_EXTENSIONS -> MediaType.VIDEO
            extension in DOCUMENT_EXTENSIONS -> MediaType.DOCUMENT
            else -> null
        }
    }

    private fun getMimeType(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension)
            ?: "application/octet-stream"
    }

    suspend fun computeFileHash(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val file = File(uri.path ?: return@withContext null)
            if (!file.exists()) return@withContext null

            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_SIZE)

            file.inputStream().use { inputStream ->
                var bytesRead: Int
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }

            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun openInputStream(uri: Uri): java.io.InputStream? {
        return try {
            val path = uri.path ?: return null
            val file = File(path)
            if (file.exists()) file.inputStream() else null
        } catch (e: Exception) {
            null
        }
    }

    fun getFileSize(uri: Uri): Long {
        return try {
            val file = File(uri.path ?: return 0)
            if (file.exists()) file.length() else 0
        } catch (e: Exception) {
            0
        }
    }
}
