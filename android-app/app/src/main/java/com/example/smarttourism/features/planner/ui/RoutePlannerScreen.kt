package com.example.smarttourism.features.planner.ui

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarttourism.R
import com.example.smarttourism.core.i18n.AppLanguage
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.components.CuratedRoutesSheetContent
import com.example.smarttourism.features.planner.components.OfflineSupportCard
import com.example.smarttourism.features.planner.components.RouteBookmarksSheetContent
import com.example.smarttourism.features.planner.components.RouteHistorySheetContent
import com.example.smarttourism.features.planner.state.PlannerMode
import com.example.smarttourism.features.planner.state.PlannerEvent
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.TrackingPermissionAction
import com.example.smarttourism.features.planner.viewmodel.RoutePlannerViewModel
import com.example.smarttourism.features.planner.location.fetchCurrentLocation
import com.example.smarttourism.features.planner.location.startRouteLocationTracking

@Composable
fun RoutePlannerScreen(
    selectedLanguage: AppLanguage,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
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
    val curatedRoutes = uiState.curatedRoutes
    val isCuratedRoutesLoading = uiState.isCuratedRoutesLoading
    val curatedRoutesError = uiState.curatedRoutesError
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
    var selectedMapPoiForAction by remember { mutableStateOf<Poi?>(null) }
    var replacingPoiId by remember { mutableStateOf<Int?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var dismissedPlannerErrorDialogKey by remember { mutableStateOf<String?>(null) }
    var locationRequestTarget by remember { mutableStateOf(LocationRequestTarget.START_POINT) }
    var mapCurrentLocation by remember { mutableStateOf<RoutePoint?>(null) }
    var mapRecenterRequest by remember { mutableIntStateOf(0) }
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

    DisposableEffect(plannerMode, view) {
        val controller = context.findActivity()?.window
            ?.let { window -> WindowCompat.getInsetsController(window, view) }
        controller?.let { insetsController ->
            if (plannerMode == PlannerMode.ACTIVE) {
                insetsController.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                insetsController.hide(WindowInsetsCompat.Type.navigationBars())
            } else {
                insetsController.show(WindowInsetsCompat.Type.navigationBars())
            }
        }
        onDispose {
            controller?.show(WindowInsetsCompat.Type.navigationBars())
        }
    }

    LaunchedEffect(currentDestination) {
        if (currentDestination == PlannerDestination.HISTORY) {
            onPlannerEvent(PlannerEvent.LoadRouteHistory(forceRefresh = true))
        } else {
            selectedHistoryEntry = null
        }
    }

    LaunchedEffect(currentDestination, selectedCity?.slug) {
        if (currentDestination == PlannerDestination.CURATED && selectedCity != null) {
            onPlannerEvent(PlannerEvent.LoadCuratedRoutes)
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
        selectedMapPoiForAction = null
        locationError = null
    }

    fun openRequiredPlacesMapPicker() {
        isSelectingStart = false
        isSelectingRequiredPlacesOnMap = true
        isMapFullScreen = true
        selectedMapPoiForAction = null
        locationError = null
    }

    fun selectedRequiredPois(): List<Poi> {
        val poisById = pois.associateBy { poi -> poi.id }
        return requiredPoiIds.mapNotNull { poiId -> poisById[poiId] }
    }

    fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

    fun requestCurrentDeviceLocation() {
        isLocating = true
        locationError = null
        fetchCurrentLocation(
            context = context,
            onSuccess = { lat, lon ->
                isLocating = false
                mapCurrentLocation = RoutePoint(lat = lat, lon = lon)
                mapRecenterRequest += 1
                updateStartPoint(lat, lon)
            },
            onError = { message ->
                isLocating = false
                locationError = message
            }
        )
    }

    fun requestMapCurrentLocation() {
        isLocating = true
        locationError = null
        fetchCurrentLocation(
            context = context,
            onSuccess = { lat, lon ->
                isLocating = false
                mapCurrentLocation = RoutePoint(lat = lat, lon = lon)
                mapRecenterRequest += 1
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
            when (locationRequestTarget) {
                LocationRequestTarget.START_POINT -> requestCurrentDeviceLocation()
                LocationRequestTarget.MAP_CAMERA -> requestMapCurrentLocation()
            }
        } else {
            isLocating = false
            locationError = locationPermissionDeniedMessage
        }
    }

    fun requestLocationWithPermission(target: LocationRequestTarget) {
        locationRequestTarget = target
        if (hasFineLocationPermission()) {
            when (target) {
                LocationRequestTarget.START_POINT -> requestCurrentDeviceLocation()
                LocationRequestTarget.MAP_CAMERA -> requestMapCurrentLocation()
            }
        } else {
            isLocating = true
            locationError = null
            locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
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

        if (hasFineLocationPermission()) {
            onPlannerEvent(PlannerEvent.ActivateRouteTracking)
        } else {
            trackingPermissionAction = TrackingPermissionAction.START
            trackingPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun resumeRoute() {
        isSelectingStart = false

        if (hasFineLocationPermission()) {
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
        selectedMapPoiForAction = null
        onPlannerEvent(PlannerEvent.ClearDisplayedRoute(cancelActiveSession = false))
    }

    val passiveMapLocation = currentRouteLocation ?: mapCurrentLocation

    val plannerAlerts = PlannerAlerts(
        locationError = locationError,
        poiError = poiError,
        routeError = routeError,
        trackingError = trackingError,
        hasNoGeneratedStops = hasNoGeneratedStops,
        hasPendingRouteChanges = hasPendingRouteChanges && routeResponse != null
    )
    val currentLocationError = locationError
    val plannerErrorDialog = when {
        routeError != null -> PlannerErrorDialogState(
            key = "route:$routeError:$allowPublicTransport",
            title = stringResource(R.string.status_route_generation_failed),
            body = routeError,
            canRetryRoute = true,
            canFallbackToWalking = allowPublicTransport
        )
        hasNoGeneratedStops -> PlannerErrorDialogState(
            key = "empty-route:$allowPublicTransport",
            title = stringResource(R.string.status_no_stops_title),
            body = stringResource(R.string.status_no_stops_body),
            canRetryRoute = true,
            canFallbackToWalking = allowPublicTransport
        )
        poiError != null -> PlannerErrorDialogState(
            key = "poi:$poiError",
            title = stringResource(R.string.status_poi_preview_unavailable),
            body = poiError,
            canRetryRoute = false,
            canFallbackToWalking = false
        )
        currentLocationError != null -> PlannerErrorDialogState(
            key = "location:$currentLocationError",
            title = stringResource(R.string.status_location_unavailable),
            body = currentLocationError,
            canRetryRoute = false,
            canFallbackToWalking = false
        )
        trackingError != null -> PlannerErrorDialogState(
            key = "tracking:$trackingError",
            title = stringResource(R.string.status_gps_tracking_unavailable),
            body = trackingError,
            canRetryRoute = false,
            canFallbackToWalking = false
        )
        else -> null
    }
    val visiblePlannerErrorDialog = plannerErrorDialog
        ?.takeUnless { dialog -> dialog.key == dismissedPlannerErrorDialogKey }

    LaunchedEffect(plannerErrorDialog?.key, isRouteLoading) {
        if (plannerErrorDialog == null || isRouteLoading) {
            dismissedPlannerErrorDialogKey = null
        }
    }

    if (plannerMode == PlannerMode.ACTIVE) {
        ActiveRouteContent(
            pois = pois,
            routeResponse = routeResponse,
            startPoint = startPoint,
            selectedCity = selectedCity,
            currentRouteLocation = currentRouteLocation,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            requiredPoiIds = requiredPoiIds,
            routeSessionStatus = routeSessionStatus,
            isPoiLoading = isPoiLoading,
            plannerAlerts = plannerAlerts,
            progressMetrics = progressMetrics,
            isRerouting = isRerouting,
            canSkipStops = canSkipStops,
            activeMapRecenterRequest = activeMapRecenterRequest,
            activeRoutePanelHeightPx = activeRoutePanelHeightPx,
            onActiveMapRecenterRequest = { activeMapRecenterRequest += 1 },
            onActiveRoutePanelHeightChange = { height -> activeRoutePanelHeightPx = height },
            onStartPointSelected = ::updateStartPoint,
            onOpenFullScreenMap = ::openFullScreenMap,
            onMarkVisited = { poiId -> onPlannerEvent(PlannerEvent.MarkRouteStopVisited(poiId)) },
            onSkip = { poiId -> onPlannerEvent(PlannerEvent.SkipRouteStop(poiId)) },
            onPauseRoute = { onPlannerEvent(PlannerEvent.PauseRoute) },
            onResumeRoute = ::resumeRoute,
            onFinishRoute = { onPlannerEvent(PlannerEvent.FinishRoute) },
            onCancelRoute = { onPlannerEvent(PlannerEvent.CancelRoute) },
            onShowAllStops = { isStopsSheetOpen = true }
        )
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
                PlannerDestination.PLANNER -> when (plannerMode) {
                    PlannerMode.PLANNING -> PlanningScreenContent(
                        selectedCity = selectedCity,
                        pois = pois,
                        routeResponse = routeResponse,
                        startPoint = startPoint,
                        currentRouteLocation = passiveMapLocation,
                        visitedPoiIds = visitedPoiIds,
                        skippedPoiIds = skippedPoiIds,
                        requiredPoiIds = requiredPoiIds,
                        selectedRequiredPois = selectedRequiredPois(),
                        isPoiLoading = isPoiLoading,
                        isSelectingStart = isSelectingStart,
                        isLocating = isLocating,
                        isPlannerEditingLocked = isPlannerEditingLocked,
                        plannerAlerts = plannerAlerts,
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
                        mapRecenterRequestKey = mapRecenterRequest,
                        onStartPointSelected = ::updateStartPoint,
                        onCurrentLocationRequested = {
                            requestLocationWithPermission(LocationRequestTarget.MAP_CAMERA)
                        },
                        onOpenFullScreenMap = ::openFullScreenMap,
                        onToggleStartSelection = {
                            val isEnteringSelection = !isSelectingStart
                            isSelectingStart = isEnteringSelection
                            if (isEnteringSelection) {
                                openFullScreenMap()
                            }
                            locationError = null
                        },
                        onUseCurrentLocation = {
                            isSelectingStart = false
                            requestLocationWithPermission(LocationRequestTarget.START_POINT)
                        },
                        onChooseRequiredOnMap = ::openRequiredPlacesMapPicker,
                        onChooseRequiredFromList = { isRequiredPlacesSheetOpen = true },
                        onMoveRequiredPoi = { poiId, direction -> onPlannerEvent(PlannerEvent.MoveRequiredPoi(poiId, direction)) },
                        onRemoveRequiredPoi = { poiId -> onPlannerEvent(PlannerEvent.RemoveRequiredPoi(poiId)) },
                        onClearRequiredPois = { onPlannerEvent(PlannerEvent.ClearRequiredPois) },
                        onAvailableMinutesChange = { onPlannerEvent(PlannerEvent.UpdateAvailableMinutes(it)) },
                        onInterestToggle = { interest, checked -> onPlannerEvent(PlannerEvent.ToggleInterest(interest, checked)) },
                        onPaceChange = { onPlannerEvent(PlannerEvent.UpdatePace(it)) },
                        onReturnToStartChange = { onPlannerEvent(PlannerEvent.UpdateReturnToStart(it)) },
                        onRespectOpeningHoursChange = { onPlannerEvent(PlannerEvent.UpdateRespectOpeningHours(it)) },
                        onAllowPublicTransportChange = { onPlannerEvent(PlannerEvent.UpdateAllowPublicTransport(it)) },
                        onStartDateTimeChange = { onPlannerEvent(PlannerEvent.UpdateStartDateTime(it)) },
                        onUseCurrentTime = { onPlannerEvent(PlannerEvent.UseCurrentTime) },
                        onGenerateRoute = { onPlannerEvent(PlannerEvent.GenerateRoute) }
                    )

                    PlannerMode.PREVIEW -> PreviewScreenContent(
                        selectedCity = selectedCity,
                        pois = pois,
                        routeResponse = routeResponse,
                        startPoint = startPoint,
                        currentRouteLocation = passiveMapLocation,
                        visitedPoiIds = visitedPoiIds,
                        skippedPoiIds = skippedPoiIds,
                        requiredPoiIds = requiredPoiIds,
                        isPoiLoading = isPoiLoading,
                        plannerAlerts = plannerAlerts,
                        hasPendingRouteChanges = hasPendingRouteChanges,
                        routeItems = routeItems,
                        canSkipStops = canSkipStops,
                        isRerouting = isRerouting,
                        highlightedPoiId = progressMetrics.nextTarget?.poiId,
                        isCurrentRouteBookmarked = isCurrentRouteBookmarked,
                        mapRecenterRequestKey = mapRecenterRequest,
                        onStartPointSelected = ::updateStartPoint,
                        onCurrentLocationRequested = {
                            requestLocationWithPermission(LocationRequestTarget.MAP_CAMERA)
                        },
                        onOpenFullScreenMap = ::openFullScreenMap,
                        onStartRoute = ::startRoute,
                        onEditParameters = { isParameterSheetOpen = true },
                        onPlanAnotherRoute = ::resetToPlanning,
                        onSaveBookmark = { onPlannerEvent(PlannerEvent.SaveCurrentRouteBookmark) },
                        onMarkVisited = { poiId -> onPlannerEvent(PlannerEvent.MarkRouteStopVisited(poiId)) },
                        onRemovePreviewStop = { poiId -> onPlannerEvent(PlannerEvent.RemovePreviewStop(poiId)) },
                        onMovePreviewStop = { poiId, direction -> onPlannerEvent(PlannerEvent.MovePreviewStop(poiId, direction)) },
                        onReplacePreviewStop = { poiId -> replacingPoiId = poiId }
                    )

                    PlannerMode.COMPLETED -> CompletedRouteContent(
                        selectedCity = selectedCity,
                        pois = pois,
                        routeResponse = routeResponse,
                        startPoint = startPoint,
                        currentRouteLocation = passiveMapLocation,
                        visitedPoiIds = visitedPoiIds,
                        skippedPoiIds = skippedPoiIds,
                        requiredPoiIds = requiredPoiIds,
                        isPoiLoading = isPoiLoading,
                        plannerAlerts = plannerAlerts,
                        routeItems = routeItems,
                        isCurrentRouteBookmarked = isCurrentRouteBookmarked,
                        hasFeedback = routeFeedback != null,
                        mapRecenterRequestKey = mapRecenterRequest,
                        onStartPointSelected = ::updateStartPoint,
                        onCurrentLocationRequested = {
                            requestLocationWithPermission(LocationRequestTarget.MAP_CAMERA)
                        },
                        onOpenFullScreenMap = ::openFullScreenMap,
                        onPlanAnotherRoute = ::resetToPlanning,
                        onSaveBookmark = { onPlannerEvent(PlannerEvent.SaveCurrentRouteBookmark) },
                        onOpenFeedback = { isFeedbackDialogOpen = true }
                    )

                    PlannerMode.ACTIVE -> Unit
                }

                PlannerDestination.CURATED -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    item {
                        CuratedRoutesSheetContent(
                            cityName = selectedCity?.name,
                            curatedRoutes = curatedRoutes,
                            isLoading = isCuratedRoutesLoading,
                            errorMessage = curatedRoutesError,
                            onOpenCuratedRoute = { routeId ->
                                onPlannerEvent(PlannerEvent.OpenCuratedRoute(routeId))
                                currentDestination = PlannerDestination.PLANNER
                            }
                        )
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

    val plannerScreenState = PlannerScreenState(
        isParameterSheetOpen = isParameterSheetOpen,
        isRequiredPlacesSheetOpen = isRequiredPlacesSheetOpen,
        isStopsSheetOpen = isStopsSheetOpen,
        replacingPoiId = replacingPoiId,
        selectedHistoryEntry = selectedHistoryEntry,
        selectedMapPoiForAction = selectedMapPoiForAction,
        isFeedbackDialogOpen = isFeedbackDialogOpen,
        isMapFullScreen = isMapFullScreen,
        pois = pois,
        routeResponse = routeResponse,
        routeItems = routeItems,
        startPoint = startPoint,
        selectedCity = selectedCity,
        currentRouteLocation = passiveMapLocation,
        isSelectingStart = isSelectingStart,
        isSelectingRequiredPlacesOnMap = isSelectingRequiredPlacesOnMap,
        isLocating = isLocating,
        isPlannerEditingLocked = isPlannerEditingLocked,
        selectedRequiredPois = selectedRequiredPois(),
        requiredPoiIds = requiredPoiIds,
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
        routeSessionStatus = routeSessionStatus,
        visitedPoiIds = visitedPoiIds,
        skippedPoiIds = skippedPoiIds,
        canSkipStops = canSkipStops,
        isRerouting = isRerouting,
        highlightedPoiId = progressMetrics.nextTarget?.poiId,
        routeFeedback = routeFeedback,
        isPoiLoading = isPoiLoading,
        mapRecenterRequestKey = mapRecenterRequest,
        errorDialog = visiblePlannerErrorDialog
    )

    val plannerActions = PlannerActions(
        onDismissParameterSheet = { isParameterSheetOpen = false },
        onDismissRequiredPlacesSheet = { isRequiredPlacesSheetOpen = false },
        onDismissStopsSheet = { isStopsSheetOpen = false },
        onDismissReplacementSheet = { replacingPoiId = null },
        onToggleStartSelection = {
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
            requestLocationWithPermission(LocationRequestTarget.START_POINT)
        },
        onChooseRequiredOnMap = ::openRequiredPlacesMapPicker,
        onChooseRequiredFromList = { isRequiredPlacesSheetOpen = true },
        onMoveRequiredPoi = { poiId, direction -> onPlannerEvent(PlannerEvent.MoveRequiredPoi(poiId, direction)) },
        onRemoveRequiredPoi = { poiId -> onPlannerEvent(PlannerEvent.RemoveRequiredPoi(poiId)) },
        onClearRequiredPois = { onPlannerEvent(PlannerEvent.ClearRequiredPois) },
        onAvailableMinutesChange = { onPlannerEvent(PlannerEvent.UpdateAvailableMinutes(it)) },
        onInterestToggle = { interest, checked -> onPlannerEvent(PlannerEvent.ToggleInterest(interest, checked)) },
        onPaceChange = { onPlannerEvent(PlannerEvent.UpdatePace(it)) },
        onReturnToStartChange = { onPlannerEvent(PlannerEvent.UpdateReturnToStart(it)) },
        onRespectOpeningHoursChange = { onPlannerEvent(PlannerEvent.UpdateRespectOpeningHours(it)) },
        onAllowPublicTransportChange = { onPlannerEvent(PlannerEvent.UpdateAllowPublicTransport(it)) },
        onStartDateTimeChange = { onPlannerEvent(PlannerEvent.UpdateStartDateTime(it)) },
        onUseCurrentTime = { onPlannerEvent(PlannerEvent.UseCurrentTime) },
        onGenerateRoute = {
            onPlannerEvent(PlannerEvent.GenerateRoute)
            isParameterSheetOpen = false
        },
        onToggleRequiredPoi = { poiId -> onPlannerEvent(PlannerEvent.ToggleRequiredPoi(poiId)) },
        onUseBestReplacement = { targetPoiId ->
            onPlannerEvent(PlannerEvent.ReplacePreviewStop(targetPoiId))
            replacingPoiId = null
        },
        onChooseReplacementCandidate = { targetPoiId, preferredPoiId ->
            onPlannerEvent(PlannerEvent.ReplacePreviewStop(targetPoiId, preferredPoiId))
            replacingPoiId = null
        },
        onMarkVisited = { poiId -> onPlannerEvent(PlannerEvent.MarkRouteStopVisited(poiId)) },
        onSkip = { poiId -> onPlannerEvent(PlannerEvent.SkipRouteStop(poiId)) },
        onDismissHistoryEntry = { selectedHistoryEntry = null },
        onDismissErrorDialog = {
            dismissedPlannerErrorDialogKey = plannerErrorDialog?.key
        },
        onRetryRouteGeneration = {
            dismissedPlannerErrorDialogKey = null
            onPlannerEvent(PlannerEvent.GenerateRoute)
        },
        onFallbackToWalking = {
            dismissedPlannerErrorDialogKey = null
            onPlannerEvent(PlannerEvent.UpdateAllowPublicTransport(false))
            onPlannerEvent(PlannerEvent.GenerateRoute)
        },
        onDismissFeedback = { isFeedbackDialogOpen = false },
        onFeedbackChange = { feedback -> onPlannerEvent(PlannerEvent.UpdateFeedback(feedback)) },
        onDismissFullScreenMap = {
            isMapFullScreen = false
            selectedMapPoiForAction = null
            if (isSelectingStart) {
                isSelectingStart = false
            }
            if (isSelectingRequiredPlacesOnMap) {
                isSelectingRequiredPlacesOnMap = false
            }
        },
        onDismissMapPoiAction = { selectedMapPoiForAction = null },
        onConfirmMapPoiToggle = { poi ->
            onPlannerEvent(PlannerEvent.ToggleRequiredPoi(poi.id))
            selectedMapPoiForAction = null
        },
        onCurrentLocationRequested = {
            requestLocationWithPermission(LocationRequestTarget.MAP_CAMERA)
        },
        onStartPointSelected = ::updateStartPoint,
        onRoutePoiSelected = { poi -> selectedMapPoiForAction = poi }
    )

    PlannerSheets(
        state = plannerScreenState,
        actions = plannerActions,
        replacementCandidatesForPoi = plannerViewModel::previewReplacementCandidates,
    )

    PlannerDialogs(
        state = plannerScreenState,
        actions = plannerActions
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class LocationRequestTarget {
    START_POINT,
    MAP_CAMERA
}
