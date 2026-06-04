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
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.TrackingPermissionAction
import com.example.smarttourism.features.planner.viewmodel.RoutePlannerViewModel
import com.example.smarttourism.features.planner.viewmodel.fetchCurrentLocation
import com.example.smarttourism.features.planner.viewmodel.startRouteLocationTracking

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RoutePlannerScreen(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val plannerViewModel: RoutePlannerViewModel = viewModel()
    val locationPermissionDeniedMessage = stringResource(R.string.error_location_permission_denied)

    val cities = plannerViewModel.cities
    val selectedCity = plannerViewModel.selectedCity
    val pois = plannerViewModel.pois
    val isPoiLoading = plannerViewModel.isPoiLoading
    val poiError = plannerViewModel.poiError
    val offlineStatusMessage = plannerViewModel.offlineStatusMessage
    val pendingSyncOperationCount = plannerViewModel.pendingSyncOperationCount
    val offlineStoredRegion = plannerViewModel.offlineStoredRegion
    val isOfflineMapBusy = plannerViewModel.isOfflineMapBusy
    val offlineMapProgress = plannerViewModel.offlineMapProgress
    val offlineMapMessage = plannerViewModel.offlineMapMessage
    val routeResponse = plannerViewModel.routeResponse
    val routeBookmarks = plannerViewModel.routeBookmarks
    val routeHistory = plannerViewModel.routeHistory
    val isRouteLoading = plannerViewModel.isRouteLoading
    val isRouteHistoryLoading = plannerViewModel.isRouteHistoryLoading
    val routeError = plannerViewModel.routeError
    val routeHistoryError = plannerViewModel.routeHistoryError
    val isRerouting = plannerViewModel.isRerouting
    val availableMinutes = plannerViewModel.availableMinutes
    val maxAvailableMinutesLimit = plannerViewModel.maxAvailableMinutesLimit
    val pace = plannerViewModel.pace
    val returnToStart = plannerViewModel.returnToStart
    val respectOpeningHours = plannerViewModel.respectOpeningHours
    val allowPublicTransport = plannerViewModel.allowPublicTransport
    val startPoint = plannerViewModel.startPoint
    val startDateTime = plannerViewModel.startDateTime
    val routeSessionStatus = plannerViewModel.routeSessionStatus
    val routeId = plannerViewModel.routeId
    val routeStartedAt = plannerViewModel.routeStartedAt
    val currentRouteLocation = plannerViewModel.currentRouteLocation
    val trackingError = plannerViewModel.trackingError
    val routeFeedback = plannerViewModel.routeFeedback
    val selectedInterests = plannerViewModel.selectedInterests
    val requiredPoiIds = plannerViewModel.requiredPoiIds
    val visitedPoiIds = plannerViewModel.visitedPoiIds
    val skippedPoiIds = plannerViewModel.skippedPoiIds
    val routeItems = plannerViewModel.routeItems
    val hasPendingRouteChanges = plannerViewModel.hasPendingRouteChanges
    val hasNoGeneratedStops = plannerViewModel.hasNoGeneratedStops
    val progressMetrics = plannerViewModel.progressMetrics
    val selectedCityAvailableCategories = plannerViewModel.selectedCityAvailableCategories
    val isPublicTransportAvailable = plannerViewModel.isPublicTransportAvailable
    val activeBookmarkId = plannerViewModel.activeBookmarkId
    val isCurrentRouteBookmarked = plannerViewModel.isCurrentRouteBookmarked
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
        plannerViewModel.initialize()
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
            plannerViewModel.loadRouteHistory(forceRefresh = true)
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
        plannerViewModel.updateStartPoint(lat, lon)
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
                TrackingPermissionAction.START -> plannerViewModel.activateRouteTracking()
                TrackingPermissionAction.RESUME -> plannerViewModel.resumeRoute()
            }
        } else {
            plannerViewModel.handleTrackingError(locationPermissionDeniedMessage)
        }
    }

    DisposableEffect(context, routeItems, plannerViewModel.routeSessionStatus) {
        if (plannerViewModel.routeSessionStatus != RouteSessionStatus.IN_PROGRESS) {
            onDispose { }
        } else {
            val stopTracking = startRouteLocationTracking(
                context = context,
                onLocation = { location ->
                    plannerViewModel.handleTrackedLocation(
                        RoutePoint(
                            lat = location.latitude,
                            lon = location.longitude
                        )
                    )
                },
                onError = { message ->
                    plannerViewModel.handleTrackingError(message)
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
            plannerViewModel.activateRouteTracking()
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
            plannerViewModel.resumeRoute()
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
        plannerViewModel.clearDisplayedRoute(cancelActiveSession = false)
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
                        plannerViewModel.markRouteStopVisited(target.poiId)
                    }
                },
                onSkip = {
                    progressMetrics.nextTarget?.let { target ->
                        plannerViewModel.skipRouteStop(target.poiId)
                    }
                },
                onPauseRoute = { plannerViewModel.pauseRoute() },
                onResumeRoute = ::resumeRoute,
                onFinishRoute = { plannerViewModel.finishRoute() },
                onCancelRoute = { plannerViewModel.cancelRoute() },
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
                        plannerViewModel.selectCity(city)
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
                            onMovePoi = { poiId, direction -> plannerViewModel.moveRequiredPoi(poiId, direction) },
                            onRemovePoi = { poiId -> plannerViewModel.removeRequiredPoi(poiId) },
                            onClearAll = { plannerViewModel.clearRequiredPois() }
                        )
                    }

                    item {
                        RouteParametersCard(
                            availableMinutes = availableMinutes,
                            maxAvailableMinutes = maxAvailableMinutesLimit,
                            onAvailableMinutesChange = { plannerViewModel.updateAvailableMinutes(it) },
                            availableInterests = selectedCityAvailableCategories,
                            selectedInterests = selectedInterests,
                            onInterestToggle = { interest, checked ->
                                plannerViewModel.toggleInterest(interest, checked)
                            },
                            pace = pace,
                            onPaceChange = { plannerViewModel.updatePace(it) },
                            returnToStart = returnToStart,
                            onReturnToStartChange = { plannerViewModel.updateReturnToStart(it) },
                            respectOpeningHours = respectOpeningHours,
                            onRespectOpeningHoursChange = { plannerViewModel.updateRespectOpeningHours(it) },
                            isPublicTransportAvailable = isPublicTransportAvailable,
                            allowPublicTransport = allowPublicTransport,
                            onAllowPublicTransportChange = { plannerViewModel.updateAllowPublicTransport(it) },
                            startDateTime = startDateTime,
                            onStartDateTimeChange = { plannerViewModel.updateStartDateTime(it) },
                            onUseCurrentTime = { plannerViewModel.useCurrentTime() },
                            isEditingEnabled = !isPlannerEditingLocked,
                            isGenerating = isRouteLoading,
                            onGenerateRoute = { plannerViewModel.generateRoute() }
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
                            onClick = { plannerViewModel.saveCurrentRouteBookmark() },
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
                        onMarkVisited = { poiId -> plannerViewModel.markRouteStopVisited(poiId) },
                        onSkip = { poiId -> plannerViewModel.removePreviewStop(poiId) },
                        onMove = { poiId, direction -> plannerViewModel.movePreviewStop(poiId, direction) },
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
                            onClick = { plannerViewModel.saveCurrentRouteBookmark() },
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
                                plannerViewModel.openRouteBookmark(bookmarkId)
                                currentDestination = PlannerDestination.PLANNER
                            },
                            onDeleteBookmark = { bookmarkId ->
                                plannerViewModel.deleteRouteBookmark(bookmarkId)
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
                        onRefresh = { plannerViewModel.loadRouteHistory(forceRefresh = true) },
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
                            onDownloadOfflineMap = { plannerViewModel.downloadOfflineMap() },
                            onDeleteOfflineMap = { plannerViewModel.deleteOfflineMap() }
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
                        onMovePoi = { poiId, direction -> plannerViewModel.moveRequiredPoi(poiId, direction) },
                        onRemovePoi = { poiId -> plannerViewModel.removeRequiredPoi(poiId) },
                        onClearAll = { plannerViewModel.clearRequiredPois() }
                    )
                }

                item {
                    RouteParametersCard(
                        availableMinutes = availableMinutes,
                        maxAvailableMinutes = maxAvailableMinutesLimit,
                        onAvailableMinutesChange = { plannerViewModel.updateAvailableMinutes(it) },
                        availableInterests = selectedCityAvailableCategories,
                        selectedInterests = selectedInterests,
                        onInterestToggle = { interest, checked ->
                            plannerViewModel.toggleInterest(interest, checked)
                        },
                        pace = pace,
                        onPaceChange = { plannerViewModel.updatePace(it) },
                        returnToStart = returnToStart,
                        onReturnToStartChange = { plannerViewModel.updateReturnToStart(it) },
                        respectOpeningHours = respectOpeningHours,
                        onRespectOpeningHoursChange = { plannerViewModel.updateRespectOpeningHours(it) },
                        isPublicTransportAvailable = isPublicTransportAvailable,
                        allowPublicTransport = allowPublicTransport,
                        onAllowPublicTransportChange = { plannerViewModel.updateAllowPublicTransport(it) },
                        startDateTime = startDateTime,
                        onStartDateTimeChange = { plannerViewModel.updateStartDateTime(it) },
                        onUseCurrentTime = { plannerViewModel.useCurrentTime() },
                        isEditingEnabled = !isPlannerEditingLocked,
                        isGenerating = isRouteLoading,
                        onGenerateRoute = {
                            plannerViewModel.generateRoute()
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
                    onTogglePoi = { poiId -> plannerViewModel.toggleRequiredPoi(poiId) }
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
                            plannerViewModel.replacePreviewStop(targetPoiId)
                            replacingPoiId = null
                        },
                        onChooseCandidate = { preferredPoiId ->
                            plannerViewModel.replacePreviewStop(targetPoiId, preferredPoiId)
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
                    onMarkVisited = { poiId -> plannerViewModel.markRouteStopVisited(poiId) },
                    onSkip = { poiId -> plannerViewModel.skipRouteStop(poiId) },
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
                        onFeedbackChange = { feedback -> plannerViewModel.updateFeedback(feedback) },
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
                    onRoutePoiSelected = { poi -> plannerViewModel.toggleRequiredPoi(poi.id) },
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
