package com.photobackup.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.photobackup.data.local.entities.BackedUpFile
import com.photobackup.data.local.entities.BackupStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface BackedUpFileDao {

    @Query("SELECT * FROM backed_up_files WHERE backupStatus = :status")
    suspend fun getByStatus(status: BackupStatus): List<BackedUpFile>

    @Query("SELECT * FROM backed_up_files WHERE backupStatus = 'COMPLETED'")
    suspend fun getCompletedBackups(): List<BackedUpFile>

    @Query("SELECT * FROM backed_up_files WHERE backupStatus IN ('PENDING', 'FAILED')")
    suspend fun getPendingFiles(): List<BackedUpFile>

    @Query("SELECT mediaStoreId FROM backed_up_files WHERE backupStatus = 'COMPLETED'")
    suspend fun getBackedUpMediaIds(): List<Long>

    @Query("SELECT sha256Hash FROM backed_up_files WHERE backupStatus = 'COMPLETED'")
    suspend fun getBackedUpHashes(): Set<String>

    @Query("SELECT EXISTS(SELECT 1 FROM backed_up_files WHERE mediaStoreId = :mediaStoreId AND backupStatus = 'COMPLETED')")
    suspend fun isBackedUp(mediaStoreId: Long): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM backed_up_files WHERE sha256Hash = :hash AND backupStatus = 'COMPLETED')")
    suspend fun isHashBackedUp(hash: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(file: BackedUpFile): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(files: List<BackedUpFile>)

    @Update
    suspend fun update(file: BackedUpFile)

    @Query("UPDATE backed_up_files SET backupStatus = :status WHERE id = :id")
    suspend fun updateStatus(id: Long, status: BackupStatus)

    @Query("DELETE FROM backed_up_files WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM backed_up_files WHERE backupStatus = 'FAILED'")
    suspend fun deleteFailedBackups()

    @Query("SELECT COUNT(*) FROM backed_up_files WHERE backupStatus = 'COMPLETED'")
    fun getBackedUpCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM backed_up_files WHERE backupStatus IN ('PENDING', 'IN_PROGRESS')")
    fun getPendingCountFlow(): Flow<Int>

    @Query("SELECT COUNT(*) FROM backed_up_files WHERE backupStatus = 'COMPLETED'")
    suspend fun getBackedUpCount(): Int

    @Query("SELECT COUNT(*) FROM backed_up_files WHERE backupStatus IN ('PENDING', 'IN_PROGRESS')")
    suspend fun getPendingCount(): Int
}
