package com.photobackup.data.repository

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

enum class BackupSource {
    CAMERA,
    WHATSAPP,
    WECHAT,
    DOWNLOADS
}

data class MediaFile(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    val dateTaken: Long,
    val dateModified: Long,
    val filePath: String,
    val mediaType: MediaType,
    val source: BackupSource = BackupSource.CAMERA
)

enum class MediaType {
    IMAGE,
    VIDEO,
    DOCUMENT,
    OTHER
}

@Singleton
class MediaRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val HASH_BUFFER_SIZE = 8192
    }

    /**
     * Get all photos and videos from MediaStore, sorted by date taken (newest first).
     */
    suspend fun getAllMediaFiles(
        includeImages: Boolean = true,
        includeVideos: Boolean = true
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val mediaFiles = mutableListOf<MediaFile>()

        if (includeImages) {
            mediaFiles.addAll(
                queryMediaStore(
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    mediaType = MediaType.IMAGE
                )
            )
        }

        if (includeVideos) {
            mediaFiles.addAll(
                queryMediaStore(
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    mediaType = MediaType.VIDEO
                )
            )
        }

        mediaFiles.sortedByDescending { it.dateTaken }
    }

    /**
     * Get the date range of all media files (oldest to newest).
     * Returns null if no media files exist.
     */
    suspend fun getMediaDateRange(
        includeImages: Boolean = true,
        includeVideos: Boolean = true
    ): Pair<Long, Long>? = withContext(Dispatchers.IO) {
        android.util.Log.d("MediaRepository", "getMediaDateRange: includeImages=$includeImages, includeVideos=$includeVideos")
        var oldest: Long? = null
        var newest: Long? = null

        val projection = arrayOf(
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED
        )
        val selection = "${MediaStore.MediaColumns.SIZE} > 0"

        fun queryDateRange(uri: Uri) {
            // Query for oldest
            context.contentResolver.query(
                uri, projection, selection, null,
                "${MediaStore.MediaColumns.DATE_TAKEN} ASC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val dateModColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateModified = cursor.getLong(dateModColumn)
                    val effectiveDate = if (dateTaken > 0) dateTaken else dateModified * 1000
                    if (oldest == null || effectiveDate < oldest!!) {
                        oldest = effectiveDate
                    }
                }
            }

            // Query for newest
            context.contentResolver.query(
                uri, projection, selection, null,
                "${MediaStore.MediaColumns.DATE_TAKEN} DESC"
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val dateModColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val dateModified = cursor.getLong(dateModColumn)
                    val effectiveDate = if (dateTaken > 0) dateTaken else dateModified * 1000
                    if (newest == null || effectiveDate > newest!!) {
                        newest = effectiveDate
                    }
                }
            }
        }

        if (includeImages) {
            queryDateRange(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        }
        if (includeVideos) {
            queryDateRange(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
        }

        if (oldest != null && newest != null) {
            android.util.Log.d("MediaRepository", "getMediaDateRange: found oldest=$oldest, newest=$newest")
            Pair(oldest!!, newest!!)
        } else {
            android.util.Log.d("MediaRepository", "getMediaDateRange: no media files found")
            null
        }
    }

    /**
     * Get media files for a specific month.
     */
    suspend fun getMediaFilesForMonth(
        yearMonth: YearMonth,
        includeImages: Boolean = true,
        includeVideos: Boolean = true
    ): List<MediaFile> = withContext(Dispatchers.IO) {
        val startOfMonth = yearMonth.atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endOfMonth = yearMonth.plusMonths(1).atDay(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        android.util.Log.d("MediaRepository", "getMediaFilesForMonth: $yearMonth, startOfMonth=$startOfMonth, endOfMonth=$endOfMonth")

        val mediaFiles = mutableListOf<MediaFile>()

        if (includeImages) {
            mediaFiles.addAll(
                queryMediaStoreByDateRange(
                    uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    mediaType = MediaType.IMAGE,
                    startDateMillis = startOfMonth,
                    endDateMillis = endOfMonth
                )
            )
        }

        if (includeVideos) {
            mediaFiles.addAll(
                queryMediaStoreByDateRange(
                    uri = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                    mediaType = MediaType.VIDEO,
                    startDateMillis = startOfMonth,
                    endDateMillis = endOfMonth
                )
            )
        }

        android.util.Log.d("MediaRepository", "getMediaFilesForMonth: found ${mediaFiles.size} files for $yearMonth")
        mediaFiles.sortedByDescending { it.dateTaken }
    }

    /**
     * Generate list of YearMonth objects from oldest to newest date.
     */
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

    private fun queryMediaStoreByDateRange(
        uri: Uri,
        mediaType: MediaType,
        startDateMillis: Long,
        endDateMillis: Long
    ): List<MediaFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )

        // Filter by size > 0 and date range
        // We check both DATE_TAKEN and DATE_MODIFIED to catch files without DATE_TAKEN
        val selection = """
            ${MediaStore.MediaColumns.SIZE} > 0 AND (
                (${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} < ?) OR
                (${MediaStore.MediaColumns.DATE_TAKEN} = 0 AND ${MediaStore.MediaColumns.DATE_MODIFIED} >= ? AND ${MediaStore.MediaColumns.DATE_MODIFIED} < ?)
            )
        """.trimIndent()
        val selectionArgs = arrayOf(
            startDateMillis.toString(),
            endDateMillis.toString(),
            (startDateMillis / 1000).toString(),  // DATE_MODIFIED is in seconds
            (endDateMillis / 1000).toString()
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"

        val files = mutableListOf<MediaFile>()

        context.contentResolver.query(
            uri, projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val dateModColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = cursor.getLong(dateTakenColumn)
                val dateModified = cursor.getLong(dateModColumn)

                files.add(
                    MediaFile(
                        id = id,
                        contentUri = ContentUris.withAppendedId(uri, id),
                        displayName = cursor.getString(nameColumn) ?: "unknown",
                        size = cursor.getLong(sizeColumn),
                        mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                        dateTaken = if (dateTaken > 0) dateTaken else dateModified * 1000,
                        dateModified = dateModified * 1000,
                        filePath = cursor.getString(dataColumn) ?: "",
                        mediaType = mediaType
                    )
                )
            }
        }

        return files
    }

    private fun queryMediaStore(
        uri: Uri,
        mediaType: MediaType
    ): List<MediaFile> {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.DATA
        )

        val selection = "${MediaStore.MediaColumns.SIZE} > 0"
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} DESC"

        val files = mutableListOf<MediaFile>()

        context.contentResolver.query(
            uri, projection, selection, null, sortOrder
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
            val dateModColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val dateTaken = cursor.getLong(dateTakenColumn)
                val dateModified = cursor.getLong(dateModColumn)

                files.add(
                    MediaFile(
                        id = id,
                        contentUri = ContentUris.withAppendedId(uri, id),
                        displayName = cursor.getString(nameColumn) ?: "unknown",
                        size = cursor.getLong(sizeColumn),
                        mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                        dateTaken = if (dateTaken > 0) dateTaken else dateModified * 1000,
                        dateModified = dateModified * 1000,
                        filePath = cursor.getString(dataColumn) ?: "",
                        mediaType = mediaType
                    )
                )
            }
        }

        return files
    }

    /**
     * Compute SHA-256 hash of a media file.
     */
    suspend fun computeFileHash(contentUri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(HASH_BUFFER_SIZE)

            context.contentResolver.openInputStream(contentUri)?.use { inputStream ->
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

    /**
     * Get input stream for a media file.
     */
    fun openInputStream(contentUri: Uri) = context.contentResolver.openInputStream(contentUri)

    /**
     * Get file size from URI.
     */
    fun getFileSize(contentUri: Uri): Long {
        return context.contentResolver.openFileDescriptor(contentUri, "r")?.use {
            it.statSize
        } ?: 0
    }
}
