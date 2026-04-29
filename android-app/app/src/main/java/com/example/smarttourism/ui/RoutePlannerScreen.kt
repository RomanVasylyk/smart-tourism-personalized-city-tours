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
import com.example.smarttourism.data.CityDto
import com.example.smarttourism.data.NetworkMonitor
import com.example.smarttourism.data.OfflineCacheRepository
import com.example.smarttourism.data.PoiDto
import com.example.smarttourism.data.RouteFeedback
import com.example.smarttourism.data.RouteFeedbackRequest
import com.example.smarttourism.data.RouteLegDto
import com.example.smarttourism.data.RouteRequest
import com.example.smarttourism.data.RouteResponse
import com.example.smarttourism.data.RouteSegmentDto
import com.example.smarttourism.data.RouteSessionDto
import com.example.smarttourism.data.RouteSessionCreateRequest
import com.example.smarttourism.data.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.RouteStartDto
import com.example.smarttourism.data.RouteStorage
import com.example.smarttourism.data.RouteItemDto
import com.example.smarttourism.data.SavedRouteSnapshot
import com.example.smarttourism.data.sync.OfflineSyncScheduler
import com.example.smarttourism.offline.OfflineCityRegion
import com.example.smarttourism.offline.OfflineMapManager
import com.example.smarttourism.offline.OfflineStoredRegion
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

private const val DefaultCitySlug = "nitra"
private val EmptyStartPoint = RouteStartDto(lat = 48.3076, lon = 18.0845)
private val AvailableMinutesOptions = listOf(120, 180, 240)
private val DefaultInterestCategories = listOf(
    "attraction",
    "museum",
    "gallery",
    "viewpoint",
    "monument",
    "historical_site",
    "park",
    "religious_site"
)
private val PaceOptions = listOf("slow", "normal", "fast")
private val RouteTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.getDefault())
private val RouteClockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private const val RouteTrackingMinTimeMs = 5_000L
private const val RouteTrackingMinDistanceMeters = 5f
private const val PoiVisitedRadiusMeters = 60f
private const val OffRouteDistanceMeters = 120f
private const val OffRouteSustainDurationMs = 10_000L
private const val AutoRerouteCooldownMs = 20_000L

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

private data class OfflineDownloadProgress(
    val completed: Long,
    val required: Long,
    val percent: Double
)

