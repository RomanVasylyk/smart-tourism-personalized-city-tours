package com.example.smarttourism.features.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.smarttourism.R
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.map.MapLocationButton
import com.example.smarttourism.features.map.PoiMapScreen
import com.example.smarttourism.features.planner.components.ActiveRouteBottomPanel
import com.example.smarttourism.features.planner.components.ReplaceRouteStopSheetContent
import com.example.smarttourism.features.planner.components.RequiredPlacesCard
import com.example.smarttourism.features.planner.components.RequiredPlacesPickerSheetContent
import com.example.smarttourism.features.planner.components.RouteFeedbackCard
import com.example.smarttourism.features.planner.components.RouteHistoryDetailsDialog
import com.example.smarttourism.features.planner.components.RouteParametersCard
import com.example.smarttourism.features.planner.components.RoutePreviewSummaryPanel
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
    onStartPointSelected: (Double, Double) -> Unit,
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
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            PlannerModeHeader(mode = com.example.smarttourism.features.planner.state.PlannerMode.PLANNING)
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
                isSelectingStart = isSelectingStart,
                isSelectingRoutePois = false,
                selectedRoutePoiIds = requiredPoiIds,
                onStartPointSelected = onStartPointSelected,
                onRoutePoiSelected = {},
                onOpenFullScreenMap = onOpenFullScreenMap,
                fixedHeight = 280.dp
            )
        }

        item {
            StartPointCard(
                startPoint = startPoint,
                isSelectingStart = isSelectingStart,
                isLocating = isLocating,
                enabled = !isPlannerEditingLocked,
                onToggleMapSelection = onToggleStartSelection,
                onUseCurrentLocation = onUseCurrentLocation
            )
        }

        item {
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

        item {
            RouteParametersCard(
                availableMinutes = availableMinutes,
                maxAvailableMinutes = maxAvailableMinutesLimit,
                onAvailableMinutesChange = onAvailableMinutesChange,
                availableInterests = selectedCityAvailableCategories,
                selectedInterests = selectedInterests,
                onInterestToggle = onInterestToggle,
                pace = pace,
                onPaceChange = onPaceChange,
                returnToStart = returnToStart,
                onReturnToStartChange = onReturnToStartChange,
                respectOpeningHours = respectOpeningHours,
                onRespectOpeningHoursChange = onRespectOpeningHoursChange,
                isPublicTransportAvailable = isPublicTransportAvailable,
                allowPublicTransport = allowPublicTransport,
                onAllowPublicTransportChange = onAllowPublicTransportChange,
                startDateTime = startDateTime,
                onStartDateTimeChange = onStartDateTimeChange,
                onUseCurrentTime = onUseCurrentTime,
                isEditingEnabled = !isPlannerEditingLocked,
                isGenerating = isRouteLoading,
                onGenerateRoute = onGenerateRoute
            )
        }

        plannerAlertItems(plannerAlerts)
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
    onStartPointSelected: (Double, Double) -> Unit,
    onOpenFullScreenMap: () -> Unit,
    onStartRoute: () -> Unit,
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
                onStartPointSelected = onStartPointSelected,
                onRoutePoiSelected = {},
                onOpenFullScreenMap = onOpenFullScreenMap,
                fixedHeight = 360.dp
            )
        }

        plannerAlertItems(plannerAlerts)

        routeResponse?.let { response ->
            item {
                RoutePreviewSummaryPanel(routeResponse = response)
            }
        }

        item {
            PreviewActionRow(
                canStart = !hasPendingRouteChanges && routeItems.isNotEmpty(),
                onStartRoute = onStartRoute,
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
    onStartPointSelected: (Double, Double) -> Unit,
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
                fixedHeight = 320.dp
            )
        }

        routeResponse?.let { response ->
            item {
                RoutePreviewSummaryPanel(routeResponse = response)
            }
        }

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerSheets(
    isParameterSheetOpen: Boolean,
    isRequiredPlacesSheetOpen: Boolean,
    isStopsSheetOpen: Boolean,
    replacingPoiId: Int?,
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    routeItems: List<RouteStop>,
    startPoint: RoutePoint,
    isSelectingStart: Boolean,
    isLocating: Boolean,
    isPlannerEditingLocked: Boolean,
    selectedRequiredPois: List<Poi>,
    requiredPoiIds: List<Int>,
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
    routeSessionStatus: RouteSessionStatus,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    canSkipStops: Boolean,
    isRerouting: Boolean,
    highlightedPoiId: Int?,
    replacementCandidatesForPoi: (Int) -> List<Poi>,
    onDismissParameterSheet: () -> Unit,
    onDismissRequiredPlacesSheet: () -> Unit,
    onDismissStopsSheet: () -> Unit,
    onDismissReplacementSheet: () -> Unit,
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
    onGenerateRoute: () -> Unit,
    onToggleRequiredPoi: (Int) -> Unit,
    onUseBestReplacement: (Int) -> Unit,
    onChooseReplacementCandidate: (targetPoiId: Int, preferredPoiId: Int) -> Unit,
    onMarkVisited: (Int) -> Unit,
    onSkip: (Int) -> Unit
) {
    if (isParameterSheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismissParameterSheet) {
            ParameterSheetContent(
                startPoint = startPoint,
                isSelectingStart = isSelectingStart,
                isLocating = isLocating,
                isPlannerEditingLocked = isPlannerEditingLocked,
                selectedRequiredPois = selectedRequiredPois,
                availablePoiCount = pois.size,
                availableMinutes = availableMinutes,
                maxAvailableMinutesLimit = maxAvailableMinutesLimit,
                selectedCityAvailableCategories = selectedCityAvailableCategories,
                selectedInterests = selectedInterests,
                pace = pace,
                returnToStart = returnToStart,
                respectOpeningHours = respectOpeningHours,
                isPublicTransportAvailable = isPublicTransportAvailable,
                allowPublicTransport = allowPublicTransport,
                startDateTime = startDateTime,
                isRouteLoading = isRouteLoading,
                onToggleStartSelection = onToggleStartSelection,
                onUseCurrentLocation = onUseCurrentLocation,
                onChooseRequiredOnMap = onChooseRequiredOnMap,
                onChooseRequiredFromList = onChooseRequiredFromList,
                onMoveRequiredPoi = onMoveRequiredPoi,
                onRemoveRequiredPoi = onRemoveRequiredPoi,
                onClearRequiredPois = onClearRequiredPois,
                onAvailableMinutesChange = onAvailableMinutesChange,
                onInterestToggle = onInterestToggle,
                onPaceChange = onPaceChange,
                onReturnToStartChange = onReturnToStartChange,
                onRespectOpeningHoursChange = onRespectOpeningHoursChange,
                onAllowPublicTransportChange = onAllowPublicTransportChange,
                onStartDateTimeChange = onStartDateTimeChange,
                onUseCurrentTime = onUseCurrentTime,
                onGenerateRoute = onGenerateRoute
            )
        }
    }

    if (isRequiredPlacesSheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismissRequiredPlacesSheet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                RequiredPlacesPickerSheetContent(
                    pois = pois,
                    selectedPoiIds = requiredPoiIds,
                    isEditingEnabled = !isPlannerEditingLocked,
                    onTogglePoi = onToggleRequiredPoi
                )
            }
        }
    }

    replacingPoiId?.let { targetPoiId ->
        val targetStop = routeItems.firstOrNull { item -> item.poiId == targetPoiId }
        if (targetStop != null) {
            ModalBottomSheet(onDismissRequest = onDismissReplacementSheet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                ) {
                    ReplaceRouteStopSheetContent(
                        targetStopName = targetStop.name,
                        candidates = replacementCandidatesForPoi(targetPoiId),
                        isActionInProgress = isRerouting,
                        onUseBestSuggestion = { onUseBestReplacement(targetPoiId) },
                        onChooseCandidate = { preferredPoiId ->
                            onChooseReplacementCandidate(targetPoiId, preferredPoiId)
                        }
                    )
                }
            }
        }
    }

    if (isStopsSheetOpen) {
        ModalBottomSheet(onDismissRequest = onDismissStopsSheet) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                routeStopItems(
                    titleRes = R.string.route_section_all_stops,
                    routeStops = routeItems,
                    routeResponse = routeResponse,
                    visitedPoiIds = visitedPoiIds,
                    skippedPoiIds = skippedPoiIds,
                    isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                    canSkip = canSkipStops,
                    canReplace = false,
                    canMove = false,
                    isActionInProgress = isRerouting,
                    highlightedPoiId = highlightedPoiId,
                    onMarkVisited = onMarkVisited,
                    onSkip = onSkip,
                    onMove = { _, _ -> },
                    onReplace = {}
                )
            }
        }
    }
}

