package com.example.smarttourism.features.planner.application

import com.example.smarttourism.features.planner.domain.route.nextPendingPoi
import com.example.smarttourism.features.planner.domain.route.rerouteStartPoint
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import javax.inject.Inject

internal class RouteStopProgressController @Inject constructor() {
    fun markRouteStopVisited(
        state: RoutePlannerUiState,
        poiId: Int,
        isNetworkAvailable: Boolean
    ): RouteStopProgressResult? {
        if (poiId in state.visitedPoiIds || poiId in state.skippedPoiIds) {
            return null
        }

        val responseBeforeVisit = state.routeResponse
        val locationAtVisit = state.currentRouteLocation
        val statusAtVisit = state.routeSessionStatus
        val updatedVisited = (state.visitedPoiIds + poiId).distinct()
        val nextPendingPoi = nextPendingPoi(state.routeItems, updatedVisited, state.skippedPoiIds)
        val baseState = state.copy(
            visitedPoiIds = updatedVisited,
            currentTargetPoiId = nextPendingPoi?.poiId
        )

        if (nextPendingPoi == null) {
            val completedState = baseState.copy(routeSessionStatus = RouteSessionStatus.COMPLETED)
            return RouteStopProgressResult(
                state = completedState,
                statusToPersist = RouteSessionStatus.COMPLETED,
                visitedPoiIds = updatedVisited,
                skippedPoiIds = state.skippedPoiIds,
                syncVisitedPoiIds = listOf(poiId)
            )
        }

        val approachRefresh = if (
            statusAtVisit == RouteSessionStatus.IN_PROGRESS &&
            responseBeforeVisit != null &&
            isNetworkAvailable
        ) {
            ActiveApproachRefresh(
                previousResponse = responseBeforeVisit,
                currentLocation = rerouteStartPoint(
                    routeItems = state.routeItems,
                    visitedPoiIds = updatedVisited,
                    currentLocation = locationAtVisit,
                    fallbackStart = state.startPoint
                ),
                nextTarget = nextPendingPoi
            )
        } else {
            null
        }

        return RouteStopProgressResult(
            state = baseState,
            statusToPersist = baseState.routeSessionStatus,
            visitedPoiIds = updatedVisited,
            skippedPoiIds = state.skippedPoiIds,
            syncVisitedPoiIds = listOf(poiId),
            snapshotToSave = if (approachRefresh == null) baseState.currentSnapshot() else null,
            approachRefresh = approachRefresh
        )
    }

    fun skipRouteStop(
        state: RoutePlannerUiState,
        poiId: Int,
        isNetworkAvailable: Boolean
    ): RouteStopProgressResult? {
        if (poiId in state.visitedPoiIds || poiId in state.skippedPoiIds) {
            return null
        }

        val responseBeforeSkip = state.routeResponse
        val locationAtSkip = state.currentRouteLocation
        val statusAtSkip = state.routeSessionStatus
        val updatedSkipped = (state.skippedPoiIds + poiId).distinct()
        val updatedRequest = state.currentRouteRequest?.copy(
            excludedPoiIds = (state.currentRouteRequest.excludedPoiIds.orEmpty() + poiId).distinct(),
            preferredPoiIds = state.currentRouteRequest.preferredPoiIds
                .orEmpty()
                .filterNot { preferredPoiId -> preferredPoiId == poiId }
        )
        val updatedRequiredPoiIds = state.requiredPoiIds.filterNot { requiredPoiId -> requiredPoiId == poiId }
        val nextPendingPoi = nextPendingPoi(state.routeItems, state.visitedPoiIds, updatedSkipped)
        val baseState = state.copy(
            skippedPoiIds = updatedSkipped,
            currentRouteRequest = updatedRequest,
            requiredPoiIds = updatedRequiredPoiIds,
            currentTargetPoiId = nextPendingPoi?.poiId
        )

        if (nextPendingPoi == null) {
            val completedState = baseState.copy(routeSessionStatus = RouteSessionStatus.COMPLETED)
            return RouteStopProgressResult(
                state = completedState,
                statusToPersist = RouteSessionStatus.COMPLETED,
                visitedPoiIds = state.visitedPoiIds,
                skippedPoiIds = updatedSkipped,
                syncSkippedPoiIds = listOf(poiId),
                snapshotToSave = completedState.currentSnapshot()
            )
        }

        val approachRefresh = if (
            statusAtSkip == RouteSessionStatus.IN_PROGRESS &&
            responseBeforeSkip != null &&
            isNetworkAvailable
        ) {
            ActiveApproachRefresh(
                previousResponse = responseBeforeSkip,
                currentLocation = rerouteStartPoint(
                    routeItems = state.routeItems,
                    visitedPoiIds = state.visitedPoiIds,
                    currentLocation = locationAtSkip,
                    fallbackStart = state.startPoint
                ),
                nextTarget = nextPendingPoi
            )
        } else {
            null
        }

        return RouteStopProgressResult(
            state = baseState,
            statusToPersist = baseState.routeSessionStatus,
            visitedPoiIds = state.visitedPoiIds,
            skippedPoiIds = updatedSkipped,
            syncSkippedPoiIds = listOf(poiId),
            snapshotToSave = baseState.currentSnapshot(),
            approachRefresh = approachRefresh
        )
    }
}