@Composable
fun RoutePlannerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val deviceId = remember(context) { RouteStorage.getOrCreateDeviceId(context) }
    val offlineMapManager = remember(context) { OfflineMapManager(context.applicationContext) }
    val locationPermissionDeniedMessage = stringResource(R.string.error_location_permission_denied)
    val poiPreviewFailedMessage = stringResource(R.string.error_poi_preview_failed)
    val routeGenerationFailedMessage = stringResource(R.string.error_route_generation_failed_default)
    val offlineCitiesFallbackMessage = stringResource(R.string.offline_cities_cache_used)
    val offlinePoisFallbackMessage = stringResource(R.string.offline_pois_cache_used)
    val offlineRouteGenerationMessage = stringResource(R.string.offline_route_generation_unavailable)
    val offlineMapDownloadFailedMessage = stringResource(R.string.offline_map_download_failed)
    val offlineMapDeleteFailedMessage = stringResource(R.string.offline_map_delete_failed)
    val offlineMapDownloadedMessage = stringResource(R.string.offline_map_download_complete)
    val offlineMapDeletedMessage = stringResource(R.string.offline_map_delete_complete)
    val pendingSyncQueuedMessage = stringResource(R.string.pending_sync_queued)

    var cities by remember { mutableStateOf<List<CityDto>>(emptyList()) }
    var selectedCity by remember { mutableStateOf<CityDto?>(null) }
    var pois by remember { mutableStateOf<List<PoiDto>>(emptyList()) }
    var isPoiLoading by remember { mutableStateOf(true) }
    var poiError by remember { mutableStateOf<String?>(null) }
    var offlineStatusMessage by remember { mutableStateOf<String?>(null) }
    var pendingSyncOperationCount by remember { mutableIntStateOf(0) }
    var offlineStoredRegion by remember { mutableStateOf<OfflineStoredRegion?>(null) }
    var isOfflineMapBusy by remember { mutableStateOf(false) }
    var offlineMapProgress by remember { mutableStateOf<OfflineDownloadProgress?>(null) }
    var offlineMapMessage by remember { mutableStateOf<String?>(null) }

    var routeResponse by remember { mutableStateOf<RouteResponse?>(null) }
    var currentRouteRequest by remember { mutableStateOf<RouteRequest?>(null) }
    var isRouteLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var isRerouting by remember { mutableStateOf(false) }

    var availableMinutes by remember { mutableIntStateOf(180) }
    var pace by remember { mutableStateOf("normal") }
    var returnToStart by remember { mutableStateOf(true) }
    var respectOpeningHours by remember { mutableStateOf(true) }
    var allowPublicTransport by remember { mutableStateOf(false) }
    var startPoint by remember { mutableStateOf(EmptyStartPoint) }
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
    var isMapFullScreen by remember { mutableStateOf(false) }
    var offRouteDetectedAtMs by remember { mutableStateOf<Long?>(null) }
    var lastAutoRerouteAtMs by remember { mutableStateOf<Long?>(null) }

    val selectedInterests = remember { mutableStateListOf<String>() }
    val visitedPoiIds = remember { mutableStateListOf<Int>() }
    val skippedPoiIds = remember { mutableStateListOf<Int>() }
    val routeItems = routeResponse?.route.orEmpty()

    fun refreshPendingSyncOperationCount() {
        scope.launch {
            pendingSyncOperationCount = OfflineCacheRepository.getPendingSyncOperationCount(context)
        }
    }

    fun resetRouteSession(clearStoredSession: Boolean = true) {
        routeSessionStatus = RouteSessionStatus.NOT_STARTED
        routeId = null
        routeStartedAt = null
        currentTargetPoiId = null
        currentRouteLocation = null
        trackingError = null
        routeFeedback = null
        offRouteDetectedAtMs = null
        lastAutoRerouteAtMs = null
        visitedPoiIds.clear()
        skippedPoiIds.clear()
        if (clearStoredSession) {
            scope.launch {
                RouteStorage.clearActiveSession(context)
            }
        }
    }

    fun clearDisplayedRoute(cancelActiveSession: Boolean = true) {
        val snapshot = if (currentRouteRequest != null && routeResponse != null) {
            SavedRouteSnapshot(
                request = currentRouteRequest!!,
                response = routeResponse!!
            )
        } else {
            null
        }
        val activeRouteId = routeId
        val activeStatus = routeSessionStatus
        val activeStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()

        routeResponse = null
        currentRouteRequest = null
        routeError = null

        if (
            cancelActiveSession &&
            activeRouteId != null &&
            snapshot != null &&
            (activeStatus == RouteSessionStatus.IN_PROGRESS || activeStatus == RouteSessionStatus.PAUSED)
        ) {
            scope.launch {
                val response = snapshot.response
                OfflineCacheRepository.enqueuePendingRouteSession(
                    context = context,
                    request = RouteSessionCreateRequest(
                        id = activeRouteId,
                        device_id = deviceId,
                        city = response.city,
                        status = RouteSessionStatus.CANCELLED.rawValue,
                        start_lat = response.start.lat,
                        start_lon = response.start.lon,
                        available_minutes = response.available_minutes,
                        pace = response.pace,
                        return_to_start = response.return_to_start,
                        opening_hours_enabled = response.respect_opening_hours,
                        started_at = activeStartedAt,
                        finished_at = defaultRouteStartDateTime().toString(),
                        used_minutes = response.used_minutes,
                        total_walk_minutes = response.total_walk_minutes,
                        total_visit_minutes = response.total_visit_minutes,
                        route_snapshot_json = response
                    )
                )
                pendingSyncOperationCount = OfflineCacheRepository.getPendingSyncOperationCount(context)
                OfflineSyncScheduler.scheduleImmediate(context)
            }
        }

        resetRouteSession()
    }

    val updateStartPoint = { lat: Double, lon: Double ->
        startPoint = RouteStartDto(lat = lat, lon = lon)
        isSelectingStart = false
        isMapFullScreen = false
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

    fun buildRouteSessionSyncRequest(
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAtValue: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ): RouteSessionCreateRequest {
        val response = snapshot.response
        return RouteSessionCreateRequest(
            id = sessionRouteId,
            device_id = deviceId,
            city = response.city,
            status = status.rawValue,
            start_lat = response.start.lat,
            start_lon = response.start.lon,
            available_minutes = response.available_minutes,
            pace = response.pace,
            return_to_start = response.return_to_start,
            opening_hours_enabled = response.respect_opening_hours,
            started_at = startedAtValue,
            finished_at = finishedAt,
            used_minutes = response.used_minutes,
            total_walk_minutes = response.total_walk_minutes,
            total_visit_minutes = response.total_visit_minutes,
            route_snapshot_json = response
        )
    }

    suspend fun enqueueRouteSessionSync(
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAtValue: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ) {
        OfflineCacheRepository.enqueuePendingRouteSession(
            context = context,
            request = buildRouteSessionSyncRequest(
                sessionRouteId = sessionRouteId,
                status = status,
                startedAtValue = startedAtValue,
                snapshot = snapshot,
                finishedAt = finishedAt
            )
        )
        pendingSyncOperationCount = OfflineCacheRepository.getPendingSyncOperationCount(context)
        OfflineSyncScheduler.scheduleImmediate(context)
    }

    fun persistRouteSession(
        status: RouteSessionStatus = routeSessionStatus,
        routeIdValue: String? = routeId,
        startedAtValue: String? = routeStartedAt,
        visitedIds: List<Int> = visitedPoiIds.toList(),
        skippedIds: List<Int> = skippedPoiIds.toList(),
        feedback: RouteFeedback? = routeFeedback,
        snapshotOverride: SavedRouteSnapshot? = null
    ) {
        val snapshot = snapshotOverride ?: currentRouteSnapshot() ?: return
        val savedRouteId = routeIdValue ?: return
        val savedStartedAt = startedAtValue ?: defaultRouteStartDateTime().toString()
        val nextTargetId = nextPendingPoi(snapshot.response.route, visitedIds, skippedIds)?.poi_id
        val totalCount = progressTotalCount(snapshot.response.route, skippedIds)

        currentTargetPoiId = nextTargetId

        scope.launch {
            val finishedAt = if (status == RouteSessionStatus.COMPLETED || status == RouteSessionStatus.CANCELLED) {
                defaultRouteStartDateTime().toString()
            } else {
                null
            }

            RouteStorage.saveActiveSession(
                context = context,
                session = ActiveRouteSession(
                        route_id = savedRouteId,
                        status = status.rawValue,
                        started_at = savedStartedAt,
                        current_target_poi_id = nextTargetId,
                        visited_poi_ids = visitedIds,
                        skipped_poi_ids = skippedIds,
                        progress_visited_count = visitedIds.distinct().size,
                        progress_total_count = totalCount,
                        snapshot = snapshot,
                        feedback = feedback
                )
            )
            enqueueRouteSessionSync(
                sessionRouteId = savedRouteId,
                status = status,
                startedAtValue = savedStartedAt,
                snapshot = snapshot,
                finishedAt = finishedAt
            )
        }
    }

    fun setRouteStatus(status: RouteSessionStatus) {
        routeSessionStatus = status
        persistRouteSession(status = status)
    }

    fun syncVisitedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        scope.launch {
            poiIds.distinct().forEach { poiId ->
                OfflineCacheRepository.enqueuePendingPoiVisit(
                    context = context,
                    sessionId = sessionRouteId,
                    poiId = poiId,
                    request = RouteSessionPoiVisitRequest(
                        visited_at = defaultRouteStartDateTime().toString(),
                        skipped = false
                    )
                )
            }
            pendingSyncOperationCount = OfflineCacheRepository.getPendingSyncOperationCount(context)
            OfflineSyncScheduler.scheduleImmediate(context)
        }
    }

    fun syncSkippedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        scope.launch {
            poiIds.distinct().forEach { poiId ->
                OfflineCacheRepository.enqueuePendingPoiVisit(
                    context = context,
                    sessionId = sessionRouteId,
                    poiId = poiId,
                    request = RouteSessionPoiVisitRequest(
                        visited_at = defaultRouteStartDateTime().toString(),
                        skipped = true
                    )
                )
            }
            pendingSyncOperationCount = OfflineCacheRepository.getPendingSyncOperationCount(context)
            OfflineSyncScheduler.scheduleImmediate(context)
        }
    }

    fun syncFeedbackToBackend(feedback: RouteFeedback) {
        val sessionRouteId = routeId ?: return
        if (feedback.rating !in 1..5) {
            return
        }

        val feedbackRequest = RouteFeedbackRequest(
            rating = feedback.rating,
            was_convenient = feedback.route_was_comfortable,
            too_much_walking = feedback.too_much_walking,
            pois_were_interesting = feedback.pois_were_interesting,
            comment = null
        )

        scope.launch {
            OfflineCacheRepository.enqueuePendingFeedback(
                context = context,
                sessionId = sessionRouteId,
                request = feedbackRequest
            )
            pendingSyncOperationCount = OfflineCacheRepository.getPendingSyncOperationCount(context)
            OfflineSyncScheduler.scheduleImmediate(context)
            offlineStatusMessage = if (NetworkMonitor.isNetworkAvailable(context)) {
                null
            } else {
                pendingSyncQueuedMessage
            }
        }
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
            skippedPoiIds.clear()
            routeFeedback = null
        }

        val activeRouteId = routeId ?: UUID.randomUUID().toString()
        val activeStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()

        routeId = activeRouteId
        routeStartedAt = activeStartedAt
        currentTargetPoiId = nextPendingPoi(response!!.route, visitedPoiIds, skippedPoiIds)?.poi_id
        trackingError = null
        offRouteDetectedAtMs = null
        lastAutoRerouteAtMs = null
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
        var restoredCityToken = savedSnapshot?.request?.city
        pendingSyncOperationCount = OfflineCacheRepository.getPendingSyncOperationCount(context)

        savedSnapshot?.let { snapshot ->
            currentRouteRequest = snapshot.request.copy(
                transport_mode = snapshot.request.transport_mode ?: "walk"
            )
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
                onAllowPublicTransportRestored = { restoredAllowPublicTransport ->
                    allowPublicTransport = restoredAllowPublicTransport
                },
                onStartDateTimeRestored = { restoredStartDateTime -> startDateTime = restoredStartDateTime },
                selectedInterests = selectedInterests
            )
        }

        if (activeSession != null) {
            routeId = activeSession.route_id
            routeStartedAt = activeSession.started_at
            routeSessionStatus = RouteSessionStatus.fromRawValue(activeSession.status)
            routeFeedback = activeSession.feedback
            visitedPoiIds.clear()
            visitedPoiIds.addAll(activeSession.visited_poi_ids.distinct())
            skippedPoiIds.clear()
            skippedPoiIds.addAll(activeSession.skipped_poi_ids.orEmpty().distinct())
            currentTargetPoiId = nextPendingPoi(
                activeSession.snapshot.response.route,
                visitedPoiIds,
                skippedPoiIds
            )?.poi_id ?: activeSession.current_target_poi_id
        }
        OfflineSyncScheduler.scheduleImmediate(context)

        runCatching {
            val remoteSession = if (routeId != null && routeSessionStatus.isRestorable()) {
                ApiModule.poiApi.getRouteSession(routeId!!)
            } else {
                ApiModule.poiApi.getRouteSessions(deviceId)
                    .firstOrNull { session ->
                        RouteSessionStatus.fromRawValue(session.status).isRestorable()
                    }
            }

            if (remoteSession != null) {
                remoteSession.toSavedRouteSnapshot()?.let { snapshot ->
                    restoredCityToken = snapshot.request.city
                    currentRouteRequest = snapshot.request.copy(
                        transport_mode = snapshot.request.transport_mode ?: "walk"
                    )
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
                        onAllowPublicTransportRestored = { restoredAllowPublicTransport ->
                            allowPublicTransport = restoredAllowPublicTransport
                        },
                        onStartDateTimeRestored = { restoredStartDateTime -> startDateTime = restoredStartDateTime },
                        selectedInterests = selectedInterests
                    )
                    routeId = remoteSession.id
                    routeStartedAt = remoteSession.started_at
                    routeSessionStatus = RouteSessionStatus.fromRawValue(remoteSession.status)
                    routeFeedback = remoteSession.toRouteFeedback()
                    visitedPoiIds.clear()
                    visitedPoiIds.addAll(
                        remoteSession.pois
                            .orEmpty()
                            .filter { poi -> poi.visited && !poi.skipped }
                            .map { poi -> poi.poi_id }
                            .distinct()
                    )
                    skippedPoiIds.clear()
                    skippedPoiIds.addAll(
                        remoteSession.pois
                            .orEmpty()
                            .filter { poi -> poi.skipped }
                            .map { poi -> poi.poi_id }
                            .distinct()
                    )
                    currentTargetPoiId = nextPendingPoi(snapshot.response.route, visitedPoiIds, skippedPoiIds)?.poi_id
                    RouteStorage.save(
                        context = context,
                        snapshot = snapshot
                    )
                    RouteStorage.saveActiveSession(
                        context = context,
                        session = ActiveRouteSession(
                            route_id = remoteSession.id,
                            status = remoteSession.status,
                            started_at = remoteSession.started_at,
                            current_target_poi_id = currentTargetPoiId,
                            visited_poi_ids = visitedPoiIds.toList(),
                            skipped_poi_ids = skippedPoiIds.toList(),
                            progress_visited_count = visitedPoiIds.distinct().size,
                            progress_total_count = progressTotalCount(snapshot.response.route, skippedPoiIds),
                            snapshot = snapshot,
                            feedback = routeFeedback
                        )
                    )
                }
            }
        }

        try {
            val remoteCities = ApiModule.poiApi.getCities()
            OfflineCacheRepository.cacheCities(context, remoteCities)
            cities = remoteCities
            offlineStatusMessage = null
            refreshPendingSyncOperationCount()
            selectedCity = cities.firstOrNull { city -> city.matchesToken(restoredCityToken) }
                ?: cities.firstOrNull { city -> city.matchesToken(DefaultCitySlug) }
                ?: cities.firstOrNull()
            if (routeResponse == null) {
                selectedCity?.let { city ->
                    startPoint = city.toStartPoint()
                }
            }
        } catch (_: Exception) {
            cities = OfflineCacheRepository.getCachedCities(context)
            selectedCity = cities.firstOrNull { city -> city.matchesToken(restoredCityToken) }
                ?: cities.firstOrNull { city -> city.matchesToken(DefaultCitySlug) }
                ?: cities.firstOrNull()
            if (routeResponse == null) {
                selectedCity?.let { city ->
                    startPoint = city.toStartPoint()
                }
            }
            offlineStatusMessage = if (cities.isNotEmpty()) offlineCitiesFallbackMessage else null
        }
    }

    LaunchedEffect(selectedCity?.slug) {
        val city = selectedCity ?: return@LaunchedEffect
        isPoiLoading = true
        offlineMapProgress = null

        if (selectedInterests.isEmpty()) {
            selectedInterests.addAll(city.availableCategories())
        } else {
            val allowedCategories = city.availableCategories().toSet()
            val filteredInterests = selectedInterests.filter { interest -> interest in allowedCategories }
            selectedInterests.clear()
            selectedInterests.addAll(filteredInterests.ifEmpty { city.availableCategories() })
        }

        if (routeResponse == null) {
            startPoint = city.toStartPoint()
        }

        if (!city.supportsPublicTransport()) {
            allowPublicTransport = false
        }

        try {
            val remotePois = ApiModule.poiApi.getPois(city.slug)
            OfflineCacheRepository.cachePois(context, city.slug, remotePois)
            pois = remotePois
            poiError = null
            offlineStatusMessage = null
            refreshPendingSyncOperationCount()
        } catch (e: Exception) {
            val cachedPois = OfflineCacheRepository.getCachedPois(context, city.slug)
            pois = cachedPois
            poiError = if (cachedPois.isEmpty()) {
                e.toUserMessage(poiPreviewFailedMessage)
            } else {
                null
            }
            offlineStatusMessage = if (cachedPois.isNotEmpty()) {
                String.format(Locale.getDefault(), offlinePoisFallbackMessage, city.name)
            } else {
                offlineStatusMessage
            }
        } finally {
            isPoiLoading = false
        }

        offlineStoredRegion = offlineMapManager.findRegionBySlug(city.slug)
    }

    val recalculateRouteFromPoint: (RouteStartDto, Boolean, List<Int>) -> Unit = reroute@ { currentLocation, autoTriggered, additionalExcludedPoiIds ->
        val baseRequest = currentRouteRequest ?: return@reroute
        val response = routeResponse ?: return@reroute

        scope.launch {
            isRerouting = true
            routeError = null
            offRouteDetectedAtMs = null

            val effectiveSkippedPoiIds = (skippedPoiIds + additionalExcludedPoiIds).distinct()
            val nextTarget = nextPendingPoi(routeItems, visitedPoiIds, effectiveSkippedPoiIds)
            val remainingMinutes = estimateRemainingMinutes(
                routeResponse = response,
                routeItems = routeItems,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = effectiveSkippedPoiIds,
                currentLocation = currentLocation,
                nextTarget = nextTarget
            )
                .coerceIn(30, maxOf(30, baseRequest.available_minutes))
            val request = baseRequest.copy(
                start_lat = currentLocation.lat,
                start_lon = currentLocation.lon,
                available_minutes = remainingMinutes,
                start_datetime = defaultRouteStartDateTime().toString(),
                exclude_poi_ids = (visitedPoiIds + effectiveSkippedPoiIds).distinct(),
                transport_mode = baseRequest.transport_mode ?: "walk"
            )

            try {
                val generatedRoute = ApiModule.poiApi.generateRoute(request)
                val mergedRoute = mergeReroutedRouteResponse(
                    previousResponse = response,
                    reroutedResponse = generatedRoute,
                    visitedPoiIds = visitedPoiIds
                )
                val snapshot = SavedRouteSnapshot(
                    request = request,
                    response = mergedRoute
                )

                currentRouteRequest = request
                routeResponse = mergedRoute
                startPoint = RouteStartDto(currentLocation.lat, currentLocation.lon)
                currentTargetPoiId = nextPendingPoi(mergedRoute.route, visitedPoiIds, effectiveSkippedPoiIds)?.poi_id
                val updatedStatus = if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS) {
                    RouteSessionStatus.IN_PROGRESS
                } else {
                    routeSessionStatus
                }
                routeSessionStatus = updatedStatus
                RouteStorage.save(context = context, snapshot = snapshot)
                persistRouteSession(
                    status = updatedStatus,
                    snapshotOverride = snapshot
                )
            } catch (e: Exception) {
                routeError = e.toUserMessage(routeGenerationFailedMessage)
                routeResponse = response
            } finally {
                if (autoTriggered) {
                    lastAutoRerouteAtMs = System.currentTimeMillis()
                }
                isRerouting = false
            }
        }
    }

    fun skipRouteStop(poiId: Int) {
        if (poiId in visitedPoiIds || poiId in skippedPoiIds) {
            return
        }

        skippedPoiIds.add(poiId)
        currentRouteRequest = currentRouteRequest?.copy(
            exclude_poi_ids = ((currentRouteRequest?.exclude_poi_ids).orEmpty() + poiId).distinct()
        )
        currentTargetPoiId = nextPendingPoi(routeItems, visitedPoiIds, skippedPoiIds)?.poi_id
        currentRouteSnapshot()?.let { snapshot ->
            scope.launch {
                RouteStorage.save(context = context, snapshot = snapshot)
            }
        }
        persistRouteSession(status = routeSessionStatus)
        syncSkippedPoisToBackend(listOf(poiId))

        if (routeResponse == null || currentRouteRequest == null || !NetworkMonitor.isNetworkAvailable(context)) {
            return
        }

        val rerouteStart = rerouteStartPoint(
            routeItems = routeItems,
            visitedPoiIds = visitedPoiIds,
            currentLocation = currentRouteLocation,
            fallbackStart = startPoint
        )
        recalculateRouteFromPoint(rerouteStart, false, listOf(poiId))
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
                    val newlyVisitedPoiIds = markNearbyPoisVisited(
                        routeItems = routeItems,
                        currentLocation = routeLocation,
                        visitedPoiIds = visitedPoiIds,
                        skippedPoiIds = skippedPoiIds
                    )
                    val nextTarget = nextPendingPoi(routeItems, visitedPoiIds, skippedPoiIds)
                    currentTargetPoiId = nextTarget?.poi_id

                    val isCurrentlyOffRoute = routeResponse != null &&
                        nextTarget != null &&
                        distanceToNextRouteSegmentMeters(routeResponse, nextTarget.poi_id, routeLocation) > OffRouteDistanceMeters

                    if (isCurrentlyOffRoute) {
                        val now = System.currentTimeMillis()
                        val detectedAt = offRouteDetectedAtMs ?: now.also { offRouteDetectedAtMs = it }
                        val rerouteCooldownReady = lastAutoRerouteAtMs == null ||
                            now - (lastAutoRerouteAtMs ?: 0L) >= AutoRerouteCooldownMs
                        val rerouteSustained = now - detectedAt >= OffRouteSustainDurationMs

                        if (!isRerouting && rerouteCooldownReady && rerouteSustained) {
                            recalculateRouteFromPoint(routeLocation, true, emptyList())
                        }
                    } else {
                        offRouteDetectedAtMs = null
                    }

                    if (routeItems.isNotEmpty() && routeItems.all { item -> item.poi_id in visitedPoiIds }) {
                        routeSessionStatus = RouteSessionStatus.COMPLETED
                        persistRouteSession(status = RouteSessionStatus.COMPLETED)
                        syncVisitedPoisToBackend(newlyVisitedPoiIds)
                    } else if (newlyVisitedPoiIds.isNotEmpty()) {
                        persistRouteSession(status = RouteSessionStatus.IN_PROGRESS)
                        syncVisitedPoisToBackend(newlyVisitedPoiIds)
                    }
                },
                onError = { message ->
                    routeSessionStatus = RouteSessionStatus.PAUSED
                    trackingError = message
                    offRouteDetectedAtMs = null
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
        skippedPoiIds = skippedPoiIds,
        currentLocation = currentRouteLocation,
        isTracking = routeSessionStatus == RouteSessionStatus.IN_PROGRESS
    )
    val selectedCityAvailableCategories = selectedCity?.availableCategories().orEmpty()
    val isPublicTransportAvailable = selectedCity?.supportsPublicTransport() == true

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
            offRouteDetectedAtMs = null
            lastAutoRerouteAtMs = null
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
        syncFeedbackToBackend(feedback)
    }

    fun recalculateFromCurrentLocation() {
        val currentLocation = currentRouteLocation ?: return
        recalculateRouteFromPoint(currentLocation, false, emptyList())
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
                    selectedCity = city
                    currentRouteRequest = null
                    clearDisplayedRoute()
                    startPoint = city.toStartPoint()
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
                onDownloadOfflineMap = {
                    val city = selectedCity ?: return@OfflineSupportCard
                    val offlineRegion = city.toOfflineCityRegion() ?: run {
                        offlineMapMessage = null
                        return@OfflineSupportCard
                    }
                    isOfflineMapBusy = true
                    offlineMapProgress = OfflineDownloadProgress(0, 0, 0.0)
                    offlineMapMessage = null
                    offlineMapManager.downloadCityRegion(
                        city = offlineRegion,
                        onProgress = { completed, required, percent ->
                            offlineMapProgress = OfflineDownloadProgress(
                                completed = completed,
                                required = required,
                                percent = percent
                            )
                        },
                        onComplete = {
                            isOfflineMapBusy = false
                            offlineMapMessage = String.format(
                                Locale.getDefault(),
                                offlineMapDownloadedMessage,
                                city.name
                            )
                            scope.launch {
                                offlineStoredRegion = offlineMapManager.findRegionBySlug(city.slug)
                            }
                        },
                        onError = { error ->
                            isOfflineMapBusy = false
                            offlineMapMessage = "$offlineMapDownloadFailedMessage $error"
                        }
                    )
                },
                onDeleteOfflineMap = {
                    val city = selectedCity ?: return@OfflineSupportCard
                    val storedRegion = offlineStoredRegion ?: return@OfflineSupportCard
                    isOfflineMapBusy = true
                    offlineMapManager.deleteRegion(
                        region = storedRegion.region,
                        onComplete = {
                            isOfflineMapBusy = false
                            offlineMapProgress = null
                            offlineStoredRegion = null
                            offlineMapMessage = String.format(
                                Locale.getDefault(),
                                offlineMapDeletedMessage,
                                city.name
                            )
                        },
                        onError = { error ->
                            isOfflineMapBusy = false
                            offlineMapMessage = "$offlineMapDeleteFailedMessage $error"
                        }
                    )
                }
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
                    onStartPointSelected = updateStartPoint,
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
                availableInterests = selectedCityAvailableCategories,
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
                isPublicTransportAvailable = isPublicTransportAvailable,
                allowPublicTransport = allowPublicTransport,
                onAllowPublicTransportChange = {
                    allowPublicTransport = it
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
                        if (!NetworkMonitor.isNetworkAvailable(context)) {
                            routeError = offlineRouteGenerationMessage
                            return@launch
                        }
                        isRouteLoading = true
                        routeError = null
                        val existingSnapshot = currentRouteSnapshot()
                        val existingRouteId = routeId
                        val existingStatus = routeSessionStatus
                        val existingStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()

                        val request = RouteRequest(
                            city = selectedCity?.slug ?: DefaultCitySlug,
                            start_lat = startPoint.lat,
                            start_lon = startPoint.lon,
                            available_minutes = availableMinutes,
                            interests = selectedInterests.toList(),
                            pace = pace,
                            return_to_start = returnToStart,
                            start_datetime = startDateTime.truncatedTo(ChronoUnit.MINUTES).toString(),
                            respect_opening_hours = respectOpeningHours,
                            transport_mode = if (allowPublicTransport && isPublicTransportAvailable) {
                                "walk_or_mhd"
                            } else {
                                "walk"
                            }
                        )

                        try {
                            val generatedRoute = ApiModule.poiApi.generateRoute(request)
                            if (
                                existingRouteId != null &&
                                existingSnapshot != null &&
                                existingStatus.isRestorable()
                            ) {
                                OfflineCacheRepository.enqueuePendingRouteSession(
                                    context = context,
                                    request = buildRouteSessionSyncRequest(
                                        sessionRouteId = existingRouteId,
                                        status = RouteSessionStatus.CANCELLED,
                                        startedAtValue = existingStartedAt,
                                        snapshot = existingSnapshot,
                                        finishedAt = defaultRouteStartDateTime().toString()
                                    )
                                )
                                OfflineSyncScheduler.scheduleImmediate(context)
                            }
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
                            offlineStatusMessage = null
                            refreshPendingSyncOperationCount()
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
                        onCancelRoute = { cancelRoute() }
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
                            incomingLeg = routeResponse?.legs.orEmpty().firstOrNull { leg -> leg.to.poi_id == item.poi_id },
                            isVisited = item.poi_id in visitedPoiIds,
                            isSkipped = item.poi_id in skippedPoiIds,
                            isRouteActive = routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                            canSkip = routeSessionStatus != RouteSessionStatus.COMPLETED &&
                                routeSessionStatus != RouteSessionStatus.CANCELLED,
                            isActionInProgress = isRerouting,
                            onMarkVisited = {
                                if (item.poi_id !in visitedPoiIds && item.poi_id !in skippedPoiIds) {
                                    visitedPoiIds.add(item.poi_id)
                                    syncVisitedPoisToBackend(listOf(item.poi_id))
                                    currentTargetPoiId = nextPendingPoi(routeItems, visitedPoiIds, skippedPoiIds)?.poi_id
                                    if (routeItems.all { routeItem -> routeItem.poi_id in visitedPoiIds }) {
                                        routeSessionStatus = RouteSessionStatus.COMPLETED
                                        persistRouteSession(status = RouteSessionStatus.COMPLETED)
                                    } else {
                                        persistRouteSession()
                                    }
                                }
                            },
                            onSkip = { skipRouteStop(item.poi_id) }
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
                    onStartPointSelected = updateStartPoint,
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

@Composable
private fun OfflineSupportCard(
    selectedCity: CityDto?,
    offlineStatusMessage: String?,
    pendingSyncOperationCount: Int,
    offlineRegionAvailable: Boolean,
    isOfflineMapBusy: Boolean,
    offlineMapProgress: OfflineDownloadProgress?,
    offlineMapMessage: String?,
    onDownloadOfflineMap: () -> Unit,
    onDeleteOfflineMap: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.offline_support_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.offline_support_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!offlineStatusMessage.isNullOrBlank()) {
                Text(
                    text = offlineStatusMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (pendingSyncOperationCount > 0) {
                Text(
                    text = stringResource(R.string.pending_sync_status, pendingSyncOperationCount),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val city = selectedCity
            if (city == null) {
                Text(
                    text = stringResource(R.string.offline_map_city_unavailable),
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }

            Text(
                text = if (offlineRegionAvailable) {
                    stringResource(R.string.offline_map_available, city.name)
                } else {
                    stringResource(R.string.offline_map_not_downloaded, city.name)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (city.bbox == null) {
                Text(
                    text = stringResource(R.string.offline_map_bbox_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (offlineRegionAvailable) {
                        OutlinedButton(
                            onClick = onDeleteOfflineMap,
                            enabled = !isOfflineMapBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_delete_offline_map))
                        }
                    } else {
                        Button(
                            onClick = onDownloadOfflineMap,
                            enabled = !isOfflineMapBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_download_offline_map))
                        }
                    }
                }
            }

            if (offlineMapProgress != null) {
                LinearProgressIndicator(
                    progress = { (offlineMapProgress.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.offline_map_progress,
                        offlineMapProgress.percent.toInt(),
                        offlineMapProgress.completed,
                        offlineMapProgress.required
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!offlineMapMessage.isNullOrBlank()) {
                Text(
                    text = offlineMapMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun CitySelectorCard(
    cities: List<CityDto>,
    selectedCity: CityDto?,
    onCitySelected: (CityDto) -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.city_selector_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.city_selector_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (cities.isEmpty()) {
                Text(
                    text = stringResource(R.string.city_selector_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                cities.forEach { city ->
                    SelectableRow(onClick = { onCitySelected(city) }) {
                        RadioButton(
                            selected = selectedCity?.id == city.id,
                            onClick = null
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(city.name)
                            Text(
                                text = city.country,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
    availableInterests: List<String>,
    selectedInterests: List<String>,
    onInterestToggle: (String, Boolean) -> Unit,
    pace: String,
    onPaceChange: (String) -> Unit,
    returnToStart: Boolean,
    onReturnToStartChange: (Boolean) -> Unit,
    respectOpeningHours: Boolean,
    onRespectOpeningHoursChange: (Boolean) -> Unit,
    isPublicTransportAvailable: Boolean,
    allowPublicTransport: Boolean,
    onAllowPublicTransportChange: (Boolean) -> Unit,
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
            if (availableInterests.isEmpty()) {
                Text(
                    text = stringResource(R.string.interests_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                availableInterests.forEach { interest ->
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

            if (isPublicTransportAvailable) {
                HorizontalDivider()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.allow_public_transport_label),
                            style = MaterialTheme.typography.labelLarge
                        )
                        Text(
                            text = stringResource(R.string.allow_public_transport_body),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = allowPublicTransport,
                        onCheckedChange = onAllowPublicTransportChange
                    )
                }
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
    onCancelRoute: () -> Unit
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
            if (metrics.isOffRoute || isRerouting) {
                Text(
                    text = stringResource(R.string.status_off_route_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (isRerouting) {
                        stringResource(R.string.status_off_route_rerouting_body)
                    } else {
                        stringResource(R.string.status_off_route_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isRerouting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.action_recalculating_route),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
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
    incomingLeg: RouteLegDto?,
    isVisited: Boolean,
    isSkipped: Boolean,
    isRouteActive: Boolean,
    canSkip: Boolean,
    isActionInProgress: Boolean,
    onMarkVisited: () -> Unit,
    onSkip: () -> Unit
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
                } else if (isSkipped) {
                    Text(
                        text = stringResource(R.string.route_stop_skipped),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (canSkip || isRouteActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canSkip) {
                            OutlinedButton(
                                onClick = onSkip,
                                enabled = !isActionInProgress
                            ) {
                                Text(stringResource(R.string.action_skip_stop))
                            }
                        }
                        if (isRouteActive) {
                            OutlinedButton(
                                onClick = onMarkVisited,
                                enabled = !isActionInProgress
                            ) {
                                Text(stringResource(R.string.action_mark_visited))
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.route_stop_walk_from_previous, item.travel_minutes_from_previous))
            incomingLeg?.segments
                .orEmpty()
                .filter { segment ->
                    val mode = segment.mode.orEmpty()
                    mode == "transit" || (segment.duration_minutes ?: 0) > 0
                }
                .forEach { segment ->
                    Text(
                        text = routeSegmentLabel(segment),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val fromStopName = segment.from_stop_name
                    val toStopName = segment.to_stop_name
                    if (segment.mode == "transit" && !fromStopName.isNullOrBlank() && !toStopName.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.route_stop_segment_stops, fromStopName, toStopName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val departureLabel = segment.departure_time.toRouteTimeOfDayLabel()
                    val arrivalLabel = segment.arrival_time.toRouteTimeOfDayLabel()
                    if (segment.mode == "transit" && departureLabel != null && arrivalLabel != null) {
                        Text(
                            text = stringResource(R.string.route_stop_segment_schedule, departureLabel, arrivalLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val waitMinutes = segment.wait_minutes_before_departure ?: 0
                    val inVehicleMinutes = segment.in_vehicle_minutes ?: 0
                    if (segment.mode == "transit" && (waitMinutes > 0 || inVehicleMinutes > 0)) {
                        Text(
                            text = stringResource(
                                R.string.route_stop_segment_wait_ride,
                                waitMinutes,
                                inVehicleMinutes
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
    onAllowPublicTransportRestored: (Boolean) -> Unit,
    onStartDateTimeRestored: (LocalDateTime) -> Unit,
    selectedInterests: MutableList<String>
) {
    onRouteRestored(snapshot.response)
    onStartPointRestored(RouteStartDto(snapshot.request.start_lat, snapshot.request.start_lon))
    onAvailableMinutesRestored(snapshot.request.available_minutes)
    onPaceRestored(snapshot.request.pace)
    onReturnToStartRestored(snapshot.request.return_to_start)
    onRespectOpeningHoursRestored(snapshot.request.respect_opening_hours)
    onAllowPublicTransportRestored(snapshot.request.transport_mode == "walk_or_mhd")
    onStartDateTimeRestored(parseRouteStartDateTime(snapshot.request.start_datetime))

    selectedInterests.clear()
    selectedInterests.addAll(snapshot.request.interests)
}

private fun RouteSessionDto.toSavedRouteSnapshot(): SavedRouteSnapshot? {
    val response = route_snapshot_json ?: return null
    val skippedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.skipped }
        .map { poi -> poi.poi_id }
        .distinct()
    val request = RouteRequest(
        city = city_name ?: response.city,
        start_lat = start_lat,
        start_lon = start_lon,
        available_minutes = available_minutes,
        interests = response.interests,
        pace = pace,
        return_to_start = return_to_start,
        start_datetime = response.start_datetime,
        respect_opening_hours = opening_hours_enabled,
        exclude_poi_ids = skippedPoiIds,
        transport_mode = response.transport_mode ?: "walk"
    )

    return SavedRouteSnapshot(
        request = request,
        response = response
    )
}

private fun RouteSessionDto.toRouteFeedback(): RouteFeedback? {
    val latestFeedback = feedback.orEmpty().firstOrNull() ?: return null
    return RouteFeedback(
        rating = latestFeedback.rating,
        route_was_comfortable = latestFeedback.was_convenient,
        too_much_walking = latestFeedback.too_much_walking,
        pois_were_interesting = latestFeedback.pois_were_interesting
    )
}

private fun RouteSessionStatus.isRestorable(): Boolean =
    this == RouteSessionStatus.NOT_STARTED ||
        this == RouteSessionStatus.IN_PROGRESS ||
        this == RouteSessionStatus.PAUSED

private fun CityDto.matchesToken(token: String?): Boolean {
    val normalizedToken = token?.trim()?.lowercase().orEmpty()
    return normalizedToken.isNotBlank() &&
        normalizedToken in setOf(slug.lowercase(), name.lowercase())
}

private fun CityDto.availableCategories(): List<String> =
    available_categories
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.distinct()
        .orEmpty()
        .ifEmpty { DefaultInterestCategories }

private fun CityDto.supportsPublicTransport(): Boolean =
    transport?.mhd_enabled == true

private fun CityDto.toStartPoint(): RouteStartDto =
    RouteStartDto(center_lat, center_lon)

private fun CityDto.toOfflineCityRegion(): OfflineCityRegion? {
    val cityBbox = bbox ?: return null
    return OfflineCityRegion(
        slug = slug,
        name = name,
        styleUrl = StreetStyleUrl,
        south = cityBbox.south,
        west = cityBbox.west,
        north = cityBbox.north,
        east = cityBbox.east
    )
}

@Composable
private fun routeSegmentLabel(segment: RouteSegmentDto): String {
    val durationMinutes = segment.duration_minutes ?: 0
    return when (segment.mode) {
        "transit" -> {
            val lineName = segment.line_name
            if (!lineName.isNullOrBlank()) {
                stringResource(R.string.route_stop_segment_transit_line, lineName, durationMinutes)
            } else {
                stringResource(R.string.route_stop_segment_transit, durationMinutes)
            }
        }

        else -> stringResource(R.string.route_stop_segment_walk, durationMinutes)
    }
}

private fun String?.toRouteTimeOfDayLabel(): String? =
    runCatching {
        this?.let(LocalDateTime::parse)?.format(RouteClockFormatter)
    }.getOrNull()

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
    skippedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    isTracking: Boolean
): RouteProgressMetrics {
    val visitedIds = visitedPoiIds.distinct()
    val skippedIds = skippedPoiIds.distinct()
    val nextTarget = nextPendingPoi(routeItems, visitedIds, skippedIds)
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
    val totalCount = progressTotalCount(routeItems, skippedIds)
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
            skippedPoiIds = skippedIds,
            currentLocation = currentLocation,
            nextTarget = nextTarget
        ),
        isOffRoute = isOffRoute,
        canComplete = totalCount > 0 && visitedCount >= requiredCompletionCount(totalCount)
    )
}

private fun progressTotalCount(
    routeItems: List<RouteItemDto>,
    skippedPoiIds: List<Int>
): Int =
    routeItems.count { item -> item.poi_id !in skippedPoiIds }

private fun requiredCompletionCount(totalCount: Int): Int =
    ceil(totalCount / 2.0).toInt().coerceAtLeast(1)

private fun nextPendingPoi(
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>
): RouteItemDto? =
    routeItems.firstOrNull { item -> item.poi_id !in visitedPoiIds && item.poi_id !in skippedPoiIds }

private fun mergeReroutedRouteResponse(
    previousResponse: RouteResponse,
    reroutedResponse: RouteResponse,
    visitedPoiIds: List<Int>
): RouteResponse {
    val visitedIds = visitedPoiIds.distinct()
    if (visitedIds.isEmpty()) {
        return reroutedResponse
    }

    val visitedItems = previousResponse.route
        .filter { item -> item.poi_id in visitedIds }
        .sortedBy { item -> item.order }
    if (visitedItems.isEmpty()) {
        return reroutedResponse
    }

    val visitedElapsedMinutes = visitedItems.maxOfOrNull { item -> item.departure_after_min } ?: 0
    val renumberedVisitedItems = visitedItems.mapIndexed { index, item ->
        item.copy(order = index + 1)
    }
    val renumberedRemainingItems = reroutedResponse.route.mapIndexed { index, item ->
        item.copy(
            order = renumberedVisitedItems.size + index + 1,
            arrival_after_min = visitedElapsedMinutes + item.arrival_after_min,
            departure_after_min = visitedElapsedMinutes + item.departure_after_min
        )
    }

    val visitedLegs = previousResponse.legs
        .orEmpty()
        .filter { leg -> leg.to.poi_id in visitedIds }
        .sortedBy { leg -> leg.order }
    val renumberedVisitedLegs = visitedLegs.mapIndexed { index, leg ->
        leg.copy(order = index + 1)
    }
    val renumberedRemainingLegs = reroutedResponse.legs
        .orEmpty()
        .mapIndexed { index, leg ->
            leg.copy(order = renumberedVisitedLegs.size + index + 1)
        }

    val mergedRouteItems = renumberedVisitedItems + renumberedRemainingItems
    val mergedLegs = (renumberedVisitedLegs + renumberedRemainingLegs).ifEmpty { null }
    val mergedVisitMinutes = mergedRouteItems.sumOf { item -> item.visit_duration_min }
    val mergedUsedMinutes = visitedElapsedMinutes + reroutedResponse.used_minutes
    val mergedAvailableMinutes = maxOf(previousResponse.available_minutes, mergedUsedMinutes)
    val mergedRemainingMinutes = maxOf(0, mergedAvailableMinutes - mergedUsedMinutes)

    return reroutedResponse.copy(
        start = previousResponse.start,
        start_datetime = previousResponse.start_datetime,
        available_minutes = mergedAvailableMinutes,
        used_minutes = mergedUsedMinutes,
        remaining_minutes = mergedRemainingMinutes,
        total_visit_minutes = mergedVisitMinutes,
        total_walk_minutes = maxOf(0, mergedUsedMinutes - mergedVisitMinutes),
        poi_count = mergedRouteItems.size,
        route = mergedRouteItems,
        legs = mergedLegs,
        full_geometry = mergeLegGeometries(mergedLegs.orEmpty())
    )
}

private fun mergeLegGeometries(legs: List<RouteLegDto>): List<com.example.smarttourism.data.RouteCoordinateDto> {
    if (legs.isEmpty()) {
        return emptyList()
    }

    val mergedGeometry = mutableListOf<com.example.smarttourism.data.RouteCoordinateDto>()
    legs.forEach { leg ->
        val geometry = leg.geometry
        if (geometry.isEmpty()) {
            return@forEach
        }

        if (mergedGeometry.isEmpty()) {
            mergedGeometry.addAll(geometry)
        } else {
            mergedGeometry.addAll(geometry.drop(1))
        }
    }
    return mergedGeometry
}

private fun estimateRemainingMinutes(
    routeResponse: RouteResponse?,
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    nextTarget: RouteItemDto?
): Int {
    val remainingItems = routeItems.filter { item ->
        item.poi_id !in visitedPoiIds && item.poi_id !in skippedPoiIds
    }
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

private fun rerouteStartPoint(
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    fallbackStart: RouteStartDto
): RouteStartDto {
    currentLocation?.let { return it }

    val lastVisitedStop = routeItems
        .filter { item -> item.poi_id in visitedPoiIds }
        .maxByOrNull { item -> item.order }

    return lastVisitedStop?.let { stop ->
        RouteStartDto(lat = stop.lat, lon = stop.lon)
    } ?: fallbackStart
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
    visitedPoiIds: MutableList<Int>,
    skippedPoiIds: List<Int>
): List<Int> {
    val newlyVisitedIds = routeItems
        .filter { item -> item.poi_id !in visitedPoiIds && item.poi_id !in skippedPoiIds }
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
    return newlyVisitedIds
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
