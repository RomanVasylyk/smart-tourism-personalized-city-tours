package com.example.smarttourism.features.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.map.MapLocationButton
import com.example.smarttourism.features.planner.components.ActiveRouteBottomPanel
import com.example.smarttourism.features.planner.components.RequiredPlacesCard
import com.example.smarttourism.features.planner.components.RouteInterestsCard
import com.example.smarttourism.features.planner.components.RouteOptionsCard
import com.example.smarttourism.features.planner.components.RoutePreviewSummaryPanel
import com.example.smarttourism.features.planner.components.RouteTimingCard
import com.example.smarttourism.features.planner.components.StartPointCard
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import java.time.LocalDateTime

@Composable
internal fun ActiveRouteContent(
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    startPoint: RoutePoint,
    selectedCity: City?,
    currentRouteLocation: RoutePoint?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    requiredPoiIds: List<Int>,
    routeSessionStatus: RouteSessionStatus,
    isPoiLoading: Boolean,
    plannerAlerts: PlannerAlerts,
    progressMetrics: RouteProgressMetrics,
    isRerouting: Boolean,
    canSkipStops: Boolean,
    activeMapRecenterRequest: Int,
    activeRoutePanelHeightPx: Int,
    onActiveMapRecenterRequest: () -> Unit,
    onActiveRoutePanelHeightChange: (Int) -> Unit,
    onStartPointSelected: (Double, Double) -> Unit,
    onOpenFullScreenMap: () -> Unit,
    onMarkVisited: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onPauseRoute: () -> Unit,
    onResumeRoute: () -> Unit,
    onFinishRoute: () -> Unit,
    onCancelRoute: () -> Unit,
    onShowAllStops: () -> Unit
) {
    val density = LocalDensity.current
    val activeLocationButtonBottomPadding = with(density) {
        if (activeRoutePanelHeightPx > 0) {
            activeRoutePanelHeightPx.toDp() + 28.dp
        } else {
            340.dp
        }
    }
    val activeCurrentLocationCameraYOffset = with(density) {
        if (activeRoutePanelHeightPx > 0) {
            (activeRoutePanelHeightPx / 2f).toDp() + 16.dp
        } else {
            180.dp
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        PlannerMapPanel(
            pois = pois,
            routeResponse = routeResponse,
            startPoint = startPoint,
            defaultZoom = selectedCity?.defaultZoom,
            currentRouteLocation = currentRouteLocation,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
            isPoiLoading = isPoiLoading,
            isSelectingStart = false,
            isSelectingRoutePois = false,
            selectedRoutePoiIds = requiredPoiIds,
            highlightedPoiId = progressMetrics.nextTarget?.poiId,
            onStartPointSelected = onStartPointSelected,
            onRoutePoiSelected = {},
            onOpenFullScreenMap = onOpenFullScreenMap,
            preferCurrentLocationCamera = true,
            showLocationButton = false,
            recenterLocationRequestKey = activeMapRecenterRequest,
            currentLocationCameraYOffset = activeCurrentLocationCameraYOffset,
            modifier = Modifier.fillMaxSize()
        )

        if (plannerAlerts.hasVisibleAlerts()) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                PlannerAlertColumn(alerts = plannerAlerts)
            }
        }

        ActiveRouteBottomPanel(
            status = routeSessionStatus,
            metrics = progressMetrics,
            currentLocation = currentRouteLocation,
            isRerouting = isRerouting,
            canSkip = canSkipStops,
            isActionInProgress = isRerouting,
            nextTarget = progressMetrics.nextTarget,
            nextTargetIncomingLeg = routeResponse?.legs.orEmpty()
                .firstOrNull { leg -> leg.to.poiId == progressMetrics.nextTarget?.poiId },
            onMarkVisited = {
                progressMetrics.nextTarget?.let { target -> onMarkVisited(target.poiId) }
            },
            onSkip = {
                progressMetrics.nextTarget?.let { target -> onSkip(target.poiId) }
            },
            onPauseRoute = onPauseRoute,
            onResumeRoute = onResumeRoute,
            onFinishRoute = onFinishRoute,
            onCancelRoute = onCancelRoute,
            onShowAllStops = onShowAllStops,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp)
                .onGloballyPositioned { coordinates ->
                    onActiveRoutePanelHeightChange(coordinates.size.height)
                }
        )

        MapLocationButton(
            enabled = currentRouteLocation != null,
            onClick = onActiveMapRecenterRequest,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 20.dp, bottom = activeLocationButtonBottomPadding)
        )
    }
}

