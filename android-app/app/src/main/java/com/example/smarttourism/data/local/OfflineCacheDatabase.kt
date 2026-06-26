package com.example.smarttourism.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        CachedCityEntity::class,
        CachedPoiEntity::class,
        CachedLastRouteEntity::class,
        BookmarkedRouteEntity::class,
        CachedRouteSessionEntity::class,
        RouteHistoryEntryEntity::class,
        PendingFeedbackEntity::class,
        PendingRouteSessionSyncEntity::class,
        PendingPoiVisitSyncEntity::class
    ],
    version = 4,
    exportSchema = true
)
abstract class OfflineCacheDatabase : RoomDatabase() {
    abstract fun offlineCacheDao(): OfflineCacheDao

    companion object {
        const val DatabaseName = "smart-tourism-offline.db"

        val Migrations: Array<Migration>
            get() = arrayOf(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)

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

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS bookmarked_routes (
                        bookmarkId TEXT NOT NULL PRIMARY KEY,
                        title TEXT NOT NULL,
                        citySlug TEXT NOT NULL,
                        snapshotJson TEXT NOT NULL,
                        createdAtEpochMs INTEGER NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS route_history_entries (
                        routeId TEXT NOT NULL PRIMARY KEY,
                        historyJson TEXT NOT NULL,
                        updatedAtEpochMs INTEGER NOT NULL
                    )
                    """
                )
            }
        }
    }
}
