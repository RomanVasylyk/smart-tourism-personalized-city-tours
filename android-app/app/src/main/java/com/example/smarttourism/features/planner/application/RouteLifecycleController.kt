package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.features.planner.domain.history.isRestorable
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.domain.route.nextPendingPoi
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import javax.inject.Inject

internal class RouteLifecycleController @Inject constructor() {
    fun activateRouteTracking(
        state: RoutePlannerUiState,
        newRouteId: String,
        startedAtNow: String
    ): ActiveRouteLifecycleResult? {
        if (state.hasPendingRouteChanges) {
            return null
        }
        val response = state.routeResponse ?: return null
        if (response.route.isEmpty() || state.currentRouteRequest == null) {
            return null
        }

        val shouldStartFreshSession =
            state.routeSessionStatus == RouteSessionStatus.NOT_STARTED ||
                state.routeSessionStatus == RouteSessionStatus.COMPLETED ||
                state.routeSessionStatus == RouteSessionStatus.CANCELLED
        val visitedPoiIds = if (shouldStartFreshSession) emptyList() else state.visitedPoiIds
        val skippedPoiIds = if (shouldStartFreshSession) emptyList() else state.skippedPoiIds
        val activeRouteId = if (shouldStartFreshSession) {
            newRouteId
        } else {
            state.routeId ?: newRouteId
        }
        val activeStartedAt = if (shouldStartFreshSession) {
            startedAtNow
        } else {
            state.routeStartedAt ?: startedAtNow
        }
        val updatedState = state.copy(
            routeId = activeRouteId,
            routeStartedAt = activeStartedAt,
            currentTargetPoiId = nextPendingPoi(response.route, visitedPoiIds, skippedPoiIds)?.poiId,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            routeFeedback = if (shouldStartFreshSession) null else state.routeFeedback,
            trackingError = null,
            offRouteDetectedAtMs = null,
            lastAutoRerouteAtMs = null,
            routeSessionStatus = RouteSessionStatus.IN_PROGRESS
        )

        return ActiveRouteLifecycleResult(
            state = updatedState,
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.IN_PROGRESS,
            feedback = null
        )
    }

    fun pauseRoute(state: RoutePlannerUiState): ActiveRouteLifecycleResult? {
        if (state.routeSessionStatus != RouteSessionStatus.IN_PROGRESS) {
            return null
        }
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(routeSessionStatus = RouteSessionStatus.PAUSED),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.PAUSED
        )
    }

    fun resumeRoute(
        state: RoutePlannerUiState,
        newRouteId: String,
        startedAtNow: String
    ): ActiveRouteLifecycleResult {
        val activeRouteId = state.routeId ?: newRouteId
        val activeStartedAt = state.routeStartedAt ?: startedAtNow
        return ActiveRouteLifecycleResult(
            state = state.copy(
                routeId = activeRouteId,
                routeStartedAt = activeStartedAt,
                routeSessionStatus = RouteSessionStatus.IN_PROGRESS,
                trackingError = null,
                offRouteDetectedAtMs = null,
                lastAutoRerouteAtMs = null
            ),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.IN_PROGRESS
        )
    }

    fun finishRoute(
        state: RoutePlannerUiState,
        progressMetrics: RouteProgressMetrics
    ): ActiveRouteLifecycleResult? {
        if (!progressMetrics.canComplete) {
            return null
        }
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(routeSessionStatus = RouteSessionStatus.COMPLETED),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.COMPLETED
        )
    }

    fun cancelRoute(state: RoutePlannerUiState): ActiveRouteLifecycleResult? {
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(routeSessionStatus = RouteSessionStatus.CANCELLED),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.CANCELLED
        )
    }

    fun updateFeedback(
        state: RoutePlannerUiState,
        feedback: RouteFeedback
    ): ActiveRouteLifecycleResult? {
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(
                routeFeedback = feedback,
                routeSessionStatus = RouteSessionStatus.COMPLETED
            ),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.COMPLETED,
            feedback = feedback
        )
    }

    fun handleTrackingError(
        state: RoutePlannerUiState,
        message: String
    ): ActiveRouteLifecycleResult? {
        val activeRouteId = state.routeId ?: return null
        val activeStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
        return ActiveRouteLifecycleResult(
            state = state.copy(
                routeSessionStatus = RouteSessionStatus.PAUSED,
                trackingError = message,
                offRouteDetectedAtMs = null
            ),
            routeId = activeRouteId,
            startedAt = activeStartedAt,
            status = RouteSessionStatus.PAUSED
        )
    }

    fun resetRouteSession(state: RoutePlannerUiState): RoutePlannerUiState =
        state.copy(
            routeSessionStatus = RouteSessionStatus.NOT_STARTED,
            routeId = null,
            routeStartedAt = null,
            currentTargetPoiId = null,
            currentRouteLocation = null,
            trackingError = null,
            routeFeedback = null,
            offRouteDetectedAtMs = null,
            lastAutoRerouteAtMs = null,
            visitedPoiIds = emptyList(),
            skippedPoiIds = emptyList()
        )

    fun restoreActiveSession(
        state: RoutePlannerUiState,
        session: ActiveRouteSession
    ): RoutePlannerUiState {
        val restoredStatus = RouteSessionStatus.fromRawValue(session.status)
        val visitedPoiIds = session.visited_poi_ids.distinct()
        val skippedPoiIds = session.skipped_poi_ids.orEmpty().distinct()
        val nextPendingPoiId = nextPendingPoi(
            session.snapshot.response.route,
            visitedPoiIds,
            skippedPoiIds
        )?.poiId

        return state.copy(
            routeId = session.route_id,
            routeStartedAt = session.started_at,
            routeFeedback = session.feedback,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            currentTargetPoiId = nextPendingPoiId ?: session.current_target_poi_id,
            routeSessionStatus = if (restoredStatus.isRestorable() && nextPendingPoiId == null) {
                RouteSessionStatus.COMPLETED
            } else {
                restoredStatus
            }
        )
    }
}
