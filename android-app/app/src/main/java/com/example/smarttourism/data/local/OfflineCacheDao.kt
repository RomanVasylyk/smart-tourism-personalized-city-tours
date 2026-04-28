package com.example.smarttourism.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

private const val LastRouteCacheKey = "last_route"

@Dao
interface OfflineCacheDao {
    @Query("SELECT * FROM cached_cities ORDER BY name")
    suspend fun getCachedCities(): List<CachedCityEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedCities(cities: List<CachedCityEntity>)

    @Query("DELETE FROM cached_cities")
    suspend fun clearCachedCities()

    @Transaction
    suspend fun replaceCachedCities(cities: List<CachedCityEntity>) {
        clearCachedCities()
        insertCachedCities(cities)
    }

    @Query(
        """
        SELECT * FROM cached_pois
        WHERE citySlug = :citySlug
        ORDER BY CASE WHEN baseScore IS NULL THEN 1 ELSE 0 END, baseScore DESC, name
        """
    )
    suspend fun getCachedPois(citySlug: String): List<CachedPoiEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCachedPois(pois: List<CachedPoiEntity>)

    @Query("DELETE FROM cached_pois WHERE citySlug = :citySlug")
    suspend fun clearCachedPois(citySlug: String)

    @Transaction
    suspend fun replaceCachedPois(citySlug: String, pois: List<CachedPoiEntity>) {
        clearCachedPois(citySlug)
        insertCachedPois(pois)
    }

    @Query("SELECT * FROM cached_last_route WHERE cacheKey = :cacheKey LIMIT 1")
    suspend fun getLastRoute(cacheKey: String = LastRouteCacheKey): CachedLastRouteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLastRoute(route: CachedLastRouteEntity)

    @Query("DELETE FROM cached_last_route WHERE cacheKey = :cacheKey")
    suspend fun clearLastRoute(cacheKey: String = LastRouteCacheKey)

    @Query("SELECT * FROM cached_route_sessions WHERE isActive = 1 ORDER BY updatedAtEpochMs DESC LIMIT 1")
    suspend fun getActiveRouteSession(): CachedRouteSessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertRouteSession(routeSession: CachedRouteSessionEntity)

    @Query("UPDATE cached_route_sessions SET isActive = 0")
    suspend fun clearActiveRouteSessionFlags()

    @Query("DELETE FROM cached_route_sessions WHERE isActive = 1")
    suspend fun deleteActiveRouteSession()

    @Transaction
    suspend fun saveActiveRouteSession(routeSession: CachedRouteSessionEntity) {
        clearActiveRouteSessionFlags()
        upsertRouteSession(routeSession.copy(isActive = true))
    }

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingFeedback(feedback: PendingFeedbackEntity)

    @Query(
        """
        SELECT * FROM pending_feedback
        WHERE syncStatus IN ('pending', 'failed')
        ORDER BY createdAtEpochMs
        """
    )
    suspend fun getPendingFeedback(): List<PendingFeedbackEntity>

    @Query("DELETE FROM pending_feedback WHERE sessionId = :sessionId")
    suspend fun deletePendingFeedback(sessionId: String)

    @Query(
        """
        SELECT COUNT(*) FROM pending_feedback
        WHERE syncStatus IN ('pending', 'failed')
        """
    )
    suspend fun getPendingFeedbackCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingRouteSessionSync(routeSessionSync: PendingRouteSessionSyncEntity)

    @Query(
        """
        SELECT * FROM pending_route_session_sync
        WHERE syncStatus IN ('pending', 'failed')
        ORDER BY createdAtEpochMs
        """
    )
    suspend fun getPendingRouteSessionSyncs(): List<PendingRouteSessionSyncEntity>

    @Query("DELETE FROM pending_route_session_sync WHERE sessionId = :sessionId")
    suspend fun deletePendingRouteSessionSync(sessionId: String)

    @Query(
        """
        SELECT COUNT(*) FROM pending_route_session_sync
        WHERE syncStatus IN ('pending', 'failed')
        """
    )
    suspend fun getPendingRouteSessionSyncCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingPoiVisitSync(poiVisitSync: PendingPoiVisitSyncEntity)

    @Query(
        """
        SELECT * FROM pending_poi_visit_sync
        WHERE syncStatus IN ('pending', 'failed')
        ORDER BY createdAtEpochMs
        """
    )
    suspend fun getPendingPoiVisitSyncs(): List<PendingPoiVisitSyncEntity>

    @Query("DELETE FROM pending_poi_visit_sync WHERE requestKey = :requestKey")
    suspend fun deletePendingPoiVisitSync(requestKey: String)

    @Query(
        """
        SELECT COUNT(*) FROM pending_poi_visit_sync
        WHERE syncStatus IN ('pending', 'failed')
        """
    )
    suspend fun getPendingPoiVisitSyncCount(): Int
}
