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
import javax.inject.Inject
import javax.inject.Singleton

data class MediaFile(
    val id: Long,
    val contentUri: Uri,
    val displayName: String,
    val size: Long,
    val mimeType: String,
    val dateTaken: Long,
    val dateModified: Long,
    val filePath: String,
    val mediaType: MediaType
)

enum class MediaType {
    IMAGE,
    VIDEO
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
