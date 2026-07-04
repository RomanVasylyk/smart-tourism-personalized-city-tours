package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.application.CityCatalogLoadInput
import com.example.smarttourism.features.planner.application.PlannerBootstrapUseCase
import com.example.smarttourism.features.planner.application.PlannerCatalogUseCase
import com.example.smarttourism.features.planner.application.PoiCatalogLoadInput
import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.domain.city.matchesToken
import com.example.smarttourism.features.planner.domain.city.toStartPoint
import com.example.smarttourism.features.planner.domain.history.isRestorable
import com.example.smarttourism.features.planner.domain.history.toRouteFeedback
import com.example.smarttourism.features.planner.domain.history.toSavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.route.nextPendingPoi
import com.example.smarttourism.features.planner.domain.route.parseRouteStartDateTime
import com.example.smarttourism.features.planner.domain.route.progressTotalCount
import com.example.smarttourism.features.planner.state.PlannerStateReducer
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlannerCatalogActions(
    private val state: PlannerStateStore,
    private val scope: CoroutineScope,
    private val deviceId: String,
    private val plannerBootstrapUseCase: PlannerBootstrapUseCase,
    private val plannerCatalogUseCase: PlannerCatalogUseCase,
    private val routePlanningRepository: RoutePlanningRepository,
    private val routeSessionRepository: RouteSessionRepository,
    private val sessionCoordinator: PlannerSessionCoordinator,
    private val messages: PlannerMessages
) {
    private var initialized = false

    fun initialize() {
        if (initialized) {
            return
        }
        initialized = true
        scope.launch {
            bootstrap()
        }
    }

    fun loadRouteHistory(forceRefresh: Boolean) {
        scope.launch {
            sessionCoordinator.refreshRouteHistory(forceRefresh)
        }
    }

    fun selectCity(city: City) {
        if (state.selectedCity?.slug == city.slug) {
            return
        }
        state.selectedCity = city
        state.activeBookmarkId = null
        state.currentRouteRequest = null
        state.requiredPoiIds = emptyList()
        state.update { current ->
            current.copy(
                curatedRoutes = emptyList(),
                isCuratedRoutesLoading = false,
                curatedRoutesError = null
            )
        }
        sessionCoordinator.clearRouteMessages()
        sessionCoordinator.clearDisplayedRoute(cancelActiveSession = true)
        state.startPoint = city.toStartPoint()
        scope.launch {
            loadPoisForCity(city)
        }
    }

    suspend fun loadPoisForCity(city: City) {
        plannerCatalogUseCase.loadPoisForCity(
            input = PoiCatalogLoadInput(
                city = city,
                currentState = state.uiState.value,
                poiPreviewFailedMessage = messages.poiPreviewFailed,
                offlinePoisFallbackMessage = messages.offlinePoisFallback
            )
        ) { stage ->
            state.update { current ->
                stage.state.copy(
                    curatedRoutes = current.curatedRoutes,
                    isCuratedRoutesLoading = current.isCuratedRoutesLoading,
                    curatedRoutesError = current.curatedRoutesError
                )
            }
        }
    }

    private suspend fun bootstrap() {
        val localState = plannerBootstrapUseCase.loadLocalState()
        state.routeBookmarks = localState.bookmarks
        state.routeHistory = localState.history
        val activeSession = localState.activeSession
        var restoredCityToken = activeSession?.snapshot?.request?.city
            ?: activeSession?.snapshot?.response?.city
        state.pendingSyncOperationCount = localState.pendingSyncOperationCount

        activeSession?.let { session ->
            sessionCoordinator.restoreActiveSession(session)
            if (
                state.routeSessionStatus == RouteSessionStatus.COMPLETED &&
                RouteSessionStatus.fromRawValue(session.status) != RouteSessionStatus.COMPLETED
            ) {
                sessionCoordinator.persistRouteSession(
                    status = RouteSessionStatus.COMPLETED,
                    routeIdValue = session.route_id,
                    startedAtValue = session.started_at,
                    visitedIds = state.visitedPoiIds,
                    skippedIds = state.skippedPoiIds,
                    feedback = state.routeFeedback,
                    snapshotOverride = session.snapshot
                )
            }
        }

        plannerBootstrapUseCase.scheduleImmediateSync()

        val remoteSession = plannerBootstrapUseCase.findRestorableRemoteSession(
            deviceId = deviceId,
            routeId = state.routeId,
            routeSessionStatus = state.routeSessionStatus
        )
        if (remoteSession != null) {
            remoteSession.toSavedRouteSnapshot()?.let { snapshot ->
                restoredCityToken = snapshot.request.city.ifBlank { snapshot.response.city }
                restoreSnapshot(snapshot)
                state.routeId = remoteSession.id
                state.routeStartedAt = remoteSession.startedAt
                val restoredStatus = RouteSessionStatus.fromRawValue(remoteSession.status)
                state.routeFeedback = remoteSession.toRouteFeedback()
                state.visitedPoiIds = remoteSession.pois
                    .orEmpty()
                    .filter { poi -> poi.visited && !poi.skipped }
                    .map { poi -> poi.poiId }
                    .distinct()
                state.skippedPoiIds = remoteSession.pois
                    .orEmpty()
                    .filter { poi -> poi.skipped }
                    .map { poi -> poi.poiId }
                    .distinct()
                val nextPendingPoiId = nextPendingPoi(
                    snapshot.response.route,
                    state.visitedPoiIds,
                    state.skippedPoiIds
                )?.poiId
                state.currentTargetPoiId = nextPendingPoiId
                val normalizedStatus = if (restoredStatus.isRestorable() && nextPendingPoiId == null) {
                    RouteSessionStatus.COMPLETED
                } else {
                    restoredStatus
                }
                state.routeSessionStatus = normalizedStatus
                routePlanningRepository.saveSnapshot(snapshot)
                routeSessionRepository.saveActiveSession(
                    ActiveRouteSession(
                        route_id = remoteSession.id,
                        status = normalizedStatus.rawValue,
                        started_at = remoteSession.startedAt,
                        current_target_poi_id = state.currentTargetPoiId,
                        visited_poi_ids = state.visitedPoiIds,
                        skipped_poi_ids = state.skippedPoiIds,
                        progress_visited_count = state.visitedPoiIds.size,
                        progress_total_count = progressTotalCount(snapshot.response.route, state.skippedPoiIds),
                        snapshot = snapshot,
                        feedback = state.routeFeedback
                    )
                )
                if (normalizedStatus != restoredStatus) {
                    sessionCoordinator.persistRouteSession(
                        status = normalizedStatus,
                        routeIdValue = remoteSession.id,
                        startedAtValue = remoteSession.startedAt,
                        visitedIds = state.visitedPoiIds,
                        skippedIds = state.skippedPoiIds,
                        feedback = state.routeFeedback,
                        snapshotOverride = snapshot
                    )
                }
            }
        }

        loadCities(restoredCityToken)
        sessionCoordinator.refreshRouteHistory(forceRefresh = true)
    }

    private fun restoreSnapshot(snapshot: SavedRouteSnapshot) {
        state.update { currentState ->
            PlannerStateReducer.restoreSnapshot(
                state = currentState,
                snapshot = snapshot,
                parsedStartDateTime = parseRouteStartDateTime(snapshot.request.startDateTime)
            )
        }
    }

    private suspend fun loadCities(restoredCityToken: String?) {
        plannerCatalogUseCase.loadCities(
            input = CityCatalogLoadInput(
                restoredCityToken = restoredCityToken,
                currentState = state.uiState.value,
                offlineCitiesFallbackMessage = messages.offlineCitiesFallback
            )
        ) { stage ->
            state.update { currentState ->
                currentState.copy(
                    cities = stage.cities,
                    selectedCity = stage.selectedCity,
                    currentRouteRequest = stage.currentRouteRequest,
                    routeResponse = stage.routeResponse,
                    startPoint = stage.startPoint,
                    offlineStatusMessage = stage.offlineStatusMessage,
                    pendingSyncOperationCount = stage.pendingSyncOperationCount
                        ?: currentState.pendingSyncOperationCount
                )
            }
            if (stage.shouldLoadPoisForSelectedCity && stage.selectedCity != null) {
                loadPoisForCity(stage.selectedCity)
            }
        }
    }

    fun cityForBookmark(citySlug: String, requestCity: String): City? =
        state.cities.firstOrNull { city -> city.matchesToken(citySlug) }
            ?: state.cities.firstOrNull { city -> city.matchesToken(requestCity) }
}
