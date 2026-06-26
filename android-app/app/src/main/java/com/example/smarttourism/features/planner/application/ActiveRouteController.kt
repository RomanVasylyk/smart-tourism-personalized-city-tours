package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import javax.inject.Inject

internal class ActiveRouteController @Inject constructor(
    private val lifecycleController: RouteLifecycleController,
    private val stopProgressController: RouteStopProgressController,
    private val offRouteDetector: OffRouteDetector,
    private val persistenceCoordinator: ActiveRoutePersistenceCoordinator
) {
    fun activateRouteTracking(
        state: RoutePlannerUiState,
        newRouteId: String,
        startedAtNow: String
    ): ActiveRouteLifecycleResult? =
        lifecycleController.activateRouteTracking(state, newRouteId, startedAtNow)

    fun pauseRoute(state: RoutePlannerUiState): ActiveRouteLifecycleResult? =
        lifecycleController.pauseRoute(state)

    fun resumeRoute(
        state: RoutePlannerUiState,
        newRouteId: String,
        startedAtNow: String
    ): ActiveRouteLifecycleResult =
        lifecycleController.resumeRoute(state, newRouteId, startedAtNow)

    fun finishRoute(
        state: RoutePlannerUiState,
        progressMetrics: RouteProgressMetrics
    ): ActiveRouteLifecycleResult? =
        lifecycleController.finishRoute(state, progressMetrics)

    fun cancelRoute(state: RoutePlannerUiState): ActiveRouteLifecycleResult? =
        lifecycleController.cancelRoute(state)

    fun updateFeedback(
        state: RoutePlannerUiState,
        feedback: RouteFeedback
    ): ActiveRouteLifecycleResult? =
        lifecycleController.updateFeedback(state, feedback)

    fun handleTrackingError(
        state: RoutePlannerUiState,
        message: String
    ): ActiveRouteLifecycleResult? =
        lifecycleController.handleTrackingError(state, message)

    fun resetRouteSession(state: RoutePlannerUiState): RoutePlannerUiState =
        lifecycleController.resetRouteSession(state)

    fun restoreActiveSession(
        state: RoutePlannerUiState,
        session: ActiveRouteSession
    ): RoutePlannerUiState =
        lifecycleController.restoreActiveSession(state, session)

    fun markRouteStopVisited(
        state: RoutePlannerUiState,
        poiId: Int,
        isNetworkAvailable: Boolean
    ): RouteStopProgressResult? =
        stopProgressController.markRouteStopVisited(state, poiId, isNetworkAvailable)

    fun skipRouteStop(
        state: RoutePlannerUiState,
        poiId: Int,
        isNetworkAvailable: Boolean
    ): RouteStopProgressResult? =
        stopProgressController.skipRouteStop(state, poiId, isNetworkAvailable)

    fun handleTrackedLocation(
        state: RoutePlannerUiState,
        routeLocation: RoutePoint,
        nowMs: Long,
        isNetworkAvailable: Boolean,
        isRerouting: Boolean
    ): TrackedLocationResult =
        offRouteDetector.handleTrackedLocation(state, routeLocation, nowMs, isNetworkAvailable, isRerouting)

    suspend fun enqueueRouteSessionSync(
        deviceId: String,
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAt: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ): Int =
        persistenceCoordinator.enqueueRouteSessionSync(
            deviceId = deviceId,
            sessionRouteId = sessionRouteId,
            status = status,
            startedAt = startedAt,
            snapshot = snapshot,
            finishedAt = finishedAt
        )

    suspend fun persistRouteSession(input: PersistRouteSessionInput): PersistRouteSessionResult =
        persistenceCoordinator.persistRouteSession(input)

    suspend fun syncVisitedPois(
        sessionId: String,
        poiIds: List<Int>
    ): Int =
        persistenceCoordinator.syncVisitedPois(sessionId, poiIds)

    suspend fun syncSkippedPois(
        sessionId: String,
        poiIds: List<Int>
    ): Int =
        persistenceCoordinator.syncSkippedPois(sessionId, poiIds)

    suspend fun syncFeedback(
        sessionId: String,
        feedback: RouteFeedback
    ): Int =
        persistenceCoordinator.syncFeedback(sessionId, feedback)
}
