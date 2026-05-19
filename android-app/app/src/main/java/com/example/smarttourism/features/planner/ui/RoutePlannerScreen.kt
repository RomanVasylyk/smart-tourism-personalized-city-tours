package com.example.smarttourism.features.planner

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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
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
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.RouteItemDto
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteStartDto
import com.example.smarttourism.features.map.PoiMapScreen

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
    var isLocating by remember { mutableStateOf(false) }
    var isMapFullScreen by remember { mutableStateOf(false) }
    var isParameterSheetOpen by remember { mutableStateOf(false) }
    var isStopsSheetOpen by remember { mutableStateOf(false) }
    var isFeedbackDialogOpen by remember { mutableStateOf(false) }
    var currentDestination by remember { mutableStateOf(PlannerDestination.PLANNER) }
    var selectedHistoryEntry by remember { mutableStateOf<RouteHistoryEntry?>(null) }
    var replacingPoiId by remember { mutableStateOf<Int?>(null) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var trackingPermissionAction by remember { mutableStateOf(TrackingPermissionAction.START) }
    var autoOpenedFeedbackToken by remember { mutableStateOf<String?>(null) }

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
        isMapFullScreen = false
        locationError = null
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
                        RouteStartDto(
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
        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            PlannerMapPanel(
                pois = pois,
                routeResponse = routeResponse,
                startPoint = startPoint,
                defaultZoom = selectedCity?.default_zoom,
                currentRouteLocation = currentRouteLocation,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = skippedPoiIds,
                isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                isPoiLoading = isPoiLoading,
                isSelectingStart = false,
                onStartPointSelected = ::updateStartPoint,
                onOpenFullScreenMap = ::openFullScreenMap,
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
                    .firstOrNull { leg -> leg.to.poi_id == progressMetrics.nextTarget?.poi_id },
                onMarkVisited = {
                    progressMetrics.nextTarget?.let { target ->
                        plannerViewModel.markRouteStopVisited(target.poi_id)
                    }
                },
                onSkip = {
                    progressMetrics.nextTarget?.let { target ->
                        plannerViewModel.skipRouteStop(target.poi_id)
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
                            defaultZoom = selectedCity?.default_zoom,
                            currentRouteLocation = currentRouteLocation,
                            visitedPoiIds = visitedPoiIds,
                            skippedPoiIds = skippedPoiIds,
                            isRouteActive = false,
                            isPoiLoading = isPoiLoading,
                            isSelectingStart = isSelectingStart,
                            onStartPointSelected = ::updateStartPoint,
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
                            defaultZoom = selectedCity?.default_zoom,
                            currentRouteLocation = currentRouteLocation,
                            visitedPoiIds = visitedPoiIds,
                            skippedPoiIds = skippedPoiIds,
                            isRouteActive = false,
                            isPoiLoading = isPoiLoading,
                            isSelectingStart = false,
                            onStartPointSelected = ::updateStartPoint,
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
                        isActionInProgress = isRerouting,
                        highlightedPoiId = progressMetrics.nextTarget?.poi_id,
                        onMarkVisited = { poiId -> plannerViewModel.markRouteStopVisited(poiId) },
                        onSkip = { poiId -> plannerViewModel.removePreviewStop(poiId) },
                        onReplace = { poiId -> replacingPoiId = poiId }
                    )
                }

                PlannerMode.COMPLETED -> {
                    item {
                        PlannerMapPanel(
                            pois = pois,
                            routeResponse = routeResponse,
                            startPoint = startPoint,
                            defaultZoom = selectedCity?.default_zoom,
                            currentRouteLocation = currentRouteLocation,
                            visitedPoiIds = visitedPoiIds,
                            skippedPoiIds = skippedPoiIds,
                            isRouteActive = false,
                            isPoiLoading = isPoiLoading,
                            isSelectingStart = false,
                            onStartPointSelected = ::updateStartPoint,
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
                        isActionInProgress = false,
                        highlightedPoiId = null,
                        onMarkVisited = {},
                        onSkip = {},
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

    selectedHistoryEntry?.let { historyEntry ->
        RouteHistoryDetailsDialog(
            entry = historyEntry,
            onDismiss = { selectedHistoryEntry = null }
        )
    }

    replacingPoiId?.let { targetPoiId ->
        val targetStop = routeItems.firstOrNull { item -> item.poi_id == targetPoiId }
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
                    isActionInProgress = isRerouting,
                    highlightedPoiId = progressMetrics.nextTarget?.poi_id,
                    onMarkVisited = { poiId -> plannerViewModel.markRouteStopVisited(poiId) },
                    onSkip = { poiId -> plannerViewModel.skipRouteStop(poiId) },
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
                    defaultZoom = selectedCity?.default_zoom,
                    currentLocation = currentRouteLocation,
                    visitedPoiIds = visitedPoiIds.toSet(),
                    skippedPoiIds = skippedPoiIds.toSet(),
                    isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                    isLoading = isPoiLoading,
                    isFullScreen = true,
                    isSelectingStart = isSelectingStart,
                    onStartPointSelected = ::updateStartPoint,
                    modifier = Modifier.fillMaxSize()
                )
                OutlinedButton(
                    onClick = {
                        isMapFullScreen = false
                        if (isSelectingStart) {
                            isSelectingStart = false
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

private enum class PlannerDestination {
    PLANNER,
    SAVED_ROUTES,
    HISTORY,
    OFFLINE
}

@Composable
private fun PlannerTopBar(
    currentDestination: PlannerDestination,
    selectedLanguage: AppLanguage,
    cities: List<CityDto>,
    selectedCity: CityDto?,
    routeBookmarkCount: Int,
    isCitySelectionEnabled: Boolean,
    onDestinationSelected: (PlannerDestination) -> Unit,
    onCitySelected: (CityDto) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    var isAppMenuOpen by remember { mutableStateOf(false) }
    var isCityMenuOpen by remember { mutableStateOf(false) }
    var isLanguageMenuOpen by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        val cityLabel = selectedCity?.name ?: stringResource(
            if (cities.isEmpty()) {
                R.string.city_selector_empty
            } else {
                R.string.app_city_select
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = destinationLabel(currentDestination, routeBookmarkCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                TextButton(
                    onClick = { isCityMenuOpen = true },
                    enabled = isCitySelectionEnabled && cities.isNotEmpty()
                ) {
                    Text(cityLabel)
                }
                DropdownMenu(
                    expanded = isCityMenuOpen,
                    onDismissRequest = { isCityMenuOpen = false }
                ) {
                    cities.forEach { city ->
                        DropdownMenuItem(
                            text = { Text(city.name) },
                            enabled = selectedCity?.slug != city.slug,
                            onClick = {
                                isCityMenuOpen = false
                                onCitySelected(city)
                            }
                        )
                    }
                }
            }

            Box {
                TextButton(onClick = { isLanguageMenuOpen = true }) {
                    Text(languageShortLabel(selectedLanguage))
                }
                DropdownMenu(
                    expanded = isLanguageMenuOpen,
                    onDismissRequest = { isLanguageMenuOpen = false }
                ) {
                    AppLanguage.entries.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(languageLabel(language)) },
                            enabled = selectedLanguage != language,
                            onClick = {
                                isLanguageMenuOpen = false
                                onLanguageSelected(language)
                            }
                        )
                    }
                }
            }

            Box {
                TextButton(onClick = { isAppMenuOpen = true }) {
                    Text(stringResource(R.string.app_menu_label))
                }
                DropdownMenu(
                    expanded = isAppMenuOpen,
                    onDismissRequest = { isAppMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(destinationLabel(PlannerDestination.PLANNER, routeBookmarkCount)) },
                        enabled = currentDestination != PlannerDestination.PLANNER,
                        onClick = {
                            isAppMenuOpen = false
                            onDestinationSelected(PlannerDestination.PLANNER)
                        }
                    )
                    HorizontalDivider()
                    listOf(
                        PlannerDestination.SAVED_ROUTES,
                        PlannerDestination.HISTORY,
                        PlannerDestination.OFFLINE
                    ).forEach { destination ->
                        DropdownMenuItem(
                            text = { Text(destinationLabel(destination, routeBookmarkCount)) },
                            enabled = currentDestination != destination,
                            onClick = {
                                isAppMenuOpen = false
                                onDestinationSelected(destination)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun destinationLabel(destination: PlannerDestination, routeBookmarkCount: Int): String =
    when (destination) {
        PlannerDestination.PLANNER -> stringResource(R.string.app_destination_planner)
        PlannerDestination.SAVED_ROUTES -> if (routeBookmarkCount > 0) {
            stringResource(R.string.action_open_route_bookmarks_with_count, routeBookmarkCount)
        } else {
            stringResource(R.string.action_open_route_bookmarks)
        }
        PlannerDestination.HISTORY -> stringResource(R.string.action_open_route_history)
        PlannerDestination.OFFLINE -> stringResource(R.string.offline_support_title)
    }

private fun languageShortLabel(language: AppLanguage): String =
    when (language) {
        AppLanguage.ENGLISH -> "EN"
        AppLanguage.SLOVAK -> "SK"
    }

@Composable
private fun PlannerModeHeader(
    mode: PlannerMode
) {
    val title = when (mode) {
        PlannerMode.PLANNING -> stringResource(R.string.planner_mode_planning_title)
        PlannerMode.PREVIEW -> stringResource(R.string.planner_mode_preview_title)
        PlannerMode.ACTIVE -> stringResource(R.string.planner_mode_active_title)
        PlannerMode.COMPLETED -> stringResource(R.string.planner_mode_completed_title)
    }
    val body = when (mode) {
        PlannerMode.PLANNING -> stringResource(R.string.planner_mode_planning_body)
        PlannerMode.PREVIEW -> stringResource(R.string.planner_mode_preview_body)
        PlannerMode.ACTIVE -> stringResource(R.string.planner_mode_active_body)
        PlannerMode.COMPLETED -> stringResource(R.string.planner_mode_completed_body)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.screen_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String =
    when (language) {
        AppLanguage.ENGLISH -> stringResource(R.string.language_english)
        AppLanguage.SLOVAK -> stringResource(R.string.language_slovak)
    }

@Composable
private fun PlannerMapPanel(
    pois: List<com.example.smarttourism.data.remote.dto.PoiDto>,
    routeResponse: RouteResponse?,
    startPoint: RouteStartDto,
    defaultZoom: Double?,
    currentRouteLocation: RouteStartDto?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    isRouteActive: Boolean,
    isPoiLoading: Boolean,
    isSelectingStart: Boolean,
    onStartPointSelected: (Double, Double) -> Unit,
    onOpenFullScreenMap: () -> Unit,
    modifier: Modifier = Modifier,
    fixedHeight: Dp? = null
) {
    val containerModifier = if (fixedHeight != null) {
        modifier
            .fillMaxWidth()
            .height(fixedHeight)
    } else {
        modifier.fillMaxWidth()
    }

    Box(
        modifier = containerModifier.clip(MaterialTheme.shapes.extraLarge)
    ) {
        PoiMapScreen(
            pois = pois,
            routeResponse = routeResponse,
            startLat = startPoint.lat,
            startLon = startPoint.lon,
            defaultZoom = defaultZoom,
            currentLocation = currentRouteLocation,
            visitedPoiIds = visitedPoiIds.toSet(),
            skippedPoiIds = skippedPoiIds.toSet(),
            isRouteActive = isRouteActive,
            isLoading = isPoiLoading,
            isFullScreen = false,
            isSelectingStart = isSelectingStart,
            onStartPointSelected = onStartPointSelected,
            modifier = Modifier.fillMaxSize()
        )
        OutlinedButton(
            onClick = onOpenFullScreenMap,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Text(stringResource(R.string.action_open_full_screen_map))
        }
    }
}

@Composable
private fun PreviewActionRow(
    canStart: Boolean,
    onStartRoute: () -> Unit,
    onEditParameters: () -> Unit,
    onPlanAnotherRoute: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStartRoute,
            enabled = canStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_start_route))
        }
        OutlinedButton(
            onClick = onEditParameters,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_edit_parameters))
        }
        Text(
            text = stringResource(R.string.route_preview_edit_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onPlanAnotherRoute,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_plan_another_route))
        }
    }
}

private data class PlannerAlerts(
    val locationError: String?,
    val poiError: String?,
    val routeError: String?,
    val trackingError: String?,
    val hasNoGeneratedStops: Boolean,
    val hasPendingRouteChanges: Boolean
)

private fun PlannerAlerts.hasVisibleAlerts(): Boolean =
    locationError != null ||
        poiError != null ||
        routeError != null ||
        trackingError != null ||
        hasNoGeneratedStops ||
        hasPendingRouteChanges

@Composable
private fun PlannerAlertColumn(alerts: PlannerAlerts) {
    alerts.locationError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_location_unavailable),
            body = message
        )
    }

    alerts.poiError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_poi_preview_unavailable),
            body = message
        )
    }

    alerts.routeError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_route_generation_failed),
            body = message
        )
    }

    if (alerts.hasNoGeneratedStops) {
        StatusCard(
            title = stringResource(R.string.status_no_stops_title),
            body = stringResource(R.string.status_no_stops_body)
        )
    }

    if (alerts.hasPendingRouteChanges) {
        StatusCard(
            title = stringResource(R.string.route_preview_outdated_title),
            body = stringResource(R.string.route_preview_outdated_body)
        )
    }

    alerts.trackingError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_gps_tracking_unavailable),
            body = message
        )
    }
}

