package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.route.distanceToNextRouteSegmentMeters
import com.example.smarttourism.features.planner.domain.route.markNearbyPoisVisited
import com.example.smarttourism.features.planner.domain.route.nextPendingPoi
import com.example.smarttourism.features.planner.state.AutoRerouteCooldownMs
import com.example.smarttourism.features.planner.state.OffRouteDistanceMeters
import com.example.smarttourism.features.planner.state.OffRouteSustainDurationMs
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import javax.inject.Inject

internal class OffRouteDetector @Inject constructor() {
    fun handleTrackedLocation(
        state: RoutePlannerUiState,
        routeLocation: RoutePoint,
        nowMs: Long,
        isNetworkAvailable: Boolean,
        isRerouting: Boolean
    ): TrackedLocationResult {
        var updatedState = state.copy(
            currentRouteLocation = routeLocation,
            trackingError = null
        )
        var approachLegRefreshed = false

        val mutableVisited = updatedState.visitedPoiIds.toMutableList()
        val newlyVisitedPoiIds = markNearbyPoisVisited(
            routeItems = updatedState.routeItems,
            currentLocation = routeLocation,
            visitedPoiIds = mutableVisited,
            skippedPoiIds = updatedState.skippedPoiIds
        )
        val updatedVisitedPoiIds = mutableVisited.distinct()
        val nextTarget = nextPendingPoi(
            updatedState.routeItems,
            updatedVisitedPoiIds,
            updatedState.skippedPoiIds
        )
        val isCurrentlyOffRoute = updatedState.routeResponse != null &&
            nextTarget != null &&
            distanceToNextRouteSegmentMeters(
                updatedState.routeResponse,
                nextTarget.poiId,
                routeLocation
            ) > OffRouteDistanceMeters

        updatedState = updatedState.copy(
            visitedPoiIds = updatedVisitedPoiIds,
            currentTargetPoiId = nextTarget?.poiId,
            offRouteDetectedAtMs = if (isCurrentlyOffRoute) {
                updatedState.offRouteDetectedAtMs ?: nowMs
            } else {
                null
            }
        )

        var statusToPersist: RouteSessionStatus? = null
        var snapshotToSave: SavedRouteSnapshot? = null
        var approachRefresh: ActiveApproachRefresh? = null
        var approachRefreshAutoTriggered = false

        if (nextTarget == null && updatedState.routeSessionStatus != RouteSessionStatus.COMPLETED) {
            updatedState = updatedState.copy(routeSessionStatus = RouteSessionStatus.COMPLETED)
            statusToPersist = RouteSessionStatus.COMPLETED
        } else if (newlyVisitedPoiIds.isNotEmpty()) {
            statusToPersist = RouteSessionStatus.IN_PROGRESS
            if (
                updatedState.routeSessionStatus == RouteSessionStatus.IN_PROGRESS &&
                updatedState.routeResponse != null &&
                nextTarget != null &&
                isNetworkAvailable
            ) {
                approachRefresh = ActiveApproachRefresh(
                    previousResponse = updatedState.routeResponse,
                    currentLocation = routeLocation,
                    nextTarget = nextTarget
                )
                approachLegRefreshed = true
            } else {
                snapshotToSave = updatedState.currentSnapshot()
            }
        }

        val offRouteDetectedAt = updatedState.offRouteDetectedAtMs
        val autoRerouteCooldownElapsed = updatedState.lastAutoRerouteAtMs?.let { lastTriggeredAt ->
            nowMs - lastTriggeredAt >= AutoRerouteCooldownMs
        } ?: true
        val offRouteSustained = offRouteDetectedAt != null &&
            nowMs - offRouteDetectedAt >= OffRouteSustainDurationMs

        if (
            !approachLegRefreshed &&
            updatedState.routeSessionStatus == RouteSessionStatus.IN_PROGRESS &&
            updatedState.routeResponse != null &&
            nextTarget != null &&
            isCurrentlyOffRoute &&
            offRouteSustained &&
            autoRerouteCooldownElapsed &&
            isNetworkAvailable &&
            !isRerouting
        ) {
            approachRefresh = ActiveApproachRefresh(
                previousResponse = updatedState.routeResponse,
                currentLocation = routeLocation,
                nextTarget = nextTarget
            )
            approachRefreshAutoTriggered = true
        }

        return TrackedLocationResult(
            state = updatedState,
            statusToPersist = statusToPersist,
            visitedPoiIds = updatedVisitedPoiIds,
            skippedPoiIds = updatedState.skippedPoiIds,
            syncVisitedPoiIds = newlyVisitedPoiIds,
            snapshotToSave = snapshotToSave,
            approachRefresh = approachRefresh,
            approachRefreshAutoTriggered = approachRefreshAutoTriggered
        )
    }
}
