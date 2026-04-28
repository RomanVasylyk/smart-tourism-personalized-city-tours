package com.example.smarttourism.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedCityEntity::class,
        CachedPoiEntity::class,
        CachedLastRouteEntity::class,
        CachedRouteSessionEntity::class,
        PendingFeedbackEntity::class,
        PendingRouteSessionSyncEntity::class,
        PendingPoiVisitSyncEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class OfflineCacheDatabase : RoomDatabase() {
    abstract fun offlineCacheDao(): OfflineCacheDao

    companion object {
        @Volatile
        private var instance: OfflineCacheDatabase? = null

        fun getInstance(context: Context): OfflineCacheDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    OfflineCacheDatabase::class.java,
                    "smart-tourism-offline.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build().also { created ->
                    instance = created
                }
            }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    ALTER TABLE pending_feedback
                    ADD COLUMN syncStatus TEXT NOT NULL DEFAULT 'pending'
                    """
                )
                database.execSQL(
                    """
                    ALTER TABLE pending_feedback
                    ADD COLUMN lastSyncAttemptAtEpochMs INTEGER
                    """
                )
                database.execSQL(
                    """
                    ALTER TABLE pending_feedback
                    ADD COLUMN retryCount INTEGER NOT NULL DEFAULT 0
                    """
                )
                database.execSQL(
                    """
                    ALTER TABLE pending_feedback
                    ADD COLUMN updatedAtEpochMs INTEGER NOT NULL DEFAULT 0
                    """
                )
                database.execSQL(
                    """
                    UPDATE pending_feedback
                    SET updatedAtEpochMs = createdAtEpochMs
                    WHERE updatedAtEpochMs = 0
                    """
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_route_session_sync (
                        sessionId TEXT NOT NULL PRIMARY KEY,
                        requestJson TEXT NOT NULL,
                        syncStatus TEXT NOT NULL,
                        lastSyncAttemptAtEpochMs INTEGER,
                        retryCount INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """
                )
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS pending_poi_visit_sync (
                        requestKey TEXT NOT NULL PRIMARY KEY,
                        sessionId TEXT NOT NULL,
                        poiId INTEGER NOT NULL,
                        requestJson TEXT NOT NULL,
                        syncStatus TEXT NOT NULL,
                        lastSyncAttemptAtEpochMs INTEGER,
                        retryCount INTEGER NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """
                )
            }
        }
    }
}
