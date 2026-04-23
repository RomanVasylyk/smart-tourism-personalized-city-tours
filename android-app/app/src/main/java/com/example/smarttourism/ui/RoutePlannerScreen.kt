package com.example.smarttourism.ui

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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarttourism.R
import com.example.smarttourism.data.ActiveRouteSession
import com.example.smarttourism.data.ApiModule
import com.example.smarttourism.data.PoiDto
import com.example.smarttourism.data.RouteFeedback
import com.example.smarttourism.data.RouteRequest
import com.example.smarttourism.data.RouteResponse
import com.example.smarttourism.data.RouteStartDto
import com.example.smarttourism.data.RouteStorage
import com.example.smarttourism.data.RouteItemDto
import com.example.smarttourism.data.SavedRouteSnapshot
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil

private const val DefaultCity = "nitra"
private val DefaultStartPoint = RouteStartDto(lat = 48.3076, lon = 18.0845)
private val AvailableMinutesOptions = listOf(120, 180, 240)
private val InterestOptions = listOf("museum", "historical_site", "viewpoint", "park")
private val PaceOptions = listOf("slow", "normal", "fast")
private val RouteTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.getDefault())
private const val RouteTrackingMinTimeMs = 5_000L
private const val RouteTrackingMinDistanceMeters = 5f
private const val PoiVisitedRadiusMeters = 60f
private const val OffRouteDistanceMeters = 120f

private enum class RouteSessionStatus(val rawValue: String) {
    NOT_STARTED("not_started"),
    IN_PROGRESS("in_progress"),
    PAUSED("paused"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    companion object {
        fun fromRawValue(rawValue: String?): RouteSessionStatus =
            entries.firstOrNull { status -> status.rawValue == rawValue } ?: NOT_STARTED
    }
}

private data class RouteProgressMetrics(
    val visitedCount: Int,
    val totalCount: Int,
    val nextTarget: RouteItemDto?,
    val distanceToNextTargetMeters: Float?,
    val estimatedRemainingMinutes: Int,
    val isOffRoute: Boolean,
    val canComplete: Boolean
)

@Composable
fun RoutePlannerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationPermissionDeniedMessage = stringResource(R.string.error_location_permission_denied)
    val poiPreviewFailedMessage = stringResource(R.string.error_poi_preview_failed)
    val routeGenerationFailedMessage = stringResource(R.string.error_route_generation_failed_default)

    var pois by remember { mutableStateOf<List<PoiDto>>(emptyList()) }
    var isPoiLoading by remember { mutableStateOf(true) }
    var poiError by remember { mutableStateOf<String?>(null) }

    var routeResponse by remember { mutableStateOf<RouteResponse?>(null) }
    var currentRouteRequest by remember { mutableStateOf<RouteRequest?>(null) }
    var isRouteLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var isRerouting by remember { mutableStateOf(false) }

