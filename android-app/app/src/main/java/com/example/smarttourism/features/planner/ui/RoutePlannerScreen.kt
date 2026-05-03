package com.example.smarttourism.features.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.remote.dto.RouteStartDto
import com.example.smarttourism.R
import com.example.smarttourism.features.map.PoiMapScreen
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
fun RoutePlannerScreen() {
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
    val isRouteLoading = plannerViewModel.isRouteLoading
    val routeError = plannerViewModel.routeError
    val isRerouting = plannerViewModel.isRerouting
    val availableMinutes = plannerViewModel.availableMinutes
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
    val progressMetrics = plannerViewModel.progressMetrics
    val selectedCityAvailableCategories = plannerViewModel.selectedCityAvailableCategories
    val isPublicTransportAvailable = plannerViewModel.isPublicTransportAvailable

    var isSelectingStart by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(false) }
    var isMapFullScreen by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var trackingPermissionAction by remember { mutableStateOf(TrackingPermissionAction.START) }

    LaunchedEffect(Unit) {
        plannerViewModel.initialize()
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

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.screen_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.screen_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            CitySelectorCard(
                cities = cities,
                selectedCity = selectedCity,
                onCitySelected = { city ->
                    if (selectedCity?.slug == city.slug) {
                        return@CitySelectorCard
                    }
                    plannerViewModel.selectCity(city)
                }
            )
        }

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

        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
            ) {
                PoiMapScreen(
                    pois = pois,
                    routeResponse = routeResponse,
                    startLat = startPoint.lat,
                    startLon = startPoint.lon,
                    defaultZoom = selectedCity?.default_zoom,
                    currentLocation = currentRouteLocation,
                    visitedPoiIds = visitedPoiIds.toSet(),
                    isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                    isLoading = isPoiLoading,
                    isFullScreen = false,
                    isSelectingStart = isSelectingStart,
                    onStartPointSelected = ::updateStartPoint,
                    modifier = Modifier.fillMaxSize()
                )
                OutlinedButton(
                    onClick = { isMapFullScreen = true },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp)
                ) {
                    Text(stringResource(R.string.action_open_full_screen_map))
                }
            }
        }

        item {
            StartPointCard(
                startPoint = startPoint,
                isSelectingStart = isSelectingStart,
                isLocating = isLocating,
                onToggleMapSelection = {
                    val isEnteringSelection = !isSelectingStart
                    isSelectingStart = isEnteringSelection
                    if (isEnteringSelection) {
                        isMapFullScreen = true
                    }
                    locationError = null
                    plannerViewModel.clearDisplayedRoute()
                },
                onUseCurrentLocation = {
                    isSelectingStart = false
                    plannerViewModel.clearDisplayedRoute()

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
                onAvailableMinutesChange = { plannerViewModel.updateAvailableMinutes(it) },
                availableInterests = selectedCityAvailableCategories,
                selectedInterests = selectedInterests,
                onInterestToggle = { interest, checked -> plannerViewModel.toggleInterest(interest, checked) },
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
                onUseCurrentTime = { plannerViewModel.useCurrentTime() },
                isGenerating = isRouteLoading,
                onGenerateRoute = { plannerViewModel.generateRoute() }
            )
        }

        if (locationError != null) {
            item {
                StatusCard(
                    title = stringResource(R.string.status_location_unavailable),
                    body = locationError!!
                )
            }
        }

        if (poiError != null) {
            item {
                StatusCard(
                    title = stringResource(R.string.status_poi_preview_unavailable),
                    body = poiError!!
                )
            }
        }

        if (routeError != null) {
            item {
                StatusCard(
                    title = stringResource(R.string.status_route_generation_failed),
                    body = routeError!!
                )
            }
        }

        if (trackingError != null) {
            item {
                StatusCard(
                    title = stringResource(R.string.status_gps_tracking_unavailable),
                    body = trackingError!!
                )
            }
        }

        when {
            routeResponse != null -> {
                item {
                    RouteTrackingCard(
                        status = routeSessionStatus,
                        routeId = routeId,
                        startedAt = routeStartedAt,
                        metrics = progressMetrics,
                        currentLocation = currentRouteLocation,
                        isRerouting = isRerouting,
                        onStartRoute = { startRoute() },
                        onPauseRoute = { plannerViewModel.pauseRoute() },
                        onResumeRoute = { resumeRoute() },
                        onFinishRoute = { plannerViewModel.finishRoute() },
                        onCancelRoute = { plannerViewModel.cancelRoute() }
                    )
                }

                if (routeSessionStatus == RouteSessionStatus.COMPLETED) {
                    item {
                        RouteFeedbackCard(
                            feedback = routeFeedback,
                            onFeedbackChange = { feedback -> plannerViewModel.updateFeedback(feedback) }
                        )
                    }
                }

                item {
                    RouteSummaryCard(routeResponse = routeResponse!!)
                }

                if (routeItems.isEmpty()) {
                    item {
                        StatusCard(
                            title = stringResource(R.string.status_no_stops_title),
                            body = stringResource(R.string.status_no_stops_body)
                        )
                    }
                } else {
                    items(
                        items = routeItems,
                        key = { item -> item.poi_id }
                    ) { item ->
                        RouteStopCard(
                            item = item,
                            incomingLeg = routeResponse?.legs.orEmpty().firstOrNull { leg -> leg.to.poi_id == item.poi_id },
                            isVisited = item.poi_id in visitedPoiIds,
                            isSkipped = item.poi_id in skippedPoiIds,
                            isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                            canSkip = routeSessionStatus != RouteSessionStatus.COMPLETED &&
                                routeSessionStatus != RouteSessionStatus.CANCELLED,
                            isActionInProgress = isRerouting,
                            onMarkVisited = { plannerViewModel.markRouteStopVisited(item.poi_id) },
                            onSkip = { plannerViewModel.skipRouteStop(item.poi_id) }
                        )
                    }
                }
            }

            routeError == null && !isRouteLoading -> {
                item {
                    StatusCard(
                        title = stringResource(R.string.status_no_route_title),
                        body = stringResource(R.string.status_no_route_body)
                    )
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
                        .padding(16.dp)
                ) {
                    Text(stringResource(R.string.action_close_map))
                }
            }
        }
    }
}
