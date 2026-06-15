package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import com.example.smarttourism.features.planner.domain.city.availableCategories
import com.example.smarttourism.features.planner.domain.city.supportsPublicTransport
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.domain.route.routeProgressMetrics
import com.example.smarttourism.features.planner.state.OfflineDownloadProgress
import com.example.smarttourism.features.planner.state.PlannerStateReducer
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.maxAvailableMinutesFor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.LocalDateTime

internal class PlannerStateStore {
    private val _uiState = MutableStateFlow(
        RoutePlannerUiState(startDateTime = defaultRouteStartDateTime())
    )
    val uiState: StateFlow<RoutePlannerUiState> = _uiState.asStateFlow()

    fun update(transform: (RoutePlannerUiState) -> RoutePlannerUiState) {
        _uiState.update(transform)
    }

    var cities: List<City>
        get() = uiState.value.cities
        set(value) = update { state -> state.copy(cities = value) }
    var selectedCity: City?
        get() = uiState.value.selectedCity
        set(value) = update { state -> state.copy(selectedCity = value) }
    var pois: List<Poi>
        get() = uiState.value.pois
        set(value) = update { state -> state.copy(pois = value) }
    var isPoiLoading: Boolean
        get() = uiState.value.isPoiLoading
        set(value) = update { state -> state.copy(isPoiLoading = value) }
    var poiError: String?
        get() = uiState.value.poiError
        set(value) = update { state -> state.copy(poiError = value) }
    var offlineStatusMessage: String?
        get() = uiState.value.offlineStatusMessage
        set(value) = update { state -> state.copy(offlineStatusMessage = value) }
    var pendingSyncOperationCount: Int
        get() = uiState.value.pendingSyncOperationCount
        set(value) = update { state -> state.copy(pendingSyncOperationCount = value) }
    var offlineStoredRegion: OfflineStoredRegion?
        get() = uiState.value.offlineStoredRegion
        set(value) = update { state -> state.copy(offlineStoredRegion = value) }
    var isOfflineMapBusy: Boolean
        get() = uiState.value.isOfflineMapBusy
        set(value) = update { state -> state.copy(isOfflineMapBusy = value) }
    var offlineMapProgress: OfflineDownloadProgress?
        get() = uiState.value.offlineMapProgress
        set(value) = update { state -> state.copy(offlineMapProgress = value) }
    var offlineMapMessage: String?
        get() = uiState.value.offlineMapMessage
        set(value) = update { state -> state.copy(offlineMapMessage = value) }
    var routeResponse: RoutePlan?
        get() = uiState.value.routeResponse
        set(value) = update { state -> state.copy(routeResponse = value) }
    var routeBookmarks: List<RouteBookmark>
        get() = uiState.value.routeBookmarks
        set(value) = update { state -> state.copy(routeBookmarks = value) }
    var routeHistory: List<RouteHistoryEntry>
        get() = uiState.value.routeHistory
        set(value) = update { state -> state.copy(routeHistory = value) }
    var currentRouteRequest: PlannerPreferences?
        get() = uiState.value.currentRouteRequest
        set(value) = update { state -> state.copy(currentRouteRequest = value) }
    var activeBookmarkId: String?
        get() = uiState.value.activeBookmarkId
        set(value) = update { state -> state.copy(activeBookmarkId = value) }
    var hasPendingRouteChanges: Boolean
        get() = uiState.value.hasPendingRouteChanges
        set(value) = update { state -> state.copy(hasPendingRouteChanges = value) }
    var isRouteLoading: Boolean
        get() = uiState.value.isRouteLoading
        set(value) = update { state -> state.copy(isRouteLoading = value) }
    var isRouteHistoryLoading: Boolean
        get() = uiState.value.isRouteHistoryLoading
        set(value) = update { state -> state.copy(isRouteHistoryLoading = value) }
    var routeError: String?
        get() = uiState.value.routeError
        set(value) = update { state -> state.copy(routeError = value) }
    var routeHistoryError: String?
        get() = uiState.value.routeHistoryError
        set(value) = update { state -> state.copy(routeHistoryError = value) }
    var hasNoGeneratedStops: Boolean
        get() = uiState.value.hasNoGeneratedStops
        set(value) = update { state -> state.copy(hasNoGeneratedStops = value) }
    var isRerouting: Boolean
        get() = uiState.value.isRerouting
        set(value) = update { state -> state.copy(isRerouting = value) }
    var availableMinutes: Int
        get() = uiState.value.availableMinutes
        set(value) = update { state -> state.copy(availableMinutes = value) }
    var pace: String
        get() = uiState.value.pace
        set(value) = update { state -> state.copy(pace = value) }
    var returnToStart: Boolean
        get() = uiState.value.returnToStart
        set(value) = update { state -> state.copy(returnToStart = value) }
    var respectOpeningHours: Boolean
        get() = uiState.value.respectOpeningHours
        set(value) = update { state -> state.copy(respectOpeningHours = value) }
    var allowPublicTransport: Boolean
        get() = uiState.value.allowPublicTransport
        set(value) = update { state -> state.copy(allowPublicTransport = value) }
    var startPoint: RoutePoint
        get() = uiState.value.startPoint
        set(value) = update { state -> state.copy(startPoint = value) }
    var startDateTime: LocalDateTime
        get() = uiState.value.startDateTime
        set(value) = update { state -> state.copy(startDateTime = value) }
    var routeSessionStatus: RouteSessionStatus
        get() = uiState.value.routeSessionStatus
        set(value) = update { state -> state.copy(routeSessionStatus = value) }
    var routeId: String?
        get() = uiState.value.routeId
        set(value) = update { state -> state.copy(routeId = value) }
    var routeStartedAt: String?
        get() = uiState.value.routeStartedAt
        set(value) = update { state -> state.copy(routeStartedAt = value) }
    var currentTargetPoiId: Int?
        get() = uiState.value.currentTargetPoiId
        set(value) = update { state -> state.copy(currentTargetPoiId = value) }
    var currentRouteLocation: RoutePoint?
        get() = uiState.value.currentRouteLocation
        set(value) = update { state -> state.copy(currentRouteLocation = value) }
    var trackingError: String?
        get() = uiState.value.trackingError
        set(value) = update { state -> state.copy(trackingError = value) }
    var routeFeedback: RouteFeedback?
        get() = uiState.value.routeFeedback
        set(value) = update { state -> state.copy(routeFeedback = value) }
    var offRouteDetectedAtMs: Long?
        get() = uiState.value.offRouteDetectedAtMs
        set(value) = update { state -> state.copy(offRouteDetectedAtMs = value) }
    var lastAutoRerouteAtMs: Long?
        get() = uiState.value.lastAutoRerouteAtMs
        set(value) = update { state -> state.copy(lastAutoRerouteAtMs = value) }
    var selectedInterests: List<String>
        get() = uiState.value.selectedInterests
        set(value) = update { state -> state.copy(selectedInterests = value) }
    var requiredPoiIds: List<Int>
        get() = uiState.value.requiredPoiIds
        set(value) = update { state -> state.copy(requiredPoiIds = value) }
    var visitedPoiIds: List<Int>
        get() = uiState.value.visitedPoiIds
        set(value) = update { state -> state.copy(visitedPoiIds = value) }
    var skippedPoiIds: List<Int>
        get() = uiState.value.skippedPoiIds
        set(value) = update { state -> state.copy(skippedPoiIds = value) }

    val routeItems: List<RouteStop>
        get() = routeResponse?.route.orEmpty()

    val progressMetrics: RouteProgressMetrics
        get() = routeProgressMetrics(
            routeResponse = routeResponse,
            routeItems = routeItems,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            currentLocation = currentRouteLocation,
            isTracking = routeSessionStatus == RouteSessionStatus.IN_PROGRESS
        )

    val selectedCityAvailableCategories: List<String>
        get() = selectedCity?.availableCategories().orEmpty()

    val isPublicTransportAvailable: Boolean
        get() = selectedCity?.supportsPublicTransport() == true

    val isCurrentRouteBookmarked: Boolean
        get() = activeBookmarkId != null

    val maxAvailableMinutesLimit: Int
        get() = maxAvailableMinutesFor(selectedCity)

    fun clearRouteMessages() {
        update { state -> PlannerStateReducer.clearRouteMessages(state) }
    }

    fun currentRouteSnapshot(): SavedRouteSnapshot? {
        val request = currentRouteRequest
        val response = routeResponse
        return if (request != null && response != null) {
            SavedRouteSnapshot(request = request, response = response)
        } else {
            null
        }
    }
}
