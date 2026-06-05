package com.example.smarttourism.features.planner.state

import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

internal data class RoutePlannerUiState(
    val cities: List<City> = emptyList(),
    val selectedCity: City? = null,
    val pois: List<Poi> = emptyList(),
    val isPoiLoading: Boolean = true,
    val poiError: String? = null,
    val offlineStatusMessage: String? = null,
    val pendingSyncOperationCount: Int = 0,
    val offlineStoredRegion: OfflineStoredRegion? = null,
    val isOfflineMapBusy: Boolean = false,
    val offlineMapProgress: OfflineDownloadProgress? = null,
    val offlineMapMessage: String? = null,
    val routeResponse: RoutePlan? = null,
    val routeBookmarks: List<RouteBookmark> = emptyList(),
    val routeHistory: List<RouteHistoryEntry> = emptyList(),
    val currentRouteRequest: PlannerPreferences? = null,
    val activeBookmarkId: String? = null,
    val hasPendingRouteChanges: Boolean = false,
    val isRouteLoading: Boolean = false,
    val isRouteHistoryLoading: Boolean = false,
    val routeError: String? = null,
    val routeHistoryError: String? = null,
    val hasNoGeneratedStops: Boolean = false,
    val isRerouting: Boolean = false,
    val availableMinutes: Int = 180,
    val pace: String = "normal",
    val returnToStart: Boolean = true,
    val respectOpeningHours: Boolean = true,
    val allowPublicTransport: Boolean = false,
    val startPoint: RoutePoint = EmptyStartPoint,
    val startDateTime: LocalDateTime = LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES),
    val routeSessionStatus: RouteSessionStatus = RouteSessionStatus.NOT_STARTED,
    val routeId: String? = null,
    val routeStartedAt: String? = null,
    val currentTargetPoiId: Int? = null,
    val currentRouteLocation: RoutePoint? = null,
    val trackingError: String? = null,
    val routeFeedback: RouteFeedback? = null,
    val offRouteDetectedAtMs: Long? = null,
    val lastAutoRerouteAtMs: Long? = null,
    val selectedInterests: List<String> = emptyList(),
    val requiredPoiIds: List<Int> = emptyList(),
    val visitedPoiIds: List<Int> = emptyList(),
    val skippedPoiIds: List<Int> = emptyList()
) {
    val routeItems: List<RouteStop>
        get() = routeResponse?.route.orEmpty()

    val selectedCityAvailableCategories: List<String>
        get() = selectedCity
            ?.availableCategories
            ?.map(String::trim)
            ?.filter(String::isNotBlank)
            ?.distinct()
            .orEmpty()
            .ifEmpty { DefaultInterestCategories }

    val isPublicTransportAvailable: Boolean
        get() = selectedCity?.transport?.mhdEnabled == true

    val isCurrentRouteBookmarked: Boolean
        get() = activeBookmarkId != null

    val maxAvailableMinutesLimit: Int
        get() = selectedCity
            ?.routingLimits
            ?.maxAvailableMinutes
            ?.coerceAtLeast(MinimumAvailableMinutes)
            ?.coerceAtMost(MaximumAvailableMinutes)
            ?: MaximumAvailableMinutes
}
