package com.example.smarttourism.data.local

import android.content.Context
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OfflineCacheDatabaseMigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        OfflineCacheDatabase::class.java,
        emptyList(),
        FrameworkSQLiteOpenHelperFactory()
    )

    @Test
    fun migrateFrom1To4_preservesExistingDataAndAddsSyncTables() {
        createVersion1Database()

        val migrated = helper.runMigrationsAndValidate(
            TestDatabaseName,
            4,
            true,
            *OfflineCacheDatabase.Migrations
        )
        helper.closeWhenFinished(migrated)

        migrated.query(
            """
            SELECT syncStatus, retryCount, updatedAtEpochMs
            FROM pending_feedback
            WHERE sessionId = 'session-1'
            """
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals("pending", cursor.getString(cursor.getColumnIndexOrThrow("syncStatus")))
            assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow("retryCount")))
            assertEquals(1234L, cursor.getLong(cursor.getColumnIndexOrThrow("updatedAtEpochMs")))
        }

        migrated.query(
            """
            SELECT name FROM sqlite_master
            WHERE type = 'table'
            AND name IN ('bookmarked_routes', 'route_history_entries', 'pending_route_session_sync', 'pending_poi_visit_sync')
            """
        ).use { cursor ->
            assertEquals(4, cursor.count)
        }
    }

    private fun createVersion1Database() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase(TestDatabaseName)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(TestDatabaseName)
            .callback(
                object : SupportSQLiteOpenHelper.Callback(1) {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        createVersion1Schema(db)
                        seedVersion1Data(db)
                    }

                    override fun onUpgrade(
                        db: SupportSQLiteDatabase,
                        oldVersion: Int,
                        newVersion: Int
                    ) = Unit
                }
            )
            .build()
        val openHelper = FrameworkSQLiteOpenHelperFactory().create(config)
        openHelper.writableDatabase.close()
        openHelper.close()
    }

    private fun createVersion1Schema(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_cities (
                slug TEXT NOT NULL PRIMARY KEY,
                cityId INTEGER NOT NULL,
                name TEXT NOT NULL,
                country TEXT NOT NULL,
                centerLat REAL NOT NULL,
                centerLon REAL NOT NULL,
                bboxSouth REAL,
                bboxWest REAL,
                bboxNorth REAL,
                bboxEast REAL,
                availableCategoriesJson TEXT,
                defaultZoom REAL,
                routingMaxAvailableMinutes INTEGER,
                routingMaxPoiCandidates INTEGER,
                transportEnabled INTEGER NOT NULL,
                transportProvider TEXT,
                transportMode TEXT,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_pois (
                id INTEGER NOT NULL PRIMARY KEY,
                citySlug TEXT NOT NULL,
                name TEXT NOT NULL,
                category TEXT NOT NULL,
                lat REAL NOT NULL,
                lon REAL NOT NULL,
                openingHoursRaw TEXT,
                visitDurationMin INTEGER,
                baseScore REAL,
                wikipediaUrl TEXT,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_last_route (
                cacheKey TEXT NOT NULL PRIMARY KEY,
                snapshotJson TEXT NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS cached_route_sessions (
                routeId TEXT NOT NULL PRIMARY KEY,
                sessionJson TEXT NOT NULL,
                isActive INTEGER NOT NULL,
                updatedAtEpochMs INTEGER NOT NULL
            )
            """
        )
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS pending_feedback (
                sessionId TEXT NOT NULL PRIMARY KEY,
                feedbackJson TEXT NOT NULL,
                createdAtEpochMs INTEGER NOT NULL
            )
            """
        )
    }

    private fun seedVersion1Data(db: SupportSQLiteDatabase) {
        db.execSQL(
            """
            INSERT INTO pending_feedback(sessionId, feedbackJson, createdAtEpochMs)
            VALUES ('session-1', '{"rating":5}', 1234)
            """
        )
    }

    private companion object {
        const val TestDatabaseName = "offline-cache-migration-test"
    }
}