@Composable
private fun ParameterSheetContent(
    startPoint: RoutePoint,
    isSelectingStart: Boolean,
    isLocating: Boolean,
    isPlannerEditingLocked: Boolean,
    selectedRequiredPois: List<Poi>,
    availablePoiCount: Int,
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
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.route_preview_edit_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.route_preview_edit_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            StartPointCard(
                startPoint = startPoint,
                isSelectingStart = isSelectingStart,
                isLocating = isLocating,
                enabled = !isPlannerEditingLocked,
                onToggleMapSelection = onToggleStartSelection,
                onUseCurrentLocation = onUseCurrentLocation
            )
        }

        item {
            RequiredPlacesCard(
                selectedPois = selectedRequiredPois,
                availablePoiCount = availablePoiCount,
                isEditingEnabled = !isPlannerEditingLocked,
                onChooseOnMap = onChooseRequiredOnMap,
                onChooseFromList = onChooseRequiredFromList,
                onMovePoi = onMoveRequiredPoi,
                onRemovePoi = onRemoveRequiredPoi,
                onClearAll = onClearRequiredPois
            )
        }

        item {
            RouteParametersCard(
                availableMinutes = availableMinutes,
                maxAvailableMinutes = maxAvailableMinutesLimit,
                onAvailableMinutesChange = onAvailableMinutesChange,
                availableInterests = selectedCityAvailableCategories,
                selectedInterests = selectedInterests,
                onInterestToggle = onInterestToggle,
                pace = pace,
                onPaceChange = onPaceChange,
                returnToStart = returnToStart,
                onReturnToStartChange = onReturnToStartChange,
                respectOpeningHours = respectOpeningHours,
                onRespectOpeningHoursChange = onRespectOpeningHoursChange,
                isPublicTransportAvailable = isPublicTransportAvailable,
                allowPublicTransport = allowPublicTransport,
                onAllowPublicTransportChange = onAllowPublicTransportChange,
                startDateTime = startDateTime,
                onStartDateTimeChange = onStartDateTimeChange,
                onUseCurrentTime = onUseCurrentTime,
                isEditingEnabled = !isPlannerEditingLocked,
                isGenerating = isRouteLoading,
                onGenerateRoute = onGenerateRoute
            )
        }
    }
}

