package com.example.smarttourism.features.planner.state

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import java.time.LocalDateTime

internal sealed interface PlannerEvent {
    data object Initialize : PlannerEvent
    data class LoadRouteHistory(val forceRefresh: Boolean = true) : PlannerEvent
    data class SelectCity(val city: City) : PlannerEvent
    data class UpdateStartPoint(val lat: Double, val lon: Double) : PlannerEvent
    data class UpdateAvailableMinutes(val value: Int) : PlannerEvent
    data class ToggleInterest(val interest: String, val checked: Boolean) : PlannerEvent
    data class UpdatePace(val value: String) : PlannerEvent
    data class UpdateReturnToStart(val value: Boolean) : PlannerEvent
    data class UpdateRespectOpeningHours(val value: Boolean) : PlannerEvent
    data class UpdateAllowPublicTransport(val value: Boolean) : PlannerEvent
    data object UseCurrentTime : PlannerEvent
    data class UpdateStartDateTime(val value: LocalDateTime) : PlannerEvent
    data class ToggleRequiredPoi(val poiId: Int) : PlannerEvent
    data class RemoveRequiredPoi(val poiId: Int) : PlannerEvent
    data object ClearRequiredPois : PlannerEvent
    data object LoadCuratedRoutes : PlannerEvent
    data class OpenCuratedRoute(val routeId: Int) : PlannerEvent
    data object GenerateRoute : PlannerEvent
    data object AddPublicTransportToRoute : PlannerEvent
    data object SaveCurrentRouteBookmark : PlannerEvent
    data class OpenRouteBookmark(val bookmarkId: String) : PlannerEvent
    data class DeleteRouteBookmark(val bookmarkId: String) : PlannerEvent
    data object ActivateRouteTracking : PlannerEvent
    data object PauseRoute : PlannerEvent
    data object ResumeRoute : PlannerEvent
    data object FinishRoute : PlannerEvent
    data object CancelRoute : PlannerEvent
    data class UpdateFeedback(val feedback: RouteFeedback) : PlannerEvent
    data class MarkRouteStopVisited(val poiId: Int) : PlannerEvent
    data class SkipRouteStop(val poiId: Int) : PlannerEvent
    data class RemovePreviewStop(val poiId: Int) : PlannerEvent
    data class MovePreviewStop(val poiId: Int, val direction: Int) : PlannerEvent
    data class ReplacePreviewStop(val poiId: Int, val preferredPoiId: Int? = null) : PlannerEvent
    data object RecalculateFromCurrentLocation : PlannerEvent
    data class TrackedLocation(val point: RoutePoint) : PlannerEvent
    data class TrackingError(val message: String) : PlannerEvent
    data object DownloadOfflineMap : PlannerEvent
    data object DeleteOfflineMap : PlannerEvent
    data class ClearDisplayedRoute(val cancelActiveSession: Boolean = true) : PlannerEvent
}