@Composable
internal fun PlanningScreenContent(
    selectedCity: City?,
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    startPoint: RoutePoint,
    currentRouteLocation: RoutePoint?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    requiredPoiIds: List<Int>,
    selectedRequiredPois: List<Poi>,
    isPoiLoading: Boolean,
    isSelectingStart: Boolean,
    isLocating: Boolean,
    isPlannerEditingLocked: Boolean,
    plannerAlerts: PlannerAlerts,
    availableMinutes: Int,
    maxAvailableMinutesLimit: Int,
    selectedCityAvailableCategories: List<String>,
    selectedInterests: List<String>,
    pace: String,
    returnToStart: Boolean,
    respectOpeningHours: Boolean,
    isPublicTransportAvailable: Boolean,
    allowPublicTransport: Boolean,
    startDateTime: LocalDateTime,
    isRouteLoading: Boolean,
    mapRecenterRequestKey: Int,
    onStartPointSelected: (Double, Double) -> Unit,
    onCurrentLocationRequested: () -> Unit,
    onOpenFullScreenMap: () -> Unit,
    onToggleStartSelection: () -> Unit,
    onUseCurrentLocation: () -> Unit,
    onChooseRequiredOnMap: () -> Unit,
    onChooseRequiredFromList: () -> Unit,
    onMoveRequiredPoi: (Int, Int) -> Unit,
    onRemoveRequiredPoi: (Int) -> Unit,
    onClearRequiredPois: () -> Unit,
    onAvailableMinutesChange: (Int) -> Unit,
    onInterestToggle: (String, Boolean) -> Unit,
    onPaceChange: (String) -> Unit,
    onReturnToStartChange: (Boolean) -> Unit,
    onRespectOpeningHoursChange: (Boolean) -> Unit,
    onAllowPublicTransportChange: (Boolean) -> Unit,
    onStartDateTimeChange: (LocalDateTime) -> Unit,
    onUseCurrentTime: () -> Unit,
    onGenerateRoute: () -> Unit
) {
    val canGenerateRoute = selectedCity != null && !isPlannerEditingLocked && !isRouteLoading

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 120.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                PlannerModeHeader(mode = com.example.smarttourism.features.planner.state.PlannerMode.PLANNING)
            }

            item {
                PlanningStepSection(
                    number = 1,
                    title = stringResource(R.string.planning_step_city_time_title),
                    body = stringResource(R.string.planning_step_city_time_body)
                ) {
                    PlannerMapPanel(
                        pois = pois,
                        routeResponse = routeResponse,
                        startPoint = startPoint,
                        defaultZoom = selectedCity?.defaultZoom,
                        currentRouteLocation = currentRouteLocation,
                        visitedPoiIds = visitedPoiIds,
                        skippedPoiIds = skippedPoiIds,
                        isRouteActive = false,
                        isPoiLoading = isPoiLoading,
                        isSelectingStart = isSelectingStart,
                        isSelectingRoutePois = false,
                        selectedRoutePoiIds = requiredPoiIds,
                        onStartPointSelected = onStartPointSelected,
                        onRoutePoiSelected = {},
                        onOpenFullScreenMap = onOpenFullScreenMap,
                        onCurrentLocationRequested = onCurrentLocationRequested,
                        recenterLocationRequestKey = mapRecenterRequestKey,
                        fixedHeight = 280.dp
                    )

                    StartPointCard(
                        startPoint = startPoint,
                        isSelectingStart = isSelectingStart,
                        isLocating = isLocating,
                        enabled = !isPlannerEditingLocked,
                        onToggleMapSelection = onToggleStartSelection,
                        onUseCurrentLocation = onUseCurrentLocation
                    )

                    RouteTimingCard(
                        availableMinutes = availableMinutes,
                        maxAvailableMinutes = maxAvailableMinutesLimit,
                        onAvailableMinutesChange = onAvailableMinutesChange,
                        pace = pace,
                        onPaceChange = onPaceChange,
                        startDateTime = startDateTime,
                        onStartDateTimeChange = onStartDateTimeChange,
                        onUseCurrentTime = onUseCurrentTime,
                        isEditingEnabled = !isPlannerEditingLocked
                    )
                }
            }

            item {
                PlanningStepSection(
                    number = 2,
                    title = stringResource(R.string.planning_step_interests_places_title),
                    body = stringResource(R.string.planning_step_interests_places_body)
                ) {
                    RouteInterestsCard(
                        availableInterests = selectedCityAvailableCategories,
                        selectedInterests = selectedInterests,
                        onInterestToggle = onInterestToggle,
                        isEditingEnabled = !isPlannerEditingLocked
                    )

                    RequiredPlacesCard(
                        selectedPois = selectedRequiredPois,
                        availablePoiCount = pois.size,
                        isEditingEnabled = !isPlannerEditingLocked,
                        onChooseOnMap = onChooseRequiredOnMap,
                        onChooseFromList = onChooseRequiredFromList,
                        onMovePoi = onMoveRequiredPoi,
                        onRemovePoi = onRemoveRequiredPoi,
                        onClearAll = onClearRequiredPois
                    )
                }
            }

            item {
                PlanningStepSection(
                    number = 3,
                    title = stringResource(R.string.planning_step_route_title),
                    body = stringResource(R.string.planning_step_route_body)
                ) {
                    RouteOptionsCard(
                        respectOpeningHours = respectOpeningHours,
                        onRespectOpeningHoursChange = onRespectOpeningHoursChange,
                        returnToStart = returnToStart,
                        onReturnToStartChange = onReturnToStartChange,
                        isPublicTransportAvailable = isPublicTransportAvailable,
                        allowPublicTransport = allowPublicTransport,
                        onAllowPublicTransportChange = onAllowPublicTransportChange,
                        isEditingEnabled = !isPlannerEditingLocked
                    )
                }
            }

            if (plannerAlerts.hasPendingRouteChanges) {
                plannerAlertItems(alerts = plannerAlerts)
            }
        }

        PlanningGenerateBottomBar(
            isGenerating = isRouteLoading,
            enabled = canGenerateRoute,
            onGenerateRoute = onGenerateRoute,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun PlanningStepSection(
    number: Int,
    title: String,
    body: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
            ) {
                Text(
                    text = number.toString(),
                    modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        content()
    }
}

@Composable
private fun PlanningGenerateBottomBar(
    isGenerating: Boolean,
    enabled: Boolean,
    onGenerateRoute: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        tonalElevation = 6.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(
                    if (isGenerating) {
                        R.string.planning_generate_loading_body
                    } else {
                        R.string.planning_generate_ready_body
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(
                onClick = onGenerateRoute,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.size(12.dp))
                    Text(stringResource(R.string.action_generating_route))
                } else {
                    Text(stringResource(R.string.action_generate_route))
                }
            }
        }
    }
}

