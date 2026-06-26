package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteSessionStatus

internal data class PersistRouteSessionInput(
    val deviceId: String,
    val routeId: String,
    val status: RouteSessionStatus,
    val startedAt: String,
    val snapshot: SavedRouteSnapshot,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val feedback: RouteFeedback?,
    val currentHistory: List<RouteHistoryEntry>
)

internal data class PersistRouteSessionResult(
    val nextTargetPoiId: Int?,
    val history: List<RouteHistoryEntry>,
    val pendingSyncOperationCount: Int
)

internal data class ActiveRouteLifecycleResult(
    val state: RoutePlannerUiState,
    val routeId: String,
    val startedAt: String,
    val status: RouteSessionStatus,
    val feedback: RouteFeedback? = state.routeFeedback
)

internal data class ActiveApproachRefresh(
    val previousResponse: RoutePlan,
    val currentLocation: RoutePoint,
    val nextTarget: RouteStop
)

internal data class RouteStopProgressResult(
    val state: RoutePlannerUiState,
    val statusToPersist: RouteSessionStatus,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val syncVisitedPoiIds: List<Int> = emptyList(),
    val syncSkippedPoiIds: List<Int> = emptyList(),
    val snapshotToSave: SavedRouteSnapshot? = null,
    val approachRefresh: ActiveApproachRefresh? = null
)

internal data class TrackedLocationResult(
    val state: RoutePlannerUiState,
    val statusToPersist: RouteSessionStatus? = null,
    val visitedPoiIds: List<Int> = state.visitedPoiIds,
    val skippedPoiIds: List<Int> = state.skippedPoiIds,
    val syncVisitedPoiIds: List<Int> = emptyList(),
    val snapshotToSave: SavedRouteSnapshot? = null,
    val approachRefresh: ActiveApproachRefresh? = null,
    val approachRefreshAutoTriggered: Boolean = false
)

internal fun RoutePlannerUiState.currentSnapshot(): SavedRouteSnapshot? {
    val request = currentRouteRequest
    val response = routeResponse
    return if (request != null && response != null) {
        SavedRouteSnapshot(request = request, response = response)
    } else {
        null
    }
}
