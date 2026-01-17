package com.photobackup.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.photobackup.data.local.entities.BackedUpFile

@Database(
    entities = [BackedUpFile::class],
    version = 1,
    exportSchema = false
)
abstract class BackupDatabase : RoomDatabase() {

    abstract fun backedUpFileDao(): BackedUpFileDao

    companion object {
        private const val DATABASE_NAME = "photo_backup_db"

        @Volatile
        private var INSTANCE: BackupDatabase? = null

        fun getInstance(context: Context): BackupDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context): BackupDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                BackupDatabase::class.java,
                DATABASE_NAME
            ).build()
        }
    }
}