private fun LazyListScope.plannerAlertItems(alerts: PlannerAlerts) {
    if (!alerts.hasVisibleAlerts()) {
        return
    }

    item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlannerAlertColumn(alerts = alerts)
        }
    }
}

private fun LazyListScope.routeStopItems(
    titleRes: Int,
    routeStops: List<RouteItemDto>,
    routeResponse: RouteResponse?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    isRouteActive: Boolean,
    canSkip: Boolean,
    canReplace: Boolean,
    isActionInProgress: Boolean,
    highlightedPoiId: Int?,
    onMarkVisited: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onReplace: (Int) -> Unit
) {
    if (routeStops.isEmpty()) {
        item {
            StatusCard(
                title = stringResource(R.string.status_no_stops_title),
                body = stringResource(R.string.status_no_stops_body)
            )
        }
        return
    }

    item {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }

    itemsIndexed(
        items = routeStops,
        key = { _, item -> item.poi_id }
    ) { index, item ->
        RouteStopTimelineItem(
            item = item,
            incomingLeg = routeResponse?.legs.orEmpty().firstOrNull { leg -> leg.to.poi_id == item.poi_id },
            isVisited = item.poi_id in visitedPoiIds,
            isSkipped = item.poi_id in skippedPoiIds,
            isNext = highlightedPoiId == item.poi_id,
            isLast = index == routeStops.lastIndex,
            isRouteActive = isRouteActive,
            canSkip = canSkip,
            canReplace = canReplace,
            isActionInProgress = isActionInProgress,
            onMarkVisited = { onMarkVisited(item.poi_id) },
            onSkip = { onSkip(item.poi_id) },
            onReplace = { onReplace(item.poi_id) }
        )
    }
}

private fun plannerModeFor(
    routeResponse: RouteResponse?,
    status: RouteSessionStatus
): PlannerMode =
    when {
        status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED -> PlannerMode.ACTIVE
        routeResponse != null && status == RouteSessionStatus.COMPLETED -> PlannerMode.COMPLETED
        routeResponse != null -> PlannerMode.PREVIEW
        else -> PlannerMode.PLANNING
    }
