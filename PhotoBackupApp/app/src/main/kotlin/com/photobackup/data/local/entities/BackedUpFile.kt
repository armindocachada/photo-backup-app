package com.photobackup.data.local.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Entity representing a file that has been backed up to the server.
 */
@Entity(
    tableName = "backed_up_files",
    indices = [
        Index(value = ["mediaStoreId"]),
        Index(value = ["sha256Hash"])
    ]
)
data class BackedUpFile(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    /** MediaStore ID of the file */
    val mediaStoreId: Long,

    /** Content URI string for accessing the file */
    val contentUri: String,

    /** Original file path on device */
    val filePath: String,

    /** Display name of the file */
    val fileName: String,

    /** File size in bytes */
    val fileSize: Long,

    /** MIME type (e.g., image/jpeg, video/mp4) */
    val mimeType: String,

    /** When the photo/video was taken (epoch millis) */
    val dateTaken: Long,

    /** When the file was last modified (epoch millis) */
    val dateModified: Long,

    /** SHA-256 hash of the file content */
    val sha256Hash: String,

    /** When the backup was completed (epoch millis) */
    val backedUpAt: Long,

    /** Path where file is stored on server */
    val serverPath: String,

    /** Current backup status */
    val backupStatus: BackupStatus = BackupStatus.COMPLETED
)

enum class BackupStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}
