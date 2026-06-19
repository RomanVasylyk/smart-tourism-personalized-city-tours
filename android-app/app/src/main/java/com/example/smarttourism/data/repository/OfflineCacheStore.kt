package com.example.smarttourism.data.repository

import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.google.gson.Gson
import javax.inject.Inject

class OfflineCacheStore @Inject constructor(
    dao: OfflineCacheDao,
    gson: Gson
) {
    private val serializer = CacheEnvelopeSerializer(gson)
    private val cityCacheStore = CityCacheStore(dao, gson, serializer)
    private val poiCacheStore = PoiCacheStore(dao)
    private val routeHistoryStore = RouteHistoryStore(dao, serializer)
    private val pendingSyncQueue = PendingSyncQueue(dao, serializer)

    suspend fun cacheCities(cities: List<CityDto>) {
        cityCacheStore.cacheCities(cities)
    }

    suspend fun getCachedCities(): List<CityDto> =
        cityCacheStore.getCachedCities()

    suspend fun cachePois(citySlug: String, pois: List<PoiDto>) {
        poiCacheStore.cachePois(citySlug, pois)
    }

    suspend fun getCachedPois(citySlug: String): List<PoiDto> =
        poiCacheStore.getCachedPois(citySlug)

    suspend fun saveLastRoute(snapshot: SavedRouteSnapshot) {
        routeHistoryStore.saveLastRoute(snapshot)
    }

    suspend fun loadLastRoute(): SavedRouteSnapshot? =
        routeHistoryStore.loadLastRoute()

    suspend fun saveRouteBookmark(bookmark: RouteBookmark) {
        routeHistoryStore.saveRouteBookmark(bookmark)
    }

    suspend fun getRouteBookmarks(): List<RouteBookmark> =
        routeHistoryStore.getRouteBookmarks()

    suspend fun getRouteBookmark(bookmarkId: String): RouteBookmark? =
        routeHistoryStore.getRouteBookmark(bookmarkId)

    suspend fun deleteRouteBookmark(bookmarkId: String) {
        routeHistoryStore.deleteRouteBookmark(bookmarkId)
    }

    suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry) {
        routeHistoryStore.saveRouteHistoryEntry(entry)
    }

    suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) {
        routeHistoryStore.saveRouteHistoryEntries(entries)
    }

    suspend fun getRouteHistoryEntries(): List<RouteHistoryEntry> =
        routeHistoryStore.getRouteHistoryEntries()

    suspend fun saveActiveRouteSession(session: ActiveRouteSession) {
        routeHistoryStore.saveActiveRouteSession(session)
    }

    suspend fun loadActiveRouteSession(): ActiveRouteSession? =
        routeHistoryStore.loadActiveRouteSession()

    suspend fun clearActiveRouteSession() {
        routeHistoryStore.clearActiveRouteSession()
    }

    suspend fun enqueuePendingRouteSession(request: RouteSessionCreateRequest) {
        pendingSyncQueue.enqueuePendingRouteSession(request)
    }

    suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        request: RouteSessionPoiVisitRequest
    ) {
        pendingSyncQueue.enqueuePendingPoiVisit(sessionId, poiId, request)
    }

    suspend fun enqueuePendingFeedback(
        sessionId: String,
        request: RouteFeedbackRequest
    ) {
        pendingSyncQueue.enqueuePendingFeedback(sessionId, request)
    }

    suspend fun deletePendingFeedback(sessionId: String) {
        pendingSyncQueue.deletePendingFeedback(sessionId)
    }

    suspend fun getPendingFeedbackCount(): Int =
        pendingSyncQueue.getPendingFeedbackCount()

    suspend fun getPendingSyncOperationCount(): Int =
        pendingSyncQueue.getPendingSyncOperationCount()

    suspend fun syncPendingOperations(api: PoiApi): OfflineSyncSummary =
        pendingSyncQueue.syncPendingOperations(api)
}
