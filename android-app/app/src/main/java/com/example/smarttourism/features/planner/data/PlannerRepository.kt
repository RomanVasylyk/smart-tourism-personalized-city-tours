package com.example.smarttourism.features.planner.data

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.bookmark.RouteBookmarkRepository
import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSession

internal class PlannerRepository(
    private val remoteDataSource: PlannerRemoteDataSource,
    private val localDataSource: PlannerLocalDataSource,
    private val routeSessionRepository: RouteSessionRepository,
    private val routeBookmarkRepository: RouteBookmarkRepository,
    private val offlineSyncRepository: OfflineSyncRepository
) {
    fun getOrCreateDeviceId(): String =
        localDataSource.getOrCreateDeviceId()

    fun isNetworkAvailable(): Boolean =
        offlineSyncRepository.isNetworkAvailable()

    suspend fun fetchCities(): List<City> =
        remoteDataSource.fetchCities()

    suspend fun fetchPois(citySlug: String): List<Poi> =
        remoteDataSource.fetchPois(citySlug)

    suspend fun generateRoute(request: PlannerPreferences): RoutePlan =
        remoteDataSource.generateRoute(request)

    suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg =
        remoteDataSource.generateRouteLeg(request)

    suspend fun getRouteSession(routeId: String): RouteSession =
        routeSessionRepository.getRouteSession(routeId)

    suspend fun getRouteSessions(deviceId: String): List<RouteSession> =
        routeSessionRepository.getRouteSessions(deviceId)

    suspend fun cacheCities(cities: List<City>) {
        localDataSource.cacheCities(cities)
    }

    suspend fun getCachedCities(): List<City> =
        localDataSource.getCachedCities()

    suspend fun cachePois(citySlug: String, pois: List<Poi>) {
        localDataSource.cachePois(citySlug, pois)
    }

    suspend fun getCachedPois(citySlug: String): List<Poi> =
        localDataSource.getCachedPois(citySlug)

    suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) {
        localDataSource.saveSnapshot(snapshot)
    }

    suspend fun loadSnapshot(): SavedRouteSnapshot? =
        localDataSource.loadSnapshot()

    suspend fun saveActiveSession(session: ActiveRouteSession) {
        routeSessionRepository.saveActiveSession(session)
    }

    suspend fun loadActiveSession(): ActiveRouteSession? =
        routeSessionRepository.loadActiveSession()

    suspend fun clearActiveSession() {
        routeSessionRepository.clearActiveSession()
    }

    suspend fun saveRouteBookmark(bookmark: RouteBookmark) {
        routeBookmarkRepository.saveBookmark(bookmark)
    }

    suspend fun loadRouteBookmarks(): List<RouteBookmark> =
        routeBookmarkRepository.loadBookmarks()

    suspend fun loadRouteBookmark(bookmarkId: String): RouteBookmark? =
        routeBookmarkRepository.loadBookmark(bookmarkId)

    suspend fun deleteRouteBookmark(bookmarkId: String) {
        routeBookmarkRepository.deleteBookmark(bookmarkId)
    }

    suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry) {
        routeSessionRepository.saveHistoryEntry(entry)
    }

    suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) {
        routeSessionRepository.saveHistoryEntries(entries)
    }

    suspend fun loadRouteHistoryEntries(): List<RouteHistoryEntry> =
        routeSessionRepository.loadHistoryEntries()

    suspend fun getPendingSyncOperationCount(): Int =
        offlineSyncRepository.getPendingSyncOperationCount()

    suspend fun enqueuePendingRouteSession(
        sessionRouteId: String,
        deviceId: String,
        status: String,
        startedAt: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ) {
        offlineSyncRepository.enqueuePendingRouteSession(
            sessionRouteId = sessionRouteId,
            deviceId = deviceId,
            status = status,
            startedAt = startedAt,
            snapshot = snapshot,
            finishedAt = finishedAt
        )
    }

    suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        visitedAt: String,
        skipped: Boolean
    ) {
        offlineSyncRepository.enqueuePendingPoiVisit(
            sessionId = sessionId,
            poiId = poiId,
            visitedAt = visitedAt,
            skipped = skipped
        )
    }

    suspend fun enqueuePendingFeedback(
        sessionId: String,
        feedback: RouteFeedback
    ) {
        offlineSyncRepository.enqueuePendingFeedback(
            sessionId = sessionId,
            feedback = feedback
        )
    }

    fun scheduleImmediateSync() {
        offlineSyncRepository.scheduleImmediateSync()
    }
}