@Composable
internal fun PreviewScreenContent(
    selectedCity: City?,
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    startPoint: RoutePoint,
    currentRouteLocation: RoutePoint?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    requiredPoiIds: List<Int>,
    isPoiLoading: Boolean,
    plannerAlerts: PlannerAlerts,
    hasPendingRouteChanges: Boolean,
    routeItems: List<RouteStop>,
    canSkipStops: Boolean,
    isRerouting: Boolean,
    highlightedPoiId: Int?,
    isCurrentRouteBookmarked: Boolean,
    mapRecenterRequestKey: Int,
    onStartPointSelected: (Double, Double) -> Unit,
    onCurrentLocationRequested: () -> Unit,
    onOpenFullScreenMap: () -> Unit,
    canAddPublicTransport: Boolean,
    onStartRoute: () -> Unit,
    onAddPublicTransport: () -> Unit,
    onEditParameters: () -> Unit,
    onPlanAnotherRoute: () -> Unit,
    onSaveBookmark: () -> Unit,
    onMarkVisited: (Int) -> Unit,
    onRemovePreviewStop: (Int) -> Unit,
    onMovePreviewStop: (Int, Int) -> Unit,
    onReplacePreviewStop: (Int) -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PlannerModeHeader(mode = com.example.smarttourism.features.planner.state.PlannerMode.PREVIEW)
        }

        item {
            PlannerMapPanel(
                pois = pois,
                routeResponse = routeResponse,
                startPoint = startPoint,
                defaultZoom = selectedCity?.defaultZoom,
                currentRouteLocation = currentRouteLocation,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = skippedPoiIds,
                isRouteActive = false,
                isPoiLoading = isPoiLoading,
                isSelectingStart = false,
                isSelectingRoutePois = false,
                selectedRoutePoiIds = requiredPoiIds,
                highlightedPoiId = highlightedPoiId,
                onStartPointSelected = onStartPointSelected,
                onRoutePoiSelected = {},
                onOpenFullScreenMap = onOpenFullScreenMap,
                onCurrentLocationRequested = onCurrentLocationRequested,
                recenterLocationRequestKey = mapRecenterRequestKey,
                fixedHeight = 360.dp
            )
        }

        plannerAlertItems(plannerAlerts)

        routeResponse?.let { response ->
            item {
                RoutePreviewSummaryPanel(routeResponse = response)
            }
        }

        routeQualityItems(routeResponse, pois)

        item {
            PreviewActionRow(
                canStart = !hasPendingRouteChanges && routeItems.isNotEmpty(),
                canAddPublicTransport = canAddPublicTransport,
                isAddingPublicTransport = isRerouting,
                onStartRoute = onStartRoute,
                onAddPublicTransport = onAddPublicTransport,
                onEditParameters = onEditParameters,
                onPlanAnotherRoute = onPlanAnotherRoute
            )
        }

        item {
            SaveRouteBookmarkButton(
                isCurrentRouteBookmarked = isCurrentRouteBookmarked,
                onSaveBookmark = onSaveBookmark
            )
        }

        routeStopItems(
            titleRes = R.string.route_section_all_stops,
            routeStops = routeItems,
            routeResponse = routeResponse,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            isRouteActive = false,
            canSkip = canSkipStops,
            canReplace = true,
            canMove = true,
            isActionInProgress = isRerouting,
            highlightedPoiId = highlightedPoiId,
            onMarkVisited = onMarkVisited,
            onSkip = onRemovePreviewStop,
            onMove = onMovePreviewStop,
            onReplace = onReplacePreviewStop
        )
    }
}