@Composable
internal fun PlannerDialogs(
    selectedHistoryEntry: RouteHistoryEntry?,
    isFeedbackDialogOpen: Boolean,
    isMapFullScreen: Boolean,
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    startPoint: RoutePoint,
    selectedCity: City?,
    currentRouteLocation: RoutePoint?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    routeSessionStatus: RouteSessionStatus,
    isPoiLoading: Boolean,
    isSelectingStart: Boolean,
    isSelectingRequiredPlacesOnMap: Boolean,
    requiredPoiIds: List<Int>,
    routeFeedback: RouteFeedback?,
    onDismissHistoryEntry: () -> Unit,
    onDismissFeedback: () -> Unit,
    onFeedbackChange: (RouteFeedback) -> Unit,
    onDismissFullScreenMap: () -> Unit,
    onStartPointSelected: (Double, Double) -> Unit,
    onRoutePoiSelected: (Poi) -> Unit
) {
    selectedHistoryEntry?.let { historyEntry ->
        RouteHistoryDetailsDialog(
            entry = historyEntry,
            onDismiss = onDismissHistoryEntry
        )
    }

    if (isFeedbackDialogOpen) {
        Dialog(onDismissRequest = onDismissFeedback) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    RouteFeedbackCard(
                        feedback = routeFeedback,
                        onFeedbackChange = onFeedbackChange,
                        framed = false
                    )
                }
                Button(
                    onClick = onDismissFeedback,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }

    if (isMapFullScreen) {
        Dialog(
            onDismissRequest = onDismissFullScreenMap,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PoiMapScreen(
                    pois = pois,
                    routeResponse = routeResponse,
                    startLat = startPoint.lat,
                    startLon = startPoint.lon,
                    defaultZoom = selectedCity?.defaultZoom,
                    currentLocation = currentRouteLocation,
                    visitedPoiIds = visitedPoiIds.toSet(),
                    skippedPoiIds = skippedPoiIds.toSet(),
                    isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                    isLoading = isPoiLoading,
                    isFullScreen = true,
                    isSelectingStart = isSelectingStart,
                    isSelectingRoutePois = isSelectingRequiredPlacesOnMap,
                    selectedRoutePoiIds = requiredPoiIds.toSet(),
                    onStartPointSelected = onStartPointSelected,
                    onRoutePoiSelected = onRoutePoiSelected,
                    modifier = Modifier.fillMaxSize()
                )
                OutlinedButton(
                    onClick = onDismissFullScreenMap,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Text(stringResource(R.string.action_close_map))
                }
            }
        }
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
