package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.features.planner.application.RouteGenerationInput
import com.example.smarttourism.features.planner.application.RouteGenerationResult
import com.example.smarttourism.features.planner.application.RouteGenerationUseCase
import com.example.smarttourism.features.planner.application.RoutePreviewController
import com.example.smarttourism.features.planner.domain.history.isRestorable
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.route.buildPreviewReplacementCandidates
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlannerRoutePlanningActions(
    private val state: PlannerStateStore,
    private val scope: CoroutineScope,
    private val routeGenerationUseCase: RouteGenerationUseCase,
    private val routePreviewController: RoutePreviewController,
    private val sessionCoordinator: PlannerSessionCoordinator,
    private val messages: PlannerMessages
) {
    fun generateRoute() {
        scope.launch {
            state.isRouteLoading = true
            sessionCoordinator.clearRouteMessages()
            val existingSnapshot = sessionCoordinator.currentRouteSnapshot()
            val existingRouteId = state.routeId
            val existingStatus = state.routeSessionStatus
            val existingStartedAt = state.routeStartedAt ?: defaultRouteStartDateTime().toString()
            val result = routeGenerationUseCase.generateRoute(
                RouteGenerationInput(
                    selectedCity = state.selectedCity,
                    startPoint = state.startPoint,
                    availableMinutes = state.availableMinutes,
                    selectedInterests = state.selectedInterests,
                    pace = state.pace,
                    returnToStart = state.returnToStart,
                    startDateTime = state.startDateTime,
                    respectOpeningHours = state.respectOpeningHours,
                    requiredPoiIds = state.requiredPoiIds,
                    visitedPoiIds = state.visitedPoiIds,
                    skippedPoiIds = state.skippedPoiIds,
                    allowPublicTransport = state.allowPublicTransport,
                    isPublicTransportAvailable = state.isPublicTransportAvailable,
                    pois = state.pois,
                    offlineRouteGenerationMessage = messages.offlineRouteGeneration,
                    routeGenerationFailedMessage = messages.routeGenerationFailed,
                    requiredPlacesMissingMessage = messages.requiredPlacesMissing
                )
            )

            when (result) {
                is RouteGenerationResult.Error -> {
                    state.routeError = result.message
                    state.hasNoGeneratedStops = false
                }

                is RouteGenerationResult.MissingRequiredPlaces -> {
                    state.routeResponse = null
                    state.currentRouteRequest = result.request
                    state.hasPendingRouteChanges = false
                    state.hasNoGeneratedStops = false
                    state.routeError = result.message
                }

                is RouteGenerationResult.EmptyRoute -> {
                    state.routeResponse = null
                    state.currentRouteRequest = null
                    state.hasPendingRouteChanges = false
                    state.hasNoGeneratedStops = true
                }

                is RouteGenerationResult.Success -> {
                    if (
                        existingRouteId != null &&
                        existingSnapshot != null &&
                        existingStatus.isRestorable()
                    ) {
                        sessionCoordinator.enqueueRouteSessionSync(
                            sessionRouteId = existingRouteId,
                            status = RouteSessionStatus.CANCELLED,
                            startedAtValue = existingStartedAt,
                            snapshot = existingSnapshot,
                            finishedAt = defaultRouteStartDateTime().toString()
                        )
                    }
                    sessionCoordinator.resetRouteSession()
                    state.currentRouteRequest = result.request
                    state.routeResponse = result.response
                    state.hasPendingRouteChanges = false
                    state.hasNoGeneratedStops = false
                    state.offlineStatusMessage = null
                    sessionCoordinator.refreshPendingSyncOperationCount()
                }
            }
            state.isRouteLoading = false
        }
    }

    fun addPublicTransportToRoute() {
        val request = state.currentRouteRequest ?: return
        val orderedPoiIds = state.routeItems.sortedBy { item -> item.order }.map { item -> item.poiId }
        if (orderedPoiIds.isEmpty()) {
            return
        }
        scope.launch {
            state.isRerouting = true
            sessionCoordinator.clearRouteMessages()
            val result = routeGenerationUseCase.addPublicTransport(
                currentRequest = request,
                orderedPoiIds = orderedPoiIds,
                pois = state.pois,
                offlineRouteGenerationMessage = messages.offlineRouteGeneration,
                routeGenerationFailedMessage = messages.routeGenerationFailed
            )
            when (result) {
                is RouteGenerationResult.Success -> {
                    state.currentRouteRequest = result.request
                    state.routeResponse = result.response
                    state.allowPublicTransport = true
                    state.hasPendingRouteChanges = false
                    state.hasNoGeneratedStops = false
                }

                is RouteGenerationResult.Error -> {
                    state.routeError = result.message
                }

                else -> Unit
            }
            state.isRerouting = false
        }
    }

    fun previewReplacementCandidates(poiId: Int): List<Poi> =
        buildPreviewReplacementCandidates(
            targetPoiId = poiId,
            routeItems = state.routeItems,
            pois = state.pois,
            selectedInterests = state.selectedInterests,
            excludePoiIds = state.currentRouteRequest?.excludedPoiIds.orEmpty()
        )

    fun recalculateFromCurrentLocation() {
        val location = state.currentRouteLocation ?: return
        sessionCoordinator.recalculateRouteFromPoint(location, autoTriggered = false, additionalExcludedPoiIds = emptyList())
    }

    fun removePreviewStop(poiId: Int) {
        scope.launch {
            state.isRerouting = true
            sessionCoordinator.clearRouteMessages()
            val result = routePreviewController.removeStop(
                state = state.uiState.value,
                poiId = poiId,
                offlineRouteGenerationMessage = messages.offlineRouteGeneration,
                routeGenerationFailedMessage = messages.routeGenerationFailed
            )
            state.update {
                result.state.copy(
                    isRerouting = false,
                    routeError = result.error ?: result.state.routeError
                )
            }
        }
    }

    fun movePreviewStop(poiId: Int, direction: Int) {
        scope.launch {
            state.isRerouting = true
            sessionCoordinator.clearRouteMessages()
            val result = routePreviewController.moveStop(
                state = state.uiState.value,
                poiId = poiId,
                direction = direction,
                offlineRouteGenerationMessage = messages.offlineRouteGeneration,
                routeGenerationFailedMessage = messages.routeGenerationFailed
            )
            state.update {
                result.state.copy(
                    isRerouting = false,
                    routeError = result.error ?: result.state.routeError
                )
            }
        }
    }

    fun replacePreviewStop(poiId: Int, preferredPoiId: Int?) {
        scope.launch {
            state.isRerouting = true
            sessionCoordinator.clearRouteMessages()
            val result = routePreviewController.replaceStop(
                state = state.uiState.value,
                poiId = poiId,
                preferredPoiId = preferredPoiId,
                offlineRouteGenerationMessage = messages.offlineRouteGeneration,
                routeGenerationFailedMessage = messages.routeGenerationFailed
            )
            state.update {
                result.state.copy(
                    isRerouting = false,
                    routeError = result.error ?: result.state.routeError
                )
            }
        }
    }
}