@Composable
internal fun CompletedRouteContent(
    selectedCity: City?,
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    startPoint: RoutePoint,
    currentRouteLocation: RoutePoint?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    requiredPoiIds: List<Int>,
    isPoiLoading: Boolean,
    plannerAlerts: PlannerAlerts,
    routeItems: List<RouteStop>,
    isCurrentRouteBookmarked: Boolean,
    hasFeedback: Boolean,
    mapRecenterRequestKey: Int,
    onStartPointSelected: (Double, Double) -> Unit,
    onCurrentLocationRequested: () -> Unit,
    onOpenFullScreenMap: () -> Unit,
    onPlanAnotherRoute: () -> Unit,
    onSaveBookmark: () -> Unit,
    onOpenFeedback: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PlannerModeHeader(mode = com.example.smarttourism.features.planner.state.PlannerMode.COMPLETED)
        }

        item {
            PlannerMapPanel(
                pois = pois,
                routeResponse = routeResponse,
                startPoint = startPoint,
                defaultZoom = selectedCity?.defaultZoom,
                currentRouteLocation = currentRouteLocation,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = skippedPoiIds,
                isRouteActive = false,
                isPoiLoading = isPoiLoading,
                isSelectingStart = false,
                isSelectingRoutePois = false,
                selectedRoutePoiIds = requiredPoiIds,
                onStartPointSelected = onStartPointSelected,
                onRoutePoiSelected = {},
                onOpenFullScreenMap = onOpenFullScreenMap,
                onCurrentLocationRequested = onCurrentLocationRequested,
                recenterLocationRequestKey = mapRecenterRequestKey,
                fixedHeight = 320.dp
            )
        }

        routeResponse?.let { response ->
            item {
                RoutePreviewSummaryPanel(routeResponse = response)
            }
        }

        routeQualityItems(routeResponse, pois)

        item {
            Button(
                onClick = onPlanAnotherRoute,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.action_plan_another_route))
            }
        }

        item {
            SaveRouteBookmarkButton(
                isCurrentRouteBookmarked = isCurrentRouteBookmarked,
                onSaveBookmark = onSaveBookmark
            )
        }

        item {
            OutlinedButton(
                onClick = onOpenFeedback,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(
                        if (!hasFeedback) {
                            R.string.action_leave_feedback
                        } else {
                            R.string.action_edit_feedback
                        }
                    )
                )
            }
        }

        plannerAlertItems(plannerAlerts)

        routeStopItems(
            titleRes = R.string.route_section_all_stops,
            routeStops = routeItems,
            routeResponse = routeResponse,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            isRouteActive = false,
            canSkip = false,
            canReplace = false,
            canMove = false,
            isActionInProgress = false,
            highlightedPoiId = null,
            onMarkVisited = {},
            onSkip = {},
            onMove = { _, _ -> },
            onReplace = {}
        )
    }
}

@Composable
private fun SaveRouteBookmarkButton(
    isCurrentRouteBookmarked: Boolean,
    onSaveBookmark: () -> Unit
) {
    Button(
        onClick = onSaveBookmark,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            stringResource(
                if (isCurrentRouteBookmarked) {
                    R.string.action_update_route_bookmark
                } else {
                    R.string.action_save_route_bookmark
                }
            )
        )
    }
}
