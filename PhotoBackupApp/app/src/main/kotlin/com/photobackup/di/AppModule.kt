package com.photobackup.di

import android.content.Context
import com.photobackup.data.local.BackedUpFileDao
import com.photobackup.data.local.BackupDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideBackupDatabase(
        @ApplicationContext context: Context
    ): BackupDatabase {
        return BackupDatabase.getInstance(context)
    }

    @Provides
    @Singleton
    fun provideBackedUpFileDao(database: BackupDatabase): BackedUpFileDao {
        return database.backedUpFileDao()
    }
}
