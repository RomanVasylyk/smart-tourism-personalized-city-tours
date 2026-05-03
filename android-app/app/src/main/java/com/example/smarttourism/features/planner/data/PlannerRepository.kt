package com.example.smarttourism.features.planner

import android.content.Context
import com.example.smarttourism.core.network.ApiModule
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionDto
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.repository.OfflineCacheRepository
import com.example.smarttourism.data.repository.RouteStorage
import com.example.smarttourism.sync.OfflineSyncScheduler

internal class PlannerRepository(
    private val context: Context,
    private val api: PoiApi = ApiModule.poiApi
) {
    fun getOrCreateDeviceId(): String =
        RouteStorage.getOrCreateDeviceId(context)

    fun isNetworkAvailable(): Boolean =
        NetworkMonitor.isNetworkAvailable(context)

    suspend fun fetchCities(): List<CityDto> =
        api.getCities()

    suspend fun fetchPois(citySlug: String): List<PoiDto> =
        api.getPois(citySlug)

    suspend fun generateRoute(request: RouteRequest): RouteResponse =
        api.generateRoute(request)

    suspend fun getRouteSession(routeId: String): RouteSessionDto =
        api.getRouteSession(routeId)

    suspend fun getRouteSessions(deviceId: String): List<RouteSessionDto> =
        api.getRouteSessions(deviceId)

    suspend fun cacheCities(cities: List<CityDto>) {
        OfflineCacheRepository.cacheCities(context, cities)
    }

    suspend fun getCachedCities(): List<CityDto> =
        OfflineCacheRepository.getCachedCities(context)

    suspend fun cachePois(citySlug: String, pois: List<PoiDto>) {
        OfflineCacheRepository.cachePois(context, citySlug, pois)
    }

    suspend fun getCachedPois(citySlug: String): List<PoiDto> =
        OfflineCacheRepository.getCachedPois(context, citySlug)

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

    suspend fun getPendingSyncOperationCount(): Int =
        OfflineCacheRepository.getPendingSyncOperationCount(context)

    suspend fun enqueuePendingRouteSession(request: RouteSessionCreateRequest) {
        OfflineCacheRepository.enqueuePendingRouteSession(context, request)
    }

    suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        request: RouteSessionPoiVisitRequest
    ) {
        OfflineCacheRepository.enqueuePendingPoiVisit(
            context = context,
            sessionId = sessionId,
            poiId = poiId,
            request = request
        )
    }

    suspend fun enqueuePendingFeedback(
        sessionId: String,
        request: RouteFeedbackRequest
    ) {
        OfflineCacheRepository.enqueuePendingFeedback(
            context = context,
            sessionId = sessionId,
            request = request
        )
    }

    fun scheduleImmediateSync() {
        OfflineSyncScheduler.scheduleImmediate(context)
    }
}
