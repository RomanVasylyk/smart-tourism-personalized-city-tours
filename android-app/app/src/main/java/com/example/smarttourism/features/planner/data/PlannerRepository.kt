package com.example.smarttourism.features.planner.data

import android.content.Context
import com.example.smarttourism.core.network.ApiModule
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.repository.OfflineCacheRepository
import com.example.smarttourism.data.repository.RouteStorage
import com.example.smarttourism.features.planner.domain.mapper.toDomain
import com.example.smarttourism.features.planner.domain.mapper.toDto
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.sync.OfflineSyncScheduler

internal class PlannerRepository(
    private val context: Context,
    private val api: PoiApi = ApiModule.poiApi
) {
    fun getOrCreateDeviceId(): String =
        RouteStorage.getOrCreateDeviceId(context)

    fun isNetworkAvailable(): Boolean =
        NetworkMonitor.isNetworkAvailable(context)

    suspend fun fetchCities(): List<City> =
        api.getCities().map { city -> city.toDomain() }

    suspend fun fetchPois(citySlug: String): List<Poi> =
        api.getPois(citySlug).map { poi -> poi.toDomain() }

    suspend fun generateRoute(request: PlannerPreferences): RoutePlan =
        api.generateRoute(request.toDto()).toDomain()

    suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg =
        api.generateRouteLeg(request.toDto()).toDomain()

    suspend fun getRouteSession(routeId: String): RouteSession =
        api.getRouteSession(routeId).toDomain()

    suspend fun getRouteSessions(deviceId: String): List<RouteSession> =
        api.getRouteSessions(deviceId).map { session -> session.toDomain() }

    suspend fun cacheCities(cities: List<City>) {
        OfflineCacheRepository.cacheCities(context, cities.map { city -> city.toDto() })
    }

    suspend fun getCachedCities(): List<City> =
        OfflineCacheRepository.getCachedCities(context).map { city -> city.toDomain() }

    suspend fun cachePois(citySlug: String, pois: List<Poi>) {
        OfflineCacheRepository.cachePois(context, citySlug, pois.map { poi -> poi.toDto() })
    }

    suspend fun getCachedPois(citySlug: String): List<Poi> =
        OfflineCacheRepository.getCachedPois(context, citySlug).map { poi -> poi.toDomain() }

    suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) {
        RouteStorage.save(context, snapshot)
    }

    suspend fun loadSnapshot(): SavedRouteSnapshot? =
        RouteStorage.load(context)

    suspend fun saveActiveSession(session: ActiveRouteSession) {
        RouteStorage.saveActiveSession(context, session)
    }

    suspend fun loadActiveSession(): ActiveRouteSession? =
        RouteStorage.loadActiveSession(context)

    suspend fun clearActiveSession() {
        RouteStorage.clearActiveSession(context)
    }

    suspend fun saveRouteBookmark(bookmark: RouteBookmark) {
        RouteStorage.saveRouteBookmark(context, bookmark)
    }

    suspend fun loadRouteBookmarks(): List<RouteBookmark> =
        RouteStorage.loadRouteBookmarks(context)

    suspend fun loadRouteBookmark(bookmarkId: String): RouteBookmark? =
        RouteStorage.loadRouteBookmark(context, bookmarkId)

    suspend fun deleteRouteBookmark(bookmarkId: String) {
        RouteStorage.deleteRouteBookmark(context, bookmarkId)
    }

    suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry) {
        RouteStorage.saveRouteHistoryEntry(context, entry)
    }

    suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) {
        RouteStorage.saveRouteHistoryEntries(context, entries)
    }

    suspend fun loadRouteHistoryEntries(): List<RouteHistoryEntry> =
        RouteStorage.loadRouteHistoryEntries(context)

    suspend fun getPendingSyncOperationCount(): Int =
        OfflineCacheRepository.getPendingSyncOperationCount(context)

    suspend fun enqueuePendingRouteSession(
        sessionRouteId: String,
        deviceId: String,
        status: String,
        startedAt: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ) {
        val response = snapshot.response
        OfflineCacheRepository.enqueuePendingRouteSession(
            context,
            RouteSessionCreateRequest(
                id = sessionRouteId,
                device_id = deviceId,
                city = snapshot.request.city.ifBlank { response.city },
                status = status,
                start_lat = response.start.lat,
                start_lon = response.start.lon,
                available_minutes = response.availableMinutes,
                pace = response.pace,
                return_to_start = response.returnToStart,
                opening_hours_enabled = response.respectOpeningHours,
                started_at = startedAt,
                finished_at = finishedAt,
                used_minutes = response.usedMinutes,
                total_walk_minutes = response.totalWalkMinutes,
                total_visit_minutes = response.totalVisitMinutes,
                route_snapshot_json = response.toDto()
            )
        )
    }

    suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        visitedAt: String,
        skipped: Boolean
    ) {
        OfflineCacheRepository.enqueuePendingPoiVisit(
            context = context,
            sessionId = sessionId,
            poiId = poiId,
            request = RouteSessionPoiVisitRequest(
                visited_at = visitedAt,
                skipped = skipped
            )
        )
    }

    suspend fun enqueuePendingFeedback(
        sessionId: String,
        feedback: RouteFeedback
    ) {
        OfflineCacheRepository.enqueuePendingFeedback(
            context = context,
            sessionId = sessionId,
            request = RouteFeedbackRequest(
                rating = feedback.rating,
                was_convenient = feedback.route_was_comfortable,
                too_much_walking = feedback.too_much_walking,
                pois_were_interesting = feedback.pois_were_interesting,
                comment = null
            )
        )
    }

    fun scheduleImmediateSync() {
        OfflineSyncScheduler.scheduleImmediate(context)
    }
}
