package com.example.smarttourism.di

import android.content.Context
import androidx.room.Room
import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.local.OfflineCacheDatabase
import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal object OfflineCacheModule {
    @Provides
    @Singleton
    fun provideGson(): Gson = Gson()

    @Provides
    @Singleton
    fun provideOfflineCacheDatabase(
        @ApplicationContext context: Context
    ): OfflineCacheDatabase =
        Room.databaseBuilder(
            context.applicationContext,
            OfflineCacheDatabase::class.java,
            OfflineCacheDatabase.DatabaseName
        )
            .addMigrations(*OfflineCacheDatabase.Migrations)
            .build()

    @Provides
    fun provideOfflineCacheDao(database: OfflineCacheDatabase): OfflineCacheDao =
        database.offlineCacheDao()
}
