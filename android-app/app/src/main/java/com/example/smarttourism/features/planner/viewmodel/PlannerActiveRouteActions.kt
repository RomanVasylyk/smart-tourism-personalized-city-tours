package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.features.planner.application.ActiveRouteController
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import java.util.UUID

internal class PlannerActiveRouteActions(
    private val state: PlannerStateStore,
    private val activeRouteController: ActiveRouteController,
    private val offlineSyncRepository: OfflineSyncRepository,
    private val sessionCoordinator: PlannerSessionCoordinator
) {
    fun activateRouteTracking() {
        val result = activeRouteController.activateRouteTracking(
            state = state.uiState.value,
            newRouteId = UUID.randomUUID().toString(),
            startedAtNow = defaultRouteStartDateTime().toString()
        ) ?: return
        state.update { result.state }
        sessionCoordinator.persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    fun pauseRoute() {
        val result = activeRouteController.pauseRoute(state.uiState.value) ?: return
        state.update { result.state }
        sessionCoordinator.persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    fun resumeRoute() {
        val result = activeRouteController.resumeRoute(
            state = state.uiState.value,
            newRouteId = UUID.randomUUID().toString(),
            startedAtNow = defaultRouteStartDateTime().toString()
        )
        state.update { result.state }
        sessionCoordinator.persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    fun finishRoute() {
        val result = activeRouteController.finishRoute(state.uiState.value, state.progressMetrics) ?: return
        state.update { result.state }
        sessionCoordinator.persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    fun cancelRoute() {
        val result = activeRouteController.cancelRoute(state.uiState.value) ?: return
        state.update { result.state }
        sessionCoordinator.persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    fun updateFeedback(feedback: RouteFeedback) {
        val result = activeRouteController.updateFeedback(state.uiState.value, feedback) ?: return
        state.update { result.state }
        sessionCoordinator.persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
        sessionCoordinator.syncFeedbackToBackend(feedback)
    }

    fun markRouteStopVisited(poiId: Int) {
        val result = activeRouteController.markRouteStopVisited(
            state = state.uiState.value,
            poiId = poiId,
            isNetworkAvailable = offlineSyncRepository.isNetworkAvailable()
        ) ?: return
        state.update { result.state }
        sessionCoordinator.syncVisitedPoisToBackend(result.syncVisitedPoiIds)
        sessionCoordinator.persistRouteSession(
            status = result.statusToPersist,
            visitedIds = result.visitedPoiIds,
            skippedIds = result.skippedPoiIds
        )
        result.snapshotToSave?.let { snapshot ->
            sessionCoordinator.saveSnapshot(snapshot)
        }
        result.approachRefresh?.let { refresh ->
            sessionCoordinator.refreshActiveRouteApproachLeg(
                previousResponse = refresh.previousResponse,
                currentLocation = refresh.currentLocation,
                nextTarget = refresh.nextTarget
            )
        }
    }

    fun skipRouteStop(poiId: Int) {
        val result = activeRouteController.skipRouteStop(
            state = state.uiState.value,
            poiId = poiId,
            isNetworkAvailable = offlineSyncRepository.isNetworkAvailable()
        ) ?: return
        state.update { result.state }
        result.snapshotToSave?.let { snapshot ->
            sessionCoordinator.saveSnapshot(snapshot)
        }
        sessionCoordinator.syncSkippedPoisToBackend(result.syncSkippedPoiIds)
        sessionCoordinator.persistRouteSession(
            status = result.statusToPersist,
            visitedIds = result.visitedPoiIds,
            skippedIds = result.skippedPoiIds
        )
        result.approachRefresh?.let { refresh ->
            sessionCoordinator.refreshActiveRouteApproachLeg(
                previousResponse = refresh.previousResponse,
                currentLocation = refresh.currentLocation,
                nextTarget = refresh.nextTarget
            )
        }
    }

    fun handleTrackedLocation(routeLocation: RoutePoint) {
        val result = activeRouteController.handleTrackedLocation(
            state = state.uiState.value,
            routeLocation = routeLocation,
            nowMs = System.currentTimeMillis(),
            isNetworkAvailable = offlineSyncRepository.isNetworkAvailable(),
            isRerouting = state.isRerouting
        )
        state.update { result.state }
        result.statusToPersist?.let { status ->
            sessionCoordinator.persistRouteSession(
                status = status,
                visitedIds = result.visitedPoiIds,
                skippedIds = result.skippedPoiIds
            )
        }
        sessionCoordinator.syncVisitedPoisToBackend(result.syncVisitedPoiIds)
        result.snapshotToSave?.let { snapshot ->
            sessionCoordinator.saveSnapshot(snapshot)
        }
        result.approachRefresh?.let { refresh ->
            sessionCoordinator.refreshActiveRouteApproachLeg(
                previousResponse = refresh.previousResponse,
                currentLocation = refresh.currentLocation,
                nextTarget = refresh.nextTarget,
                autoTriggered = result.approachRefreshAutoTriggered
            )
        }
    }

    fun handleTrackingError(message: String) {
        val result = activeRouteController.handleTrackingError(state.uiState.value, message) ?: return
        state.update { result.state }
        sessionCoordinator.persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }
}
