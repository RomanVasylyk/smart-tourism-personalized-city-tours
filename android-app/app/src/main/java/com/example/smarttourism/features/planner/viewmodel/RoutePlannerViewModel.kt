package com.example.smarttourism.features.planner.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttourism.features.planner.application.ActiveRouteController
import com.example.smarttourism.features.planner.application.BookmarkController
import com.example.smarttourism.features.planner.application.OfflineMapController
import com.example.smarttourism.features.planner.application.PlannerBootstrapUseCase
import com.example.smarttourism.features.planner.application.PlannerCatalogUseCase
import com.example.smarttourism.features.planner.application.RouteGenerationUseCase
import com.example.smarttourism.features.planner.application.RouteHistoryController
import com.example.smarttourism.features.planner.application.RoutePreviewController
import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.state.PlannerEvent
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
internal class RoutePlannerViewModel @Inject constructor(
    @ApplicationContext appContext: Context,
    routePlanningRepository: RoutePlanningRepository,
    routeSessionRepository: RouteSessionRepository,
    offlineSyncRepository: OfflineSyncRepository,
    plannerBootstrapUseCase: PlannerBootstrapUseCase,
    plannerCatalogUseCase: PlannerCatalogUseCase,
    routeGenerationUseCase: RouteGenerationUseCase,
    activeRouteController: ActiveRouteController,
    routeHistoryController: RouteHistoryController,
    bookmarkController: BookmarkController,
    offlineMapController: OfflineMapController,
    routePreviewController: RoutePreviewController
) : ViewModel() {
    private val stateStore = PlannerStateStore()
    private val messages = PlannerMessages.from(appContext)
    private val deviceId = routeSessionRepository.getOrCreateDeviceId()

    val uiState: StateFlow<RoutePlannerUiState> = stateStore.uiState
    val progressMetrics: RouteProgressMetrics
        get() = stateStore.progressMetrics

    private val sessionCoordinator = PlannerSessionCoordinator(
        state = stateStore,
        scope = viewModelScope,
        deviceId = deviceId,
        routePlanningRepository = routePlanningRepository,
        routeSessionRepository = routeSessionRepository,
        offlineSyncRepository = offlineSyncRepository,
        activeRouteController = activeRouteController,
        routeHistoryController = routeHistoryController,
        messages = messages
    )

    private val catalogActions = PlannerCatalogActions(
        state = stateStore,
        scope = viewModelScope,
        deviceId = deviceId,
        plannerBootstrapUseCase = plannerBootstrapUseCase,
        plannerCatalogUseCase = plannerCatalogUseCase,
        routePlanningRepository = routePlanningRepository,
        routeSessionRepository = routeSessionRepository,
        sessionCoordinator = sessionCoordinator,
        messages = messages
    )

    private val routePlanningActions = PlannerRoutePlanningActions(
        state = stateStore,
        scope = viewModelScope,
        routeGenerationUseCase = routeGenerationUseCase,
        routePreviewController = routePreviewController,
        sessionCoordinator = sessionCoordinator,
        messages = messages
    )

    private val bookmarkActions = PlannerBookmarkActions(
        state = stateStore,
        scope = viewModelScope,
        bookmarkController = bookmarkController,
        catalogActions = catalogActions,
        sessionCoordinator = sessionCoordinator
    )

    private val activeRouteActions = PlannerActiveRouteActions(
        state = stateStore,
        activeRouteController = activeRouteController,
        offlineSyncRepository = offlineSyncRepository,
        sessionCoordinator = sessionCoordinator
    )

    private val offlineMapActions = PlannerOfflineMapActions(
        state = stateStore,
        scope = viewModelScope,
        offlineMapController = offlineMapController,
        messages = messages
    )

    private val eventDispatcher = PlannerEventDispatcher(
        PlannerEventActionFacade(
            state = stateStore,
            catalogActions = catalogActions,
            routePlanningActions = routePlanningActions,
            bookmarkActions = bookmarkActions,
            activeRouteActions = activeRouteActions,
            offlineMapActions = offlineMapActions,
            sessionCoordinator = sessionCoordinator
        )
    )

    fun onEvent(event: PlannerEvent) {
        eventDispatcher.dispatch(event)
    }

    fun previewReplacementCandidates(poiId: Int): List<Poi> =
        routePlanningActions.previewReplacementCandidates(poiId)
}
