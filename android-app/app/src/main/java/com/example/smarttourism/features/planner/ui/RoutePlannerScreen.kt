package com.example.smarttourism.features.planner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarttourism.R
import com.example.smarttourism.core.i18n.AppLanguage
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.map.MapLocationButton
import com.example.smarttourism.features.map.PoiMapScreen
import com.example.smarttourism.features.planner.components.ActiveRouteBottomPanel
import com.example.smarttourism.features.planner.components.CitySelectorCard
import com.example.smarttourism.features.planner.components.OfflineSupportCard
import com.example.smarttourism.features.planner.components.ReplaceRouteStopSheetContent
import com.example.smarttourism.features.planner.components.RequiredPlacesCard
import com.example.smarttourism.features.planner.components.RequiredPlacesPickerSheetContent
import com.example.smarttourism.features.planner.components.RouteBookmarksSheetContent
import com.example.smarttourism.features.planner.components.RouteFeedbackCard
import com.example.smarttourism.features.planner.components.RouteHistoryDetailsDialog
import com.example.smarttourism.features.planner.components.RouteHistorySheetContent
import com.example.smarttourism.features.planner.components.RouteParametersCard
import com.example.smarttourism.features.planner.components.RoutePreviewSummaryPanel
import com.example.smarttourism.features.planner.components.RouteStopTimelineItem
import com.example.smarttourism.features.planner.components.RouteSummaryCard
import com.example.smarttourism.features.planner.components.RouteTrackingCard
import com.example.smarttourism.features.planner.components.StartPointCard
import com.example.smarttourism.features.planner.components.StatusCard
import com.example.smarttourism.features.planner.state.PlannerMode
import com.example.smarttourism.features.planner.state.PlannerEvent
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.TrackingPermissionAction
import com.example.smarttourism.features.planner.viewmodel.RoutePlannerViewModel
import com.example.smarttourism.features.planner.location.fetchCurrentLocation
import com.example.smarttourism.features.planner.location.startRouteLocationTracking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val plannerViewModel: RoutePlannerViewModel = viewModel()
    val uiState by plannerViewModel.uiState.collectAsStateWithLifecycle()
    val onPlannerEvent = plannerViewModel::onEvent
    val locationPermissionDeniedMessage = stringResource(R.string.error_location_permission_denied)

    val cities = uiState.cities
    val selectedCity = uiState.selectedCity
    val pois = uiState.pois
    val isPoiLoading = uiState.isPoiLoading
    val poiError = uiState.poiError
    val offlineStatusMessage = uiState.offlineStatusMessage
    val pendingSyncOperationCount = uiState.pendingSyncOperationCount
    val offlineStoredRegion = uiState.offlineStoredRegion
    val isOfflineMapBusy = uiState.isOfflineMapBusy
    val offlineMapProgress = uiState.offlineMapProgress
    val offlineMapMessage = uiState.offlineMapMessage
    val routeResponse = uiState.routeResponse
    val routeBookmarks = uiState.routeBookmarks
    val routeHistory = uiState.routeHistory
    val isRouteLoading = uiState.isRouteLoading
    val isRouteHistoryLoading = uiState.isRouteHistoryLoading
    val routeError = uiState.routeError
    val routeHistoryError = uiState.routeHistoryError
    val isRerouting = uiState.isRerouting
    val availableMinutes = uiState.availableMinutes
    val maxAvailableMinutesLimit = uiState.maxAvailableMinutesLimit
    val pace = uiState.pace
    val returnToStart = uiState.returnToStart
    val respectOpeningHours = uiState.respectOpeningHours
    val allowPublicTransport = uiState.allowPublicTransport
    val startPoint = uiState.startPoint
    val startDateTime = uiState.startDateTime
    val routeSessionStatus = uiState.routeSessionStatus
    val routeId = uiState.routeId
    val routeStartedAt = uiState.routeStartedAt
    val currentRouteLocation = uiState.currentRouteLocation
    val trackingError = uiState.trackingError
    val routeFeedback = uiState.routeFeedback
    val selectedInterests = uiState.selectedInterests
    val requiredPoiIds = uiState.requiredPoiIds
    val visitedPoiIds = uiState.visitedPoiIds
    val skippedPoiIds = uiState.skippedPoiIds
    val routeItems = uiState.routeItems
    val hasPendingRouteChanges = uiState.hasPendingRouteChanges
    val hasNoGeneratedStops = uiState.hasNoGeneratedStops
    val progressMetrics = plannerViewModel.progressMetrics
    val selectedCityAvailableCategories = uiState.selectedCityAvailableCategories
    val isPublicTransportAvailable = uiState.isPublicTransportAvailable
    val activeBookmarkId = uiState.activeBookmarkId
    val isCurrentRouteBookmarked = uiState.isCurrentRouteBookmarked
    val isPlannerEditingLocked =
        routeSessionStatus == RouteSessionStatus.IN_PROGRESS ||
            routeSessionStatus == RouteSessionStatus.PAUSED
    val plannerMode = plannerModeFor(routeResponse = routeResponse, status = routeSessionStatus)
    val canSkipStops =
        routeSessionStatus != RouteSessionStatus.COMPLETED &&
            routeSessionStatus != RouteSessionStatus.CANCELLED

    var isSelectingStart by remember { mutableStateOf(false) }
    var isSelectingRequiredPlacesOnMap by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(false) }
    var isMapFullScreen by remember { mutableStateOf(false) }
    var isParameterSheetOpen by remember { mutableStateOf(false) }
    var isStopsSheetOpen by remember { mutableStateOf(false) }
    var isRequiredPlacesSheetOpen by remember { mutableStateOf(false) }
    var isFeedbackDialogOpen by remember { mutableStateOf(false) }
    var currentDestination by remember { mutableStateOf(PlannerDestination.PLANNER) }
    var selectedHistoryEntry by remember { mutableStateOf<RouteHistoryEntry?>(null) }
    var replacingPoiId by remember { mutableStateOf<Int?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var trackingPermissionAction by remember { mutableStateOf(TrackingPermissionAction.START) }
    var autoOpenedFeedbackToken by remember { mutableStateOf<String?>(null) }
    var activeMapRecenterRequest by remember { mutableIntStateOf(0) }
    var activeRoutePanelHeightPx by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        onPlannerEvent(PlannerEvent.Initialize)
    }

    LaunchedEffect(plannerMode) {
        if (plannerMode != PlannerMode.PREVIEW) {
            isParameterSheetOpen = false
            replacingPoiId = null
        }
        if (plannerMode == PlannerMode.ACTIVE || plannerMode == PlannerMode.COMPLETED) {
            isSelectingStart = false
            isSelectingRequiredPlacesOnMap = false
        }
        if (plannerMode != PlannerMode.ACTIVE) {
            isStopsSheetOpen = false
        }
        if (plannerMode == PlannerMode.ACTIVE) {
            currentDestination = PlannerDestination.PLANNER
        }
        if (plannerMode != PlannerMode.COMPLETED) {
            isFeedbackDialogOpen = false
        }
    }

    LaunchedEffect(currentDestination) {
        if (currentDestination == PlannerDestination.HISTORY) {
            onPlannerEvent(PlannerEvent.LoadRouteHistory(forceRefresh = true))
        } else {
            selectedHistoryEntry = null
        }
    }

    LaunchedEffect(plannerMode, routeId, routeStartedAt, routeFeedback) {
        val feedbackToken = routeId ?: routeStartedAt ?: "completed"
        if (
            plannerMode == PlannerMode.COMPLETED &&
            routeFeedback == null &&
            autoOpenedFeedbackToken != feedbackToken
        ) {
            isFeedbackDialogOpen = true
            autoOpenedFeedbackToken = feedbackToken
        }
    }

    fun updateStartPoint(lat: Double, lon: Double) {
        onPlannerEvent(PlannerEvent.UpdateStartPoint(lat, lon))
        isSelectingStart = false
        isSelectingRequiredPlacesOnMap = false
        isMapFullScreen = false
        locationError = null
    }

    fun openRequiredPlacesMapPicker() {
        isSelectingStart = false
        isSelectingRequiredPlacesOnMap = true
        isMapFullScreen = true
        locationError = null
    }

    fun selectedRequiredPois(): List<Poi> {
        val poisById = pois.associateBy { poi -> poi.id }
        return requiredPoiIds.mapNotNull { poiId -> poisById[poiId] }
    }

    fun requestCurrentDeviceLocation() {
        isLocating = true
        locationError = null
        fetchCurrentLocation(
            context = context,
            onSuccess = { lat, lon ->
                isLocating = false
                updateStartPoint(lat, lon)
            },
            onError = { message ->
                isLocating = false
                locationError = message
            }
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestCurrentDeviceLocation()
        } else {
            isLocating = false
            locationError = locationPermissionDeniedMessage
        }
    }

    val trackingPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            when (trackingPermissionAction) {
                TrackingPermissionAction.START -> onPlannerEvent(PlannerEvent.ActivateRouteTracking)
                TrackingPermissionAction.RESUME -> onPlannerEvent(PlannerEvent.ResumeRoute)
            }
        } else {
            onPlannerEvent(PlannerEvent.TrackingError(locationPermissionDeniedMessage))
        }
    }

    DisposableEffect(context, routeItems, routeSessionStatus) {
        if (routeSessionStatus != RouteSessionStatus.IN_PROGRESS) {
            onDispose { }
        } else {
            val stopTracking = startRouteLocationTracking(
                context = context,
                onLocation = { location ->
                    onPlannerEvent(
                        PlannerEvent.TrackedLocation(
                            RoutePoint(
                                lat = location.latitude,
                                lon = location.longitude
                            )
                        )
                    )
                },
                onError = { message ->
                    onPlannerEvent(PlannerEvent.TrackingError(message))
                }
            )

            onDispose {
                stopTracking()
            }
        }
    }

    fun startRoute() {
        isSelectingStart = false

        val hasFineLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocationPermission) {
            onPlannerEvent(PlannerEvent.ActivateRouteTracking)
        } else {
            trackingPermissionAction = TrackingPermissionAction.START
            trackingPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun resumeRoute() {
        isSelectingStart = false

        val hasFineLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocationPermission) {
            onPlannerEvent(PlannerEvent.ResumeRoute)
        } else {
            trackingPermissionAction = TrackingPermissionAction.RESUME
            trackingPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun openFullScreenMap() {
        isMapFullScreen = true
    }

    fun resetToPlanning() {
        isSelectingStart = false
        isParameterSheetOpen = false
        onPlannerEvent(PlannerEvent.ClearDisplayedRoute(cancelActiveSession = false))
    }

    val plannerAlerts = PlannerAlerts(
        locationError = locationError,
        poiError = poiError,
        routeError = routeError,
        trackingError = trackingError,
        hasNoGeneratedStops = hasNoGeneratedStops,
        hasPendingRouteChanges = hasPendingRouteChanges && routeResponse != null
    )

    if (plannerMode == PlannerMode.ACTIVE) {
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
                onStartPointSelected = ::updateStartPoint,
                onRoutePoiSelected = {},
                onOpenFullScreenMap = ::openFullScreenMap,
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
                    progressMetrics.nextTarget?.let { target ->
                        onPlannerEvent(PlannerEvent.MarkRouteStopVisited(target.poiId))
                    }
                },
                onSkip = {
                    progressMetrics.nextTarget?.let { target ->
                        onPlannerEvent(PlannerEvent.SkipRouteStop(target.poiId))
                    }
                },
                onPauseRoute = { onPlannerEvent(PlannerEvent.PauseRoute) },
                onResumeRoute = ::resumeRoute,
                onFinishRoute = { onPlannerEvent(PlannerEvent.FinishRoute) },
                onCancelRoute = { onPlannerEvent(PlannerEvent.CancelRoute) },
                onShowAllStops = { isStopsSheetOpen = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .onGloballyPositioned { coordinates ->
                        activeRoutePanelHeightPx = coordinates.size.height
                    }
            )

            MapLocationButton(
                enabled = currentRouteLocation != null,
                onClick = { activeMapRecenterRequest += 1 },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 20.dp, bottom = activeLocationButtonBottomPadding)
            )
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            PlannerTopBar(
                currentDestination = currentDestination,
                selectedLanguage = selectedLanguage,
                cities = cities,
                selectedCity = selectedCity,
                routeBookmarkCount = routeBookmarks.size,
                isCitySelectionEnabled = !isPlannerEditingLocked,
                onDestinationSelected = { destination -> currentDestination = destination },
                onCitySelected = { city ->
                    if (selectedCity?.slug != city.slug) {
                        onPlannerEvent(PlannerEvent.SelectCity(city))
                    }
                },
                onLanguageSelected = onLanguageSelected
            )

            when (currentDestination) {
                PlannerDestination.PLANNER -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        PlannerModeHeader(mode = plannerMode)
                    }

                    when (plannerMode) {
                PlannerMode.PLANNING -> {
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
                            onStartPointSelected = ::updateStartPoint,
                            onRoutePoiSelected = {},
                            onOpenFullScreenMap = ::openFullScreenMap,
                            fixedHeight = 280.dp
                        )
                    }

                    item {
                        StartPointCard(
                            startPoint = startPoint,
                            isSelectingStart = isSelectingStart,
                            isLocating = isLocating,
                            enabled = !isPlannerEditingLocked,
                            onToggleMapSelection = {
                                val isEnteringSelection = !isSelectingStart
                                isSelectingStart = isEnteringSelection
                                if (isEnteringSelection) {
                                    openFullScreenMap()
                                }
                                locationError = null
                            },
                            onUseCurrentLocation = {
                                isSelectingStart = false

                                val hasFineLocationPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED

                                if (hasFineLocationPermission) {
                                    requestCurrentDeviceLocation()
                                } else {
                                    locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                                }
                            }
                        )
                    }

                    item {
                        RequiredPlacesCard(
                            selectedPois = selectedRequiredPois(),
                            availablePoiCount = pois.size,
                            isEditingEnabled = !isPlannerEditingLocked,
                            onChooseOnMap = ::openRequiredPlacesMapPicker,
                            onChooseFromList = { isRequiredPlacesSheetOpen = true },
                            onMovePoi = { poiId, direction -> onPlannerEvent(PlannerEvent.MoveRequiredPoi(poiId, direction)) },
                            onRemovePoi = { poiId -> onPlannerEvent(PlannerEvent.RemoveRequiredPoi(poiId)) },
                            onClearAll = { onPlannerEvent(PlannerEvent.ClearRequiredPois) }
                        )
                    }

                    item {
                        RouteParametersCard(
                            availableMinutes = availableMinutes,
                            maxAvailableMinutes = maxAvailableMinutesLimit,
                            onAvailableMinutesChange = { onPlannerEvent(PlannerEvent.UpdateAvailableMinutes(it)) },
                            availableInterests = selectedCityAvailableCategories,
                            selectedInterests = selectedInterests,
                            onInterestToggle = { interest, checked ->
                                onPlannerEvent(PlannerEvent.ToggleInterest(interest, checked))
                            },
                            pace = pace,
                            onPaceChange = { onPlannerEvent(PlannerEvent.UpdatePace(it)) },
                            returnToStart = returnToStart,
                            onReturnToStartChange = { onPlannerEvent(PlannerEvent.UpdateReturnToStart(it)) },
                            respectOpeningHours = respectOpeningHours,
                            onRespectOpeningHoursChange = { onPlannerEvent(PlannerEvent.UpdateRespectOpeningHours(it)) },
                            isPublicTransportAvailable = isPublicTransportAvailable,
                            allowPublicTransport = allowPublicTransport,
                            onAllowPublicTransportChange = { onPlannerEvent(PlannerEvent.UpdateAllowPublicTransport(it)) },
                            startDateTime = startDateTime,
                            onStartDateTimeChange = { onPlannerEvent(PlannerEvent.UpdateStartDateTime(it)) },
                            onUseCurrentTime = { onPlannerEvent(PlannerEvent.UseCurrentTime) },
                            isEditingEnabled = !isPlannerEditingLocked,
                            isGenerating = isRouteLoading,
                            onGenerateRoute = { onPlannerEvent(PlannerEvent.GenerateRoute) }
                        )
                    }

                    plannerAlertItems(plannerAlerts)
                }

                PlannerMode.PREVIEW -> {
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
                            onStartPointSelected = ::updateStartPoint,
                            onRoutePoiSelected = {},
                            onOpenFullScreenMap = ::openFullScreenMap,
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
                            onStartRoute = ::startRoute,
                            onEditParameters = { isParameterSheetOpen = true },
                            onPlanAnotherRoute = ::resetToPlanning
                        )
                    }

                    item {
                        Button(
                            onClick = { onPlannerEvent(PlannerEvent.SaveCurrentRouteBookmark) },
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
                        highlightedPoiId = progressMetrics.nextTarget?.poiId,
                        onMarkVisited = { poiId -> onPlannerEvent(PlannerEvent.MarkRouteStopVisited(poiId)) },
                        onSkip = { poiId -> onPlannerEvent(PlannerEvent.RemovePreviewStop(poiId)) },
                        onMove = { poiId, direction -> onPlannerEvent(PlannerEvent.MovePreviewStop(poiId, direction)) },
                        onReplace = { poiId -> replacingPoiId = poiId }
                    )
                }

                PlannerMode.COMPLETED -> {
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
                            onStartPointSelected = ::updateStartPoint,
                            onRoutePoiSelected = {},
                            onOpenFullScreenMap = ::openFullScreenMap,
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
                            onClick = ::resetToPlanning,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(stringResource(R.string.action_plan_another_route))
                        }
                    }

                    item {
                        Button(
                            onClick = { onPlannerEvent(PlannerEvent.SaveCurrentRouteBookmark) },
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

                    item {
                        OutlinedButton(
                            onClick = { isFeedbackDialogOpen = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                stringResource(
                                    if (routeFeedback == null) {
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

                PlannerMode.ACTIVE -> Unit
                    }
                }

                PlannerDestination.SAVED_ROUTES -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        RouteBookmarksSheetContent(
                            bookmarks = routeBookmarks,
                            activeBookmarkId = activeBookmarkId,
                            onOpenBookmark = { bookmarkId ->
                                onPlannerEvent(PlannerEvent.OpenRouteBookmark(bookmarkId))
                                currentDestination = PlannerDestination.PLANNER
                            },
                            onDeleteBookmark = { bookmarkId ->
                                onPlannerEvent(PlannerEvent.DeleteRouteBookmark(bookmarkId))
                            }
                        )
                    }
                }

                PlannerDestination.HISTORY -> Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp)
                ) {
                    RouteHistorySheetContent(
                        historyEntries = routeHistory,
                        currentRouteId = routeId,
                        isLoading = isRouteHistoryLoading,
                        errorMessage = routeHistoryError,
                        onRefresh = { onPlannerEvent(PlannerEvent.LoadRouteHistory(forceRefresh = true)) },
                        onOpenEntry = { entry -> selectedHistoryEntry = entry }
                    )
                }

                PlannerDestination.OFFLINE -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        OfflineSupportCard(
                            selectedCity = selectedCity,
                            offlineStatusMessage = offlineStatusMessage,
                            pendingSyncOperationCount = pendingSyncOperationCount,
                            offlineRegionAvailable = offlineStoredRegion != null,
                            isOfflineMapBusy = isOfflineMapBusy,
                            offlineMapProgress = offlineMapProgress,
                            offlineMapMessage = offlineMapMessage,
                            onDownloadOfflineMap = { onPlannerEvent(PlannerEvent.DownloadOfflineMap) },
                            onDeleteOfflineMap = { onPlannerEvent(PlannerEvent.DeleteOfflineMap) }
                        )
                    }
                }
            }
        }
    }

    if (isParameterSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isParameterSheetOpen = false }
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
                        onToggleMapSelection = {
                            val isEnteringSelection = !isSelectingStart
                            isSelectingStart = isEnteringSelection
                            isSelectingRequiredPlacesOnMap = false
                            if (isEnteringSelection) {
                                openFullScreenMap()
                            }
                            locationError = null
                        },
                        onUseCurrentLocation = {
                            isSelectingStart = false

                            val hasFineLocationPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.ACCESS_FINE_LOCATION
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasFineLocationPermission) {
                                requestCurrentDeviceLocation()
                            } else {
                                locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                            }
                        }
                    )
                }

                item {
                    RequiredPlacesCard(
                        selectedPois = selectedRequiredPois(),
                        availablePoiCount = pois.size,
                        isEditingEnabled = !isPlannerEditingLocked,
                        onChooseOnMap = ::openRequiredPlacesMapPicker,
                        onChooseFromList = { isRequiredPlacesSheetOpen = true },
                        onMovePoi = { poiId, direction -> onPlannerEvent(PlannerEvent.MoveRequiredPoi(poiId, direction)) },
                        onRemovePoi = { poiId -> onPlannerEvent(PlannerEvent.RemoveRequiredPoi(poiId)) },
                        onClearAll = { onPlannerEvent(PlannerEvent.ClearRequiredPois) }
                    )
                }

                item {
                    RouteParametersCard(
                        availableMinutes = availableMinutes,
                        maxAvailableMinutes = maxAvailableMinutesLimit,
                        onAvailableMinutesChange = { onPlannerEvent(PlannerEvent.UpdateAvailableMinutes(it)) },
                        availableInterests = selectedCityAvailableCategories,
                        selectedInterests = selectedInterests,
                        onInterestToggle = { interest, checked ->
                            onPlannerEvent(PlannerEvent.ToggleInterest(interest, checked))
                        },
                        pace = pace,
                        onPaceChange = { onPlannerEvent(PlannerEvent.UpdatePace(it)) },
                        returnToStart = returnToStart,
                        onReturnToStartChange = { onPlannerEvent(PlannerEvent.UpdateReturnToStart(it)) },
                        respectOpeningHours = respectOpeningHours,
                        onRespectOpeningHoursChange = { onPlannerEvent(PlannerEvent.UpdateRespectOpeningHours(it)) },
                        isPublicTransportAvailable = isPublicTransportAvailable,
                        allowPublicTransport = allowPublicTransport,
                        onAllowPublicTransportChange = { onPlannerEvent(PlannerEvent.UpdateAllowPublicTransport(it)) },
                        startDateTime = startDateTime,
                        onStartDateTimeChange = { onPlannerEvent(PlannerEvent.UpdateStartDateTime(it)) },
                        onUseCurrentTime = { onPlannerEvent(PlannerEvent.UseCurrentTime) },
                        isEditingEnabled = !isPlannerEditingLocked,
                        isGenerating = isRouteLoading,
                        onGenerateRoute = {
                            onPlannerEvent(PlannerEvent.GenerateRoute)
                            isParameterSheetOpen = false
                        }
                    )
                }
            }
        }
    }

    if (isRequiredPlacesSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isRequiredPlacesSheetOpen = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                RequiredPlacesPickerSheetContent(
                    pois = pois,
                    selectedPoiIds = requiredPoiIds,
                    isEditingEnabled = !isPlannerEditingLocked,
                    onTogglePoi = { poiId -> onPlannerEvent(PlannerEvent.ToggleRequiredPoi(poiId)) }
                )
            }
        }
    }

    selectedHistoryEntry?.let { historyEntry ->
        RouteHistoryDetailsDialog(
            entry = historyEntry,
            onDismiss = { selectedHistoryEntry = null }
        )
    }

    replacingPoiId?.let { targetPoiId ->
        val targetStop = routeItems.firstOrNull { item -> item.poiId == targetPoiId }
        if (targetStop != null) {
            ModalBottomSheet(
                onDismissRequest = { replacingPoiId = null }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                ) {
                    ReplaceRouteStopSheetContent(
                        targetStopName = targetStop.name,
                        candidates = plannerViewModel.previewReplacementCandidates(targetPoiId),
                        isActionInProgress = isRerouting,
                        onUseBestSuggestion = {
                            onPlannerEvent(PlannerEvent.ReplacePreviewStop(targetPoiId))
                            replacingPoiId = null
                        },
                        onChooseCandidate = { preferredPoiId ->
                            onPlannerEvent(PlannerEvent.ReplacePreviewStop(targetPoiId, preferredPoiId))
                            replacingPoiId = null
                        }
                    )
                }
            }
        }
    }

    if (isStopsSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isStopsSheetOpen = false }
        ) {
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
                    highlightedPoiId = progressMetrics.nextTarget?.poiId,
                    onMarkVisited = { poiId -> onPlannerEvent(PlannerEvent.MarkRouteStopVisited(poiId)) },
                    onSkip = { poiId -> onPlannerEvent(PlannerEvent.SkipRouteStop(poiId)) },
                    onMove = { _, _ -> },
                    onReplace = {}
                )
            }
        }
    }

    if (isFeedbackDialogOpen) {
        Dialog(
            onDismissRequest = { isFeedbackDialogOpen = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedCard(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    RouteFeedbackCard(
                        feedback = routeFeedback,
                        onFeedbackChange = { feedback -> onPlannerEvent(PlannerEvent.UpdateFeedback(feedback)) },
                        framed = false
                    )
                }
                Button(
                    onClick = { isFeedbackDialogOpen = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }

    if (isMapFullScreen) {
        Dialog(
            onDismissRequest = {
                isMapFullScreen = false
                if (isSelectingStart) {
                    isSelectingStart = false
                }
                if (isSelectingRequiredPlacesOnMap) {
                    isSelectingRequiredPlacesOnMap = false
                }
            },
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
                    onStartPointSelected = ::updateStartPoint,
                    onRoutePoiSelected = { poi -> onPlannerEvent(PlannerEvent.ToggleRequiredPoi(poi.id)) },
                    modifier = Modifier.fillMaxSize()
                )
                OutlinedButton(
                    onClick = {
                        isMapFullScreen = false
                        if (isSelectingStart) {
                            isSelectingStart = false
                        }
                        if (isSelectingRequiredPlacesOnMap) {
                            isSelectingRequiredPlacesOnMap = false
                        }
                    },
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
