package com.example.smarttourism.features.planner.data.local

import android.content.Context
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.repository.OfflineCacheRepository
import com.example.smarttourism.data.repository.RouteStorage
import com.example.smarttourism.features.planner.domain.mapper.toDomain
import com.example.smarttourism.features.planner.domain.mapper.toDto
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi

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

internal class DefaultPlannerLocalDataSource(
    context: Context
) : PlannerLocalDataSource {
    private val appContext = context.applicationContext

    override fun getOrCreateDeviceId(): String =
        RouteStorage.getOrCreateDeviceId(appContext)

    override suspend fun cacheCities(cities: List<City>) {
        OfflineCacheRepository.cacheCities(appContext, cities.map { city -> city.toDto() })
    }

    override suspend fun getCachedCities(): List<City> =
        OfflineCacheRepository.getCachedCities(appContext).map { city -> city.toDomain() }

    override suspend fun cachePois(citySlug: String, pois: List<Poi>) {
        OfflineCacheRepository.cachePois(appContext, citySlug, pois.map { poi -> poi.toDto() })
    }

    override suspend fun getCachedPois(citySlug: String): List<Poi> =
        OfflineCacheRepository.getCachedPois(appContext, citySlug).map { poi -> poi.toDomain() }

    override suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) {
        RouteStorage.save(appContext, snapshot)
    }

    override suspend fun loadSnapshot(): SavedRouteSnapshot? =
        RouteStorage.load(appContext)

    override suspend fun saveActiveSession(session: ActiveRouteSession) {
        RouteStorage.saveActiveSession(appContext, session)
    }

    override suspend fun loadActiveSession(): ActiveRouteSession? =
        RouteStorage.loadActiveSession(appContext)

    override suspend fun clearActiveSession() {
        RouteStorage.clearActiveSession(appContext)
    }

    override suspend fun saveRouteBookmark(bookmark: RouteBookmark) {
        RouteStorage.saveRouteBookmark(appContext, bookmark)
    }

    override suspend fun loadRouteBookmarks(): List<RouteBookmark> =
        RouteStorage.loadRouteBookmarks(appContext)

    override suspend fun loadRouteBookmark(bookmarkId: String): RouteBookmark? =
        RouteStorage.loadRouteBookmark(appContext, bookmarkId)

    override suspend fun deleteRouteBookmark(bookmarkId: String) {
        RouteStorage.deleteRouteBookmark(appContext, bookmarkId)
    }

    override suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry) {
        RouteStorage.saveRouteHistoryEntry(appContext, entry)
    }

    override suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) {
        RouteStorage.saveRouteHistoryEntries(appContext, entries)
    }

    override suspend fun loadRouteHistoryEntries(): List<RouteHistoryEntry> =
        RouteStorage.loadRouteHistoryEntries(appContext)
}
