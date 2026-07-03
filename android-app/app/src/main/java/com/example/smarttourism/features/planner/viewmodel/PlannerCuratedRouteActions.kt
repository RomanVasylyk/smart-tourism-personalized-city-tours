package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.application.CuratedRoutesUseCase
import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.domain.route.parseRouteStartDateTime
import com.example.smarttourism.features.planner.state.PlannerStateReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.temporal.ChronoUnit

internal class PlannerCuratedRouteActions(
    private val state: PlannerStateStore,
    private val scope: CoroutineScope,
    private val curatedRoutesUseCase: CuratedRoutesUseCase,
    private val catalogActions: PlannerCatalogActions,
    private val routePlanningRepository: RoutePlanningRepository,
    private val sessionCoordinator: PlannerSessionCoordinator,
    private val messages: PlannerMessages
) {
    fun loadCuratedRoutes() {
        val citySlug = state.selectedCity?.slug ?: return
        scope.launch {
            state.update { current ->
                current.copy(isCuratedRoutesLoading = true, curatedRoutesError = null)
            }
            val result = runCatching { curatedRoutesUseCase.loadCuratedRoutes(citySlug) }
            state.update { current ->
                result.fold(
                    onSuccess = { routes ->
                        current.copy(
                            curatedRoutes = routes,
                            isCuratedRoutesLoading = false,
                            curatedRoutesError = null
                        )
                    },
                    onFailure = {
                        current.copy(
                            isCuratedRoutesLoading = false,
                            curatedRoutesError = messages.curatedRoutesFailed
                        )
                    }
                )
            }
        }
    }

    fun openCuratedRoute(routeId: Int) {
        scope.launch {
            state.update { current ->
                current.copy(isCuratedRoutesLoading = true, curatedRoutesError = null)
            }
            val startDateTime = state.startDateTime.truncatedTo(ChronoUnit.MINUTES).toString()
            val result = runCatching { curatedRoutesUseCase.openCuratedRoute(routeId, startDateTime) }
            result.fold(
                onSuccess = { opened ->
                    sessionCoordinator.resetRouteSession()
                    sessionCoordinator.clearRouteMessages()
                    state.activeBookmarkId = null
                    restoreSnapshot(opened.snapshot)
                    val city = catalogActions.cityForBookmark(
                        citySlug = opened.citySlug,
                        requestCity = opened.snapshot.request.city
                    )
                    if (city != null) {
                        catalogActions.loadPoisForCity(city)
                    }
                    routePlanningRepository.saveSnapshot(opened.snapshot)
                    state.update { current -> current.copy(isCuratedRoutesLoading = false) }
                },
                onFailure = {
                    state.update { current ->
                        current.copy(
                            isCuratedRoutesLoading = false,
                            curatedRoutesError = messages.curatedRouteOpenFailed
                        )
                    }
                }
            )
        }
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
}
