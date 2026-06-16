package com.example.smarttourism.features.planner.data.local

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.repository.DeviceIdStore
import com.example.smarttourism.data.repository.OfflineCacheStore
import com.example.smarttourism.features.planner.data.mapper.toDomain
import com.example.smarttourism.features.planner.data.mapper.toDto
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi
import javax.inject.Inject

internal interface PlannerLocalDataSource {
    fun getOrCreateDeviceId(): String

    suspend fun cacheCities(cities: List<City>)

    suspend fun getCachedCities(): List<City>

    suspend fun cachePois(citySlug: String, pois: List<Poi>)

    suspend fun getCachedPois(citySlug: String): List<Poi>

    suspend fun saveSnapshot(snapshot: SavedRouteSnapshot)

    suspend fun loadSnapshot(): SavedRouteSnapshot?

    suspend fun saveActiveSession(session: ActiveRouteSession)

    suspend fun loadActiveSession(): ActiveRouteSession?

    suspend fun clearActiveSession()

    suspend fun saveRouteBookmark(bookmark: RouteBookmark)

    suspend fun loadRouteBookmarks(): List<RouteBookmark>

    suspend fun loadRouteBookmark(bookmarkId: String): RouteBookmark?

    suspend fun deleteRouteBookmark(bookmarkId: String)

    suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry)

    suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>)

    suspend fun loadRouteHistoryEntries(): List<RouteHistoryEntry>
}

internal class DefaultPlannerLocalDataSource @Inject constructor(
    private val offlineCacheStore: OfflineCacheStore,
    private val deviceIdStore: DeviceIdStore
) : PlannerLocalDataSource {
    override fun getOrCreateDeviceId(): String =
        deviceIdStore.getOrCreateDeviceId()

    override suspend fun cacheCities(cities: List<City>) {
        offlineCacheStore.cacheCities(cities.map { city -> city.toDto() })
    }

    override suspend fun getCachedCities(): List<City> =
        offlineCacheStore.getCachedCities().map { city -> city.toDomain() }

    override suspend fun cachePois(citySlug: String, pois: List<Poi>) {
        offlineCacheStore.cachePois(citySlug, pois.map { poi -> poi.toDto() })
    }

    override suspend fun getCachedPois(citySlug: String): List<Poi> =
        offlineCacheStore.getCachedPois(citySlug).map { poi -> poi.toDomain() }

    override suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) {
        offlineCacheStore.saveLastRoute(snapshot)
    }

    override suspend fun loadSnapshot(): SavedRouteSnapshot? =
        offlineCacheStore.loadLastRoute()

    override suspend fun saveActiveSession(session: ActiveRouteSession) {
        offlineCacheStore.saveActiveRouteSession(session)
    }

    override suspend fun loadActiveSession(): ActiveRouteSession? =
        offlineCacheStore.loadActiveRouteSession()

    override suspend fun clearActiveSession() {
        offlineCacheStore.clearActiveRouteSession()
    }

    override suspend fun saveRouteBookmark(bookmark: RouteBookmark) {
        offlineCacheStore.saveRouteBookmark(bookmark)
    }

    override suspend fun loadRouteBookmarks(): List<RouteBookmark> =
        offlineCacheStore.getRouteBookmarks()

    override suspend fun loadRouteBookmark(bookmarkId: String): RouteBookmark? =
        offlineCacheStore.getRouteBookmark(bookmarkId)

    override suspend fun deleteRouteBookmark(bookmarkId: String) {
        offlineCacheStore.deleteRouteBookmark(bookmarkId)
    }

    override suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry) {
        offlineCacheStore.saveRouteHistoryEntry(entry)
    }

    override suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) {
        offlineCacheStore.saveRouteHistoryEntries(entries)
    }

    override suspend fun loadRouteHistoryEntries(): List<RouteHistoryEntry> =
        offlineCacheStore.getRouteHistoryEntries()
}