    var availableMinutes by remember { mutableIntStateOf(180) }
    var pace by remember { mutableStateOf("normal") }
    var returnToStart by remember { mutableStateOf(true) }
    var respectOpeningHours by remember { mutableStateOf(true) }
    var startPoint by remember { mutableStateOf(DefaultStartPoint) }
    var startDateTime by remember { mutableStateOf(defaultRouteStartDateTime()) }
    var isSelectingStart by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }
    var routeSessionStatus by remember { mutableStateOf(RouteSessionStatus.NOT_STARTED) }
    var routeId by remember { mutableStateOf<String?>(null) }
    var routeStartedAt by remember { mutableStateOf<String?>(null) }
    var currentTargetPoiId by remember { mutableStateOf<Int?>(null) }
    var currentRouteLocation by remember { mutableStateOf<RouteStartDto?>(null) }
    var trackingError by remember { mutableStateOf<String?>(null) }
    var routeFeedback by remember { mutableStateOf<RouteFeedback?>(null) }

    val selectedInterests = remember { mutableStateListOf(*InterestOptions.toTypedArray()) }
    val visitedPoiIds = remember { mutableStateListOf<Int>() }
    val routeItems = routeResponse?.route.orEmpty()

    fun resetRouteSession(clearStoredSession: Boolean = true) {
        routeSessionStatus = RouteSessionStatus.NOT_STARTED
        routeId = null
        routeStartedAt = null
        currentTargetPoiId = null
        currentRouteLocation = null
        trackingError = null
        routeFeedback = null
        visitedPoiIds.clear()
        if (clearStoredSession) {
            scope.launch {
                RouteStorage.clearActiveSession(context)
            }
        }
    }

    val clearDisplayedRoute = {
        routeResponse = null
        currentRouteRequest = null
        routeError = null
        resetRouteSession()
    }

    val updateStartPoint = { lat: Double, lon: Double ->
        startPoint = RouteStartDto(lat = lat, lon = lon)
        isSelectingStart = false
        locationError = null
        clearDisplayedRoute()
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

    fun currentRouteSnapshot(): SavedRouteSnapshot? {
        val request = currentRouteRequest
        val response = routeResponse
        return if (request != null && response != null) {
            SavedRouteSnapshot(request = request, response = response)
        } else {
            null
        }
    }

    fun persistRouteSession(
        status: RouteSessionStatus = routeSessionStatus,
        routeIdValue: String? = routeId,
        startedAtValue: String? = routeStartedAt,
        visitedIds: List<Int> = visitedPoiIds.toList(),
        feedback: RouteFeedback? = routeFeedback,
        snapshotOverride: SavedRouteSnapshot? = null
    ) {
        val snapshot = snapshotOverride ?: currentRouteSnapshot() ?: return
        val savedRouteId = routeIdValue ?: return
        val savedStartedAt = startedAtValue ?: defaultRouteStartDateTime().toString()
        val nextTargetId = nextUnvisitedPoi(snapshot.response.route, visitedIds)?.poi_id
        val totalCount = progressTotalCount(snapshot.response.route, visitedIds)

        currentTargetPoiId = nextTargetId

        scope.launch {
            RouteStorage.saveActiveSession(
                context = context,
                session = ActiveRouteSession(
                    route_id = savedRouteId,
                    status = status.rawValue,
                    started_at = savedStartedAt,
                    current_target_poi_id = nextTargetId,
                    visited_poi_ids = visitedIds,
                    progress_visited_count = visitedIds.distinct().size,
                    progress_total_count = totalCount,
                    snapshot = snapshot,
                    feedback = feedback
                )
            )
        }
    }

    fun setRouteStatus(status: RouteSessionStatus) {
        routeSessionStatus = status
        persistRouteSession(status = status)
    }

    fun activateRouteTracking() {
        val response = routeResponse
        if (response?.route.isNullOrEmpty() || currentRouteRequest == null) {
            return
        }

        if (routeSessionStatus == RouteSessionStatus.NOT_STARTED ||
            routeSessionStatus == RouteSessionStatus.COMPLETED ||
            routeSessionStatus == RouteSessionStatus.CANCELLED
        ) {
            visitedPoiIds.clear()
            routeFeedback = null
        }

        val activeRouteId = routeId ?: UUID.randomUUID().toString()
        val activeStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()

        routeId = activeRouteId
        routeStartedAt = activeStartedAt
        currentTargetPoiId = nextUnvisitedPoi(response!!.route, visitedPoiIds)?.poi_id
        trackingError = null
        routeSessionStatus = RouteSessionStatus.IN_PROGRESS
        persistRouteSession(
            status = RouteSessionStatus.IN_PROGRESS,
            routeIdValue = activeRouteId,
            startedAtValue = activeStartedAt,
            feedback = null
        )
    }

    val trackingPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            activateRouteTracking()
        } else {
            routeSessionStatus = RouteSessionStatus.PAUSED
            trackingError = locationPermissionDeniedMessage
            persistRouteSession(status = RouteSessionStatus.PAUSED)
        }
    }

    LaunchedEffect(Unit) {
        val activeSession = RouteStorage.loadActiveSession(context)
        val savedSnapshot = activeSession?.snapshot ?: RouteStorage.load(context)

        savedSnapshot?.let { snapshot ->
            currentRouteRequest = snapshot.request
            applySavedSnapshot(
                snapshot = snapshot,
                onRouteRestored = { restoredRoute -> routeResponse = restoredRoute },
                onStartPointRestored = { restoredStart -> startPoint = restoredStart },
                onAvailableMinutesRestored = { restoredMinutes -> availableMinutes = restoredMinutes },
                onPaceRestored = { restoredPace -> pace = restoredPace },
                onReturnToStartRestored = { restoredReturnToStart -> returnToStart = restoredReturnToStart },
                onRespectOpeningHoursRestored = { restoredRespectOpeningHours ->
                    respectOpeningHours = restoredRespectOpeningHours
                },
                onStartDateTimeRestored = { restoredStartDateTime -> startDateTime = restoredStartDateTime },
                selectedInterests = selectedInterests
            )
        }

        if (activeSession != null) {
            routeId = activeSession.route_id
            routeStartedAt = activeSession.started_at
            routeSessionStatus = RouteSessionStatus.fromRawValue(activeSession.status)
            currentTargetPoiId = activeSession.current_target_poi_id
            routeFeedback = activeSession.feedback
            visitedPoiIds.clear()
            visitedPoiIds.addAll(activeSession.visited_poi_ids.distinct())
        }

        try {
            pois = ApiModule.poiApi.getPois(DefaultCity)
            poiError = null
        } catch (e: Exception) {
            poiError = e.toUserMessage(poiPreviewFailedMessage)
        } finally {
            isPoiLoading = false
        }
    }

    DisposableEffect(context, routeItems, routeSessionStatus) {
        if (routeSessionStatus != RouteSessionStatus.IN_PROGRESS) {
            onDispose { }
        } else {
            val stopTracking = startRouteLocationTracking(
                context = context,
                onLocation = { location ->
                    val routeLocation = RouteStartDto(
                        lat = location.latitude,
                        lon = location.longitude
                    )

                    currentRouteLocation = routeLocation
                    trackingError = null
                    val visitedChanged = markNearbyPoisVisited(
                        routeItems = routeItems,
                        currentLocation = routeLocation,
                        visitedPoiIds = visitedPoiIds
                    )
                    currentTargetPoiId = nextUnvisitedPoi(routeItems, visitedPoiIds)?.poi_id

                    if (routeItems.isNotEmpty() && routeItems.all { item -> item.poi_id in visitedPoiIds }) {
                        routeSessionStatus = RouteSessionStatus.COMPLETED
                        persistRouteSession(status = RouteSessionStatus.COMPLETED)
                    } else if (visitedChanged) {
                        persistRouteSession(status = RouteSessionStatus.IN_PROGRESS)
                    }
                },
                onError = { message ->
                    routeSessionStatus = RouteSessionStatus.PAUSED
                    trackingError = message
                    persistRouteSession(status = RouteSessionStatus.PAUSED)
                }
            )

            onDispose {
                stopTracking()
            }
        }
    }

    val progressMetrics = routeProgressMetrics(
        routeResponse = routeResponse,
        routeItems = routeItems,
        visitedPoiIds = visitedPoiIds,
        currentLocation = currentRouteLocation,
        isTracking = routeSessionStatus == RouteSessionStatus.IN_PROGRESS
    )

    fun pauseRoute() {
        if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS) {
            setRouteStatus(RouteSessionStatus.PAUSED)
        }
    }

    fun startRoute() {
        isSelectingStart = false

        val hasFineLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (hasFineLocationPermission) {
            activateRouteTracking()
        } else {
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
            val activeRouteId = routeId ?: UUID.randomUUID().toString()
            val activeStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()
            routeId = activeRouteId
            routeStartedAt = activeStartedAt
            routeSessionStatus = RouteSessionStatus.IN_PROGRESS
            trackingError = null
            persistRouteSession(
                status = RouteSessionStatus.IN_PROGRESS,
                routeIdValue = activeRouteId,
                startedAtValue = activeStartedAt
            )
        } else {
            trackingPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    fun finishRoute() {
        if (!progressMetrics.canComplete) {
            return
        }

        routeSessionStatus = RouteSessionStatus.COMPLETED
        persistRouteSession(status = RouteSessionStatus.COMPLETED)
    }

    fun cancelRoute() {
        routeSessionStatus = RouteSessionStatus.CANCELLED
        persistRouteSession(status = RouteSessionStatus.CANCELLED)
    }

    fun updateFeedback(feedback: RouteFeedback) {
        routeFeedback = feedback
        persistRouteSession(
            status = RouteSessionStatus.COMPLETED,
            feedback = feedback
        )
    }

    fun recalculateFromCurrentLocation() {
        val currentLocation = currentRouteLocation ?: return
        val baseRequest = currentRouteRequest ?: return
        val response = routeResponse ?: return

        scope.launch {
            isRerouting = true
            routeError = null

            val remainingMinutes = progressMetrics.estimatedRemainingMinutes
                .coerceIn(30, maxOf(30, baseRequest.available_minutes))
            val request = baseRequest.copy(
                start_lat = currentLocation.lat,
                start_lon = currentLocation.lon,
                available_minutes = remainingMinutes,
                start_datetime = defaultRouteStartDateTime().toString(),
                exclude_poi_ids = visitedPoiIds.distinct()
            )

            try {
                val generatedRoute = ApiModule.poiApi.generateRoute(request)
                val snapshot = SavedRouteSnapshot(
                    request = request,
                    response = generatedRoute
                )

                currentRouteRequest = request
                routeResponse = generatedRoute
                startPoint = RouteStartDto(currentLocation.lat, currentLocation.lon)
                currentTargetPoiId = nextUnvisitedPoi(generatedRoute.route, visitedPoiIds)?.poi_id
                routeSessionStatus = RouteSessionStatus.IN_PROGRESS
                RouteStorage.save(context = context, snapshot = snapshot)
                persistRouteSession(
                    status = RouteSessionStatus.IN_PROGRESS,
                    snapshotOverride = snapshot
                )
            } catch (e: Exception) {
                routeError = e.toUserMessage(routeGenerationFailedMessage)
                routeResponse = response
            } finally {
                isRerouting = false
            }
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
            PoiMapScreen(
                pois = pois,
                routeResponse = routeResponse,
                startLat = startPoint.lat,
                startLon = startPoint.lon,
                currentLocation = currentRouteLocation,
                visitedPoiIds = visitedPoiIds.toSet(),
                isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                isLoading = isPoiLoading,
                isSelectingStart = isSelectingStart,
                onStartPointSelected = updateStartPoint,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
            )
        }

        item {
            StartPointCard(
                startPoint = startPoint,
                isSelectingStart = isSelectingStart,
                isLocating = isLocating,
                onToggleMapSelection = {
                    isSelectingStart = !isSelectingStart
                    locationError = null
                    clearDisplayedRoute()
                },
                onUseCurrentLocation = {
                    isSelectingStart = false
                    clearDisplayedRoute()

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
                onAvailableMinutesChange = {
                    availableMinutes = it
                    clearDisplayedRoute()
                },
                selectedInterests = selectedInterests,
                onInterestToggle = { interest, checked ->
                    if (checked) {
                        if (interest !in selectedInterests) {
                            selectedInterests.add(interest)
                        }
                    } else {
                        selectedInterests.remove(interest)
                    }
                    clearDisplayedRoute()
                },
                pace = pace,
                onPaceChange = {
                    pace = it
                    clearDisplayedRoute()
                },
                returnToStart = returnToStart,
                onReturnToStartChange = {
                    returnToStart = it
                    clearDisplayedRoute()
                },
                respectOpeningHours = respectOpeningHours,
                onRespectOpeningHoursChange = {
                    respectOpeningHours = it
                    clearDisplayedRoute()
                },
                startDateTime = startDateTime,
                onUseCurrentTime = {
                    startDateTime = defaultRouteStartDateTime()
                    clearDisplayedRoute()
                },
                isGenerating = isRouteLoading,
                onGenerateRoute = {
                    scope.launch {
                        isRouteLoading = true
                        routeError = null

                        val request = RouteRequest(
                            city = DefaultCity,
                            start_lat = startPoint.lat,
                            start_lon = startPoint.lon,
                            available_minutes = availableMinutes,
                            interests = selectedInterests.toList(),
                            pace = pace,
                            return_to_start = returnToStart,
                            start_datetime = startDateTime.truncatedTo(ChronoUnit.MINUTES).toString(),
                            respect_opening_hours = respectOpeningHours
                        )

                        try {
                            val generatedRoute = ApiModule.poiApi.generateRoute(request)
                            resetRouteSession()
                            currentRouteRequest = request
                            routeResponse = generatedRoute
                            RouteStorage.save(
                                context = context,
                                snapshot = SavedRouteSnapshot(
                                    request = request,
                                    response = generatedRoute
                                )
                            )
                        } catch (e: Exception) {
                            routeError = e.toUserMessage(routeGenerationFailedMessage)
                        } finally {
                            isRouteLoading = false
                        }
                    }
                }
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
                        onPauseRoute = { pauseRoute() },
                        onResumeRoute = { resumeRoute() },
                        onFinishRoute = { finishRoute() },
                        onCancelRoute = { cancelRoute() },
                        onRecalculateRoute = { recalculateFromCurrentLocation() }
                    )
                }

                if (routeSessionStatus == RouteSessionStatus.COMPLETED) {
                    item {
                        RouteFeedbackCard(
                            feedback = routeFeedback,
                            onFeedbackChange = { feedback -> updateFeedback(feedback) }
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
                            isVisited = item.poi_id in visitedPoiIds,
                            isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                            onMarkVisited = {
                                if (item.poi_id !in visitedPoiIds) {
                                    visitedPoiIds.add(item.poi_id)
                                    currentTargetPoiId = nextUnvisitedPoi(routeItems, visitedPoiIds)?.poi_id
                                    if (routeItems.all { routeItem -> routeItem.poi_id in visitedPoiIds }) {
                                        routeSessionStatus = RouteSessionStatus.COMPLETED
                                        persistRouteSession(status = RouteSessionStatus.COMPLETED)
                                    } else {
                                        persistRouteSession()
                                    }
                                }
                            }
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
}

@Composable
private fun StartPointCard(
    startPoint: RouteStartDto,
    isSelectingStart: Boolean,
    isLocating: Boolean,
    onToggleMapSelection: () -> Unit,
    onUseCurrentLocation: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.start_point_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.start_point_coordinates,
                    formatCoordinate(startPoint.lat),
                    formatCoordinate(startPoint.lon)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (isSelectingStart) {
                    stringResource(R.string.start_point_selecting_body)
                } else {
                    stringResource(R.string.start_point_default_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleMapSelection,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isSelectingStart) {
                            stringResource(R.string.action_cancel_map_pick)
                        } else {
                            stringResource(R.string.action_pick_on_map)
                        }
                    )
                }
                Button(
                    onClick = onUseCurrentLocation,
                    modifier = Modifier.weight(1f),
                    enabled = !isLocating
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        if (isLocating) {
                            stringResource(R.string.action_locating)
                        } else {
                            stringResource(R.string.action_use_my_location)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteParametersCard(
    availableMinutes: Int,
    onAvailableMinutesChange: (Int) -> Unit,
    selectedInterests: List<String>,
    onInterestToggle: (String, Boolean) -> Unit,
    pace: String,
    onPaceChange: (String) -> Unit,
    returnToStart: Boolean,
    onReturnToStartChange: (Boolean) -> Unit,
    respectOpeningHours: Boolean,
    onRespectOpeningHoursChange: (Boolean) -> Unit,
    startDateTime: LocalDateTime,
    onUseCurrentTime: () -> Unit,
    isGenerating: Boolean,
    onGenerateRoute: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_parameters_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.route_start_time_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = startDateTime.format(RouteTimeFormatter),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onUseCurrentTime) {
                    Text(stringResource(R.string.action_use_now))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.respect_opening_hours_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = stringResource(R.string.respect_opening_hours_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = respectOpeningHours,
                    onCheckedChange = onRespectOpeningHoursChange
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.available_time_label),
                style = MaterialTheme.typography.labelLarge
            )
            AvailableMinutesOptions.forEach { option ->
                SelectableRow(
                    onClick = { onAvailableMinutesChange(option) }
                ) {
                    RadioButton(
                        selected = availableMinutes == option,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.available_minutes_option, option))
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.interests_label),
                style = MaterialTheme.typography.labelLarge
            )
            InterestOptions.forEach { interest ->
                val checked = interest in selectedInterests
                SelectableRow(
                    onClick = { onInterestToggle(interest, !checked) }
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(categoryLabel(interest))
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.pace_label),
                style = MaterialTheme.typography.labelLarge
            )
            PaceOptions.forEach { option ->
                SelectableRow(
                    onClick = { onPaceChange(option) }
                ) {
                    RadioButton(
                        selected = pace == option,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(paceLabel(option))
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.return_to_start_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = stringResource(R.string.return_to_start_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = returnToStart,
                    onCheckedChange = onReturnToStartChange
                )
            }

            Button(
                onClick = onGenerateRoute,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.action_generating_route))
                } else {
                    Text(stringResource(R.string.action_generate_route))
                }
            }
        }
    }
}

@Composable
private fun RouteTrackingCard(
    status: RouteSessionStatus,
    routeId: String?,
    startedAt: String?,
    metrics: RouteProgressMetrics,
    currentLocation: RouteStartDto?,
    isRerouting: Boolean,
    onStartRoute: () -> Unit,
    onPauseRoute: () -> Unit,
    onResumeRoute: () -> Unit,
    onFinishRoute: () -> Unit,
    onCancelRoute: () -> Unit,
    onRecalculateRoute: () -> Unit
) {
    val progress = if (metrics.totalCount == 0) {
        0f
    } else {
        metrics.visitedCount.toFloat() / metrics.totalCount.toFloat()
    }
    val nextTargetName = metrics.nextTarget?.name ?: stringResource(R.string.route_tracking_no_next_target)
    val canPause = status == RouteSessionStatus.IN_PROGRESS
    val canResume = status == RouteSessionStatus.PAUSED
    val canStart = status == RouteSessionStatus.NOT_STARTED ||
        status == RouteSessionStatus.COMPLETED ||
        status == RouteSessionStatus.CANCELLED

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.route_tracking_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = routeSessionStatusLabel(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!routeId.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.route_tracking_route_id, routeId.takeLast(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!startedAt.isNullOrBlank()) {
                Text(
                    text = stringResource(
                        R.string.route_tracking_started_at,
                        startedAt.toRouteDateTimeLabel(stringResource(R.string.common_unknown))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(
                    R.string.route_tracking_visited_count,
                    metrics.visitedCount,
                    metrics.totalCount
                )
            )
            Text(
                text = stringResource(R.string.route_tracking_next_target, nextTargetName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.route_tracking_distance_to_next,
                    metrics.distanceToNextTargetMeters?.let(::formatDistanceMeters)
                        ?: stringResource(R.string.common_unknown)
                )
            )
            Text(
                text = stringResource(
                    R.string.route_tracking_estimated_remaining,
                    metrics.estimatedRemainingMinutes
                )
            )
            if (status == RouteSessionStatus.IN_PROGRESS && currentLocation == null) {
                Text(
                    text = stringResource(R.string.route_tracking_waiting_for_gps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (currentLocation != null) {
                Text(
                    text = stringResource(
                        R.string.route_tracking_current_location,
                        formatCoordinate(currentLocation.lat),
                        formatCoordinate(currentLocation.lon)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (metrics.isOffRoute) {
                Text(
                    text = stringResource(R.string.status_off_route_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = stringResource(R.string.status_off_route_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Button(
                    onClick = onRecalculateRoute,
                    enabled = !isRerouting && currentLocation != null,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isRerouting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        if (isRerouting) {
                            stringResource(R.string.action_recalculating_route)
                        } else {
                            stringResource(R.string.action_recalculate_route)
                        }
                    )
                }
            }
            Text(
                text = if (metrics.totalCount == 0) {
                    stringResource(R.string.route_tracking_no_stops)
                } else {
                    stringResource(R.string.route_tracking_auto_visit_hint, PoiVisitedRadiusMeters.toInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    canStart -> Button(
                        onClick = onStartRoute,
                        modifier = Modifier.weight(1f),
                        enabled = metrics.totalCount > 0
                    ) {
                        Text(stringResource(R.string.action_start_route))
                    }

                    canPause -> OutlinedButton(
                        onClick = onPauseRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_pause_route))
                    }

                    canResume -> OutlinedButton(
                        onClick = onResumeRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_resume_route))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onFinishRoute,
                    modifier = Modifier.weight(1f),
                    enabled = metrics.canComplete &&
                        (status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED)
                ) {
                    Text(stringResource(R.string.action_finish_route))
                }
                OutlinedButton(
                    onClick = onCancelRoute,
                    modifier = Modifier.weight(1f),
                    enabled = status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED
                ) {
                    Text(stringResource(R.string.action_cancel_route))
                }
            }
            if (!metrics.canComplete &&
                metrics.totalCount > 0 &&
                (status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED)
            ) {
                Text(
                    text = stringResource(
                        R.string.route_tracking_finish_requirement,
                        requiredCompletionCount(metrics.totalCount),
                        metrics.totalCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun RouteFeedbackCard(
    feedback: RouteFeedback?,
    onFeedbackChange: (RouteFeedback) -> Unit
) {
    val currentFeedback = feedback ?: RouteFeedback(
        rating = 0,
        route_was_comfortable = false,
        too_much_walking = false,
        pois_were_interesting = false
    )

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_feedback_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_feedback_rating_label),
                style = MaterialTheme.typography.labelLarge
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                (1..5).forEach { rating ->
                    val selected = currentFeedback.rating == rating
                    if (selected) {
                        Button(
                            onClick = {
                                onFeedbackChange(currentFeedback.copy(rating = rating))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(rating.toString())
                        }
                    } else {
                        OutlinedButton(
                            onClick = {
                                onFeedbackChange(currentFeedback.copy(rating = rating))
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(rating.toString())
                        }
                    }
                }
            }

            FeedbackSwitchRow(
                title = stringResource(R.string.route_feedback_comfortable),
                checked = currentFeedback.route_was_comfortable,
                onCheckedChange = { checked ->
                    onFeedbackChange(currentFeedback.copy(route_was_comfortable = checked))
                }
            )
            FeedbackSwitchRow(
                title = stringResource(R.string.route_feedback_too_much_walking),
                checked = currentFeedback.too_much_walking,
                onCheckedChange = { checked ->
                    onFeedbackChange(currentFeedback.copy(too_much_walking = checked))
                }
            )
            FeedbackSwitchRow(
                title = stringResource(R.string.route_feedback_interesting_pois),
                checked = currentFeedback.pois_were_interesting,
                onCheckedChange = { checked ->
                    onFeedbackChange(currentFeedback.copy(pois_were_interesting = checked))
                }
            )
        }
    }
}

@Composable
private fun FeedbackSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun RouteSummaryCard(routeResponse: RouteResponse) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.route_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.route_summary_city, routeResponse.city)
            )
            Text(
                stringResource(
                    R.string.route_summary_start,
                    formatCoordinate(routeResponse.start.lat),
                    formatCoordinate(routeResponse.start.lon)
                )
            )
            Text(
                stringResource(
                    R.string.route_summary_start_time,
                    routeResponse.start_datetime.toRouteDateTimeLabel(stringResource(R.string.common_unknown))
                )
            )
            Text(stringResource(R.string.route_summary_pace, paceLabel(routeResponse.pace)))
            Text(stringResource(R.string.route_summary_stops, routeResponse.poi_count))
            Text(
                stringResource(
                    R.string.route_summary_used_time,
                    routeResponse.used_minutes,
                    routeResponse.available_minutes
                )
            )
            Text(stringResource(R.string.route_summary_walking, routeResponse.total_walk_minutes))
            Text(stringResource(R.string.route_summary_visits, routeResponse.total_visit_minutes))
            Text(stringResource(R.string.route_summary_remaining, routeResponse.remaining_minutes))
            Text(stringResource(R.string.route_summary_return_to_start, routeResponse.return_to_start_minutes))
            Text(
                stringResource(
                    R.string.route_summary_opening_hours_filter,
                    if (routeResponse.respect_opening_hours) {
                        stringResource(R.string.state_on)
                    } else {
                        stringResource(R.string.state_off)
                    }
                )
            )
        }
    }
}

@Composable
private fun RouteStopCard(
    item: RouteItemDto,
    isVisited: Boolean,
    isRouteActive: Boolean,
    onMarkVisited: () -> Unit
) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.route_stop_title, item.order, item.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = categoryLabel(item.category),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isVisited) {
                    Text(
                        text = stringResource(R.string.route_stop_visited),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (isRouteActive) {
                    OutlinedButton(onClick = onMarkVisited) {
                        Text(stringResource(R.string.action_mark_visited))
                    }
                }
            }
            Text(stringResource(R.string.route_stop_walk_from_previous, item.travel_minutes_from_previous))
            Text(stringResource(R.string.route_stop_visit_duration, item.visit_duration_min))
            Text(stringResource(R.string.route_stop_arrival_after_start, item.arrival_after_min))
            Text(stringResource(R.string.route_stop_departure_after_start, item.departure_after_min))
            if (!item.opening_hours_raw.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.route_stop_opening_hours, item.opening_hours_raw),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SelectableRow(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content
    )
}

private fun applySavedSnapshot(
    snapshot: SavedRouteSnapshot,
    onRouteRestored: (RouteResponse) -> Unit,
    onStartPointRestored: (RouteStartDto) -> Unit,
    onAvailableMinutesRestored: (Int) -> Unit,
    onPaceRestored: (String) -> Unit,
    onReturnToStartRestored: (Boolean) -> Unit,
    onRespectOpeningHoursRestored: (Boolean) -> Unit,
    onStartDateTimeRestored: (LocalDateTime) -> Unit,
    selectedInterests: MutableList<String>
) {
    onRouteRestored(snapshot.response)
    onStartPointRestored(RouteStartDto(snapshot.request.start_lat, snapshot.request.start_lon))
    onAvailableMinutesRestored(snapshot.request.available_minutes)
    onPaceRestored(snapshot.request.pace)
    onReturnToStartRestored(snapshot.request.return_to_start)
    onRespectOpeningHoursRestored(snapshot.request.respect_opening_hours)
    onStartDateTimeRestored(parseRouteStartDateTime(snapshot.request.start_datetime))

    selectedInterests.clear()
    selectedInterests.addAll(snapshot.request.interests)
}

@Composable
private fun categoryLabel(category: String): String =
    when (category) {
        "attraction" -> stringResource(R.string.category_attraction)
        "museum" -> stringResource(R.string.category_museum)
        "gallery" -> stringResource(R.string.category_gallery)
        "viewpoint" -> stringResource(R.string.category_viewpoint)
        "monument" -> stringResource(R.string.category_monument)
        "historical_site" -> stringResource(R.string.category_historical_site)
        "park" -> stringResource(R.string.category_park)
        "religious_site" -> stringResource(R.string.category_religious_site)
        else -> category.toDisplayLabel()
    }


@Composable
private fun paceLabel(pace: String): String =
    when (pace) {
        "slow" -> stringResource(R.string.pace_slow)
        "normal" -> stringResource(R.string.pace_normal)
        "fast" -> stringResource(R.string.pace_fast)
        else -> pace.toDisplayLabel()
    }

@Composable
private fun routeSessionStatusLabel(status: RouteSessionStatus): String =
    when (status) {
        RouteSessionStatus.NOT_STARTED -> stringResource(R.string.route_tracking_state_ready)
        RouteSessionStatus.IN_PROGRESS -> stringResource(R.string.route_tracking_state_active)
        RouteSessionStatus.PAUSED -> stringResource(R.string.route_tracking_state_paused)
        RouteSessionStatus.COMPLETED -> stringResource(R.string.route_tracking_state_finished)
        RouteSessionStatus.CANCELLED -> stringResource(R.string.route_tracking_state_cancelled)
    }

private fun routeProgressMetrics(
    routeResponse: RouteResponse?,
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    isTracking: Boolean
): RouteProgressMetrics {
    val visitedIds = visitedPoiIds.distinct()
    val nextTarget = nextUnvisitedPoi(routeItems, visitedIds)
    val distanceToNextTargetMeters = if (currentLocation != null && nextTarget != null) {
        distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
    } else {
        null
    }
    val totalCount = progressTotalCount(routeItems, visitedIds)
    val visitedCount = visitedIds.size.coerceAtMost(totalCount)
    val isOffRoute = isTracking &&
        currentLocation != null &&
        nextTarget != null &&
        distanceToNextRouteSegmentMeters(routeResponse, nextTarget.poi_id, currentLocation) > OffRouteDistanceMeters

    return RouteProgressMetrics(
        visitedCount = visitedCount,
        totalCount = totalCount,
        nextTarget = nextTarget,
        distanceToNextTargetMeters = distanceToNextTargetMeters,
        estimatedRemainingMinutes = estimateRemainingMinutes(
            routeResponse = routeResponse,
            routeItems = routeItems,
            visitedPoiIds = visitedIds,
            currentLocation = currentLocation,
            nextTarget = nextTarget
        ),
        isOffRoute = isOffRoute,
        canComplete = totalCount > 0 && visitedCount >= requiredCompletionCount(totalCount)
    )
}

private fun progressTotalCount(
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>
): Int =
    visitedPoiIds.distinct().size + routeItems.count { item -> item.poi_id !in visitedPoiIds }

private fun requiredCompletionCount(totalCount: Int): Int =
    ceil(totalCount / 2.0).toInt().coerceAtLeast(1)

private fun nextUnvisitedPoi(
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>
): RouteItemDto? =
    routeItems.firstOrNull { item -> item.poi_id !in visitedPoiIds }

private fun estimateRemainingMinutes(
    routeResponse: RouteResponse?,
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    nextTarget: RouteItemDto?
): Int {
    val remainingItems = routeItems.filter { item -> item.poi_id !in visitedPoiIds }
    if (remainingItems.isEmpty()) {
        return 0
    }

    val firstWalkMinutes = if (currentLocation != null && nextTarget != null) {
        val distanceMeters = distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
        estimateWalkingMinutes(distanceMeters, routeResponse?.pace)
    } else {
        remainingItems.first().travel_minutes_from_previous
    }
    val remainingVisits = remainingItems.sumOf { item -> item.visit_duration_min }
    val remainingWalksAfterTarget = remainingItems.drop(1).sumOf { item ->
        item.travel_minutes_from_previous
    }
    val returnToStartMinutes = if (routeResponse?.return_to_start == true) {
        routeResponse.return_to_start_minutes
    } else {
        0
    }

    return firstWalkMinutes + remainingVisits + remainingWalksAfterTarget + returnToStartMinutes
}

private fun estimateWalkingMinutes(
    distanceMeters: Float,
    pace: String?
): Int {
    val speedMetersPerMinute = when (pace) {
        "slow" -> 4_000.0 / 60.0
        "fast" -> 5_600.0 / 60.0
        else -> 4_800.0 / 60.0
    }
    return maxOf(1, ceil(distanceMeters / speedMetersPerMinute).toInt())
}

private fun distanceToNextRouteSegmentMeters(
    routeResponse: RouteResponse?,
    nextTargetPoiId: Int,
    currentLocation: RouteStartDto
): Float {
    val legGeometry = routeResponse
        ?.legs
        .orEmpty()
        .firstOrNull { leg -> leg.to.poi_id == nextTargetPoiId }
        ?.geometry
        .orEmpty()

    if (legGeometry.size < 2) {
        val nextTarget = routeResponse
            ?.route
            .orEmpty()
            .firstOrNull { item -> item.poi_id == nextTargetPoiId }
            ?: return 0f

        return distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
    }

    return legGeometry
        .zipWithNext()
        .minOf { (start, end) ->
            distanceToSegmentMeters(
                point = currentLocation,
                startLat = start.lat,
                startLon = start.lon,
                endLat = end.lat,
                endLon = end.lon
            )
        }
}

private fun distanceToSegmentMeters(
    point: RouteStartDto,
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double
): Float {
    val segmentDistance = distanceMeters(startLat, startLon, endLat, endLon)
    if (segmentDistance == 0f) {
        return distanceMeters(point.lat, point.lon, startLat, startLon)
    }

    val referenceLatRadians = Math.toRadians(point.lat)
    val startX = longitudeToMeters(startLon - point.lon, referenceLatRadians)
    val startY = latitudeToMeters(startLat - point.lat)
    val endX = longitudeToMeters(endLon - point.lon, referenceLatRadians)
    val endY = latitudeToMeters(endLat - point.lat)
    val segmentX = endX - startX
    val segmentY = endY - startY
    val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY
    val projectionRatio = if (segmentLengthSquared == 0.0) {
        0.0
    } else {
        ((-startX * segmentX + -startY * segmentY) / segmentLengthSquared).coerceIn(0.0, 1.0)
    }
    val projectedX = startX + segmentX * projectionRatio
    val projectedY = startY + segmentY * projectionRatio

    return kotlin.math.sqrt(projectedX * projectedX + projectedY * projectedY).toFloat()
}

private fun latitudeToMeters(latitudeDelta: Double): Double =
    latitudeDelta * 111_320.0

private fun longitudeToMeters(
    longitudeDelta: Double,
    referenceLatRadians: Double
): Double =
    longitudeDelta * 111_320.0 * kotlin.math.cos(referenceLatRadians)

private fun fetchCurrentLocation(
    context: Context,
    onSuccess: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError(context.getString(R.string.error_location_service_unavailable))
            return
        }

        val hasFinePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePermission && !hasCoarsePermission) {
            onError(context.getString(R.string.error_location_permission_missing))
            return
        }

        val enabledProviders = buildList {
            if (hasFinePermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }.distinct()

        val fallbackProviders = buildList {
            addAll(enabledProviders)
            if (hasFinePermission) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }.distinct()

        val lastKnownLocation = fallbackProviders
            .mapNotNull { provider ->
                runCatching {
                    locationManager.getLastKnownLocation(provider)
                }.getOrNull()
            }
            .maxByOrNull { it.time }

        val provider = enabledProviders.firstOrNull()
        if (provider == null) {
            if (lastKnownLocation != null) {
                onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
            } else {
                onError(context.getString(R.string.error_no_location_provider_enabled))
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                when {
                    location != null -> onSuccess(location.latitude, location.longitude)
                    lastKnownLocation != null -> onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    else -> onError(context.getString(R.string.error_current_location_unavailable))
                }
            }
            return
        }

        @Suppress("DEPRECATION")
        locationManager.requestSingleUpdate(
            provider,
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onSuccess(location.latitude, location.longitude)
                    locationManager.removeUpdates(this)
                }

                override fun onProviderDisabled(provider: String) {
                    if (lastKnownLocation != null) {
                        onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    } else {
                        onError(context.getString(R.string.error_no_location_provider_enabled))
                    }
                    locationManager.removeUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    } catch (securityException: SecurityException) {
        onError(context.getString(R.string.error_location_permission_missing))
    }
}

private fun startRouteLocationTracking(
    context: Context,
    onLocation: (Location) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError(context.getString(R.string.error_location_service_unavailable))
            return {}
        }

        val hasFinePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePermission && !hasCoarsePermission) {
            onError(context.getString(R.string.error_location_permission_missing))
            return {}
        }

        val providers = buildList {
            if (hasFinePermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }.distinct()

        if (providers.isEmpty()) {
            onError(context.getString(R.string.error_no_location_provider_enabled))
            return {}
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location)
            }

            override fun onProviderDisabled(provider: String) {
                val hasEnabledProvider = providers.any { enabledProvider ->
                    runCatching {
                        locationManager.isProviderEnabled(enabledProvider)
                    }.getOrDefault(false)
                }

                if (!hasEnabledProvider) {
                    onError(context.getString(R.string.error_no_location_provider_enabled))
                }
            }
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                RouteTrackingMinTimeMs,
                RouteTrackingMinDistanceMeters,
                listener,
                Looper.getMainLooper()
            )
        }

        providers
            .mapNotNull { provider ->
                runCatching {
                    locationManager.getLastKnownLocation(provider)
                }.getOrNull()
            }
            .maxByOrNull { location -> location.time }
            ?.let(onLocation)

        val stopTracking = {
            locationManager.removeUpdates(listener)
        }

        return stopTracking
    } catch (securityException: SecurityException) {
        onError(context.getString(R.string.error_location_permission_missing))
        return {}
    }
}

private fun markNearbyPoisVisited(
    routeItems: List<RouteItemDto>,
    currentLocation: RouteStartDto,
    visitedPoiIds: MutableList<Int>
): Boolean {
    val newlyVisitedIds = routeItems
        .filter { item -> item.poi_id !in visitedPoiIds }
        .filter { item ->
            distanceMeters(
                startLat = currentLocation.lat,
                startLon = currentLocation.lon,
                endLat = item.lat,
                endLon = item.lon
            ) <= PoiVisitedRadiusMeters
        }
        .map { item -> item.poi_id }

    visitedPoiIds.addAll(newlyVisitedIds)
    return newlyVisitedIds.isNotEmpty()
}

private fun distanceMeters(
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double
): Float {
    val result = FloatArray(1)
    Location.distanceBetween(startLat, startLon, endLat, endLon, result)
    return result[0]
}

private fun defaultRouteStartDateTime(): LocalDateTime =
    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)

private fun parseRouteStartDateTime(rawValue: String?): LocalDateTime =
    runCatching {
        rawValue?.let(LocalDateTime::parse)?.truncatedTo(ChronoUnit.MINUTES) ?: defaultRouteStartDateTime()
    }.getOrElse {
        defaultRouteStartDateTime()
    }

private fun String.toDisplayLabel(): String =
    split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

private fun String?.toRouteDateTimeLabel(unknownLabel: String): String =
    runCatching {
        this?.let(LocalDateTime::parse)?.format(RouteTimeFormatter)
    }.getOrNull() ?: unknownLabel

private fun Throwable.toUserMessage(defaultMessage: String): String {
    val rawMessage = message?.substringBefore('\n')?.trim()
    return if (rawMessage.isNullOrEmpty()) defaultMessage else rawMessage
}

private fun formatDistanceMeters(distanceMeters: Float): String =
    if (distanceMeters >= 1000f) {
        String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1000f)
    } else {
        String.format(Locale.getDefault(), "%.0f m", distanceMeters)
    }

private fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)
