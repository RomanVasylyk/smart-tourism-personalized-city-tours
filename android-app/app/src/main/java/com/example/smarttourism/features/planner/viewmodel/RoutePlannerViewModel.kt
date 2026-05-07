package com.example.smarttourism.features.planner

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttourism.R
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteItemDto
import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionDto
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.remote.dto.RouteStartDto
import com.example.smarttourism.features.map.offline.OfflineMapManager
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID

internal class RoutePlannerViewModel(
    application: Application
) : AndroidViewModel(application) {
    private val appContext = application.applicationContext
    private val repository = PlannerRepository(appContext)
    private val offlineMapManager = OfflineMapManager(appContext)
    private val deviceId = repository.getOrCreateDeviceId()

    private val poiPreviewFailedMessage = appContext.getString(R.string.error_poi_preview_failed)
    private val routeGenerationFailedMessage = appContext.getString(R.string.error_route_generation_failed_default)
    private val offlineCitiesFallbackMessage = appContext.getString(R.string.offline_cities_cache_used)
    private val offlinePoisFallbackMessage = appContext.getString(R.string.offline_pois_cache_used)
    internal val offlineRouteGenerationMessage = appContext.getString(R.string.offline_route_generation_unavailable)
    private val offlineMapDownloadFailedMessage = appContext.getString(R.string.offline_map_download_failed)
    private val offlineMapDeleteFailedMessage = appContext.getString(R.string.offline_map_delete_failed)
    private val offlineMapDownloadedMessage = appContext.getString(R.string.offline_map_download_complete)
    private val offlineMapDeletedMessage = appContext.getString(R.string.offline_map_delete_complete)
    private val pendingSyncQueuedMessage = appContext.getString(R.string.pending_sync_queued)

    private var initialized = false

    var cities by mutableStateOf<List<CityDto>>(emptyList())
        private set
    var selectedCity by mutableStateOf<CityDto?>(null)
        private set
    var pois by mutableStateOf<List<PoiDto>>(emptyList())
        private set
    var isPoiLoading by mutableStateOf(true)
        private set
    var poiError by mutableStateOf<String?>(null)
        private set
    var offlineStatusMessage by mutableStateOf<String?>(null)
        private set
    var pendingSyncOperationCount by mutableIntStateOf(0)
        private set
    var offlineStoredRegion by mutableStateOf<OfflineStoredRegion?>(null)
        private set
    var isOfflineMapBusy by mutableStateOf(false)
        private set
    var offlineMapProgress by mutableStateOf<OfflineDownloadProgress?>(null)
        private set
    var offlineMapMessage by mutableStateOf<String?>(null)
        private set

    var routeResponse by mutableStateOf<RouteResponse?>(null)
        private set
    var currentRouteRequest by mutableStateOf<RouteRequest?>(null)
        private set
    var hasPendingRouteChanges by mutableStateOf(false)
        private set
    var isRouteLoading by mutableStateOf(false)
        private set
    var routeError by mutableStateOf<String?>(null)
        private set
    var hasNoGeneratedStops by mutableStateOf(false)
        private set
    var isRerouting by mutableStateOf(false)
        private set

    var availableMinutes by mutableIntStateOf(180)
        private set
    var pace by mutableStateOf("normal")
        private set
    var returnToStart by mutableStateOf(true)
        private set
    var respectOpeningHours by mutableStateOf(true)
        private set
    var allowPublicTransport by mutableStateOf(false)
        private set
    var startPoint by mutableStateOf(EmptyStartPoint)
        private set
    var startDateTime by mutableStateOf(defaultRouteStartDateTime())
        private set
    var routeSessionStatus by mutableStateOf(RouteSessionStatus.NOT_STARTED)
        private set
    var routeId by mutableStateOf<String?>(null)
        private set
    var routeStartedAt by mutableStateOf<String?>(null)
        private set
    var currentTargetPoiId by mutableStateOf<Int?>(null)
        private set
    var currentRouteLocation by mutableStateOf<RouteStartDto?>(null)
        private set
    var trackingError by mutableStateOf<String?>(null)
        private set
    var routeFeedback by mutableStateOf<RouteFeedback?>(null)
        private set
    var offRouteDetectedAtMs by mutableStateOf<Long?>(null)
        private set
    var lastAutoRerouteAtMs by mutableStateOf<Long?>(null)
        private set

    var selectedInterests by mutableStateOf<List<String>>(emptyList())
        private set
    var visitedPoiIds by mutableStateOf<List<Int>>(emptyList())
        private set
    var skippedPoiIds by mutableStateOf<List<Int>>(emptyList())
        private set

    val routeItems: List<RouteItemDto>
        get() = routeResponse?.route.orEmpty()

    val progressMetrics: RouteProgressMetrics
        get() = routeProgressMetrics(
            routeResponse = routeResponse,
            routeItems = routeItems,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            currentLocation = currentRouteLocation,
            isTracking = routeSessionStatus == RouteSessionStatus.IN_PROGRESS
        )

    val selectedCityAvailableCategories: List<String>
        get() = selectedCity?.availableCategories().orEmpty()

    val isPublicTransportAvailable: Boolean
        get() = selectedCity?.supportsPublicTransport() == true

    fun initialize() {
        if (initialized) {
            return
        }
        initialized = true
        viewModelScope.launch {
            bootstrap()
        }
    }

    fun selectCity(city: CityDto) {
        if (selectedCity?.slug == city.slug) {
            return
        }
        selectedCity = city
        currentRouteRequest = null
        clearRouteMessages()
        clearDisplayedRoute()
        startPoint = city.toStartPoint()
        viewModelScope.launch {
            loadPoisForCity(city)
        }
    }

    fun updateStartPoint(lat: Double, lon: Double) {
        startPoint = RouteStartDto(lat = lat, lon = lon)
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun updateAvailableMinutes(value: Int) {
        availableMinutes = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun toggleInterest(interest: String, checked: Boolean) {
        selectedInterests = if (checked) {
            (selectedInterests + interest).distinct()
        } else {
            selectedInterests.filterNot { selected -> selected == interest }
        }
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun updatePace(value: String) {
        pace = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun updateReturnToStart(value: Boolean) {
        returnToStart = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun updateRespectOpeningHours(value: Boolean) {
        respectOpeningHours = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun updateAllowPublicTransport(value: Boolean) {
        allowPublicTransport = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun useCurrentTime() {
        startDateTime = defaultRouteStartDateTime()
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    fun generateRoute() {
        viewModelScope.launch {
            if (!repository.isNetworkAvailable()) {
                routeError = offlineRouteGenerationMessage
                hasNoGeneratedStops = false
                return@launch
            }

            isRouteLoading = true
            clearRouteMessages()
            val existingSnapshot = currentRouteSnapshot()
            val existingRouteId = routeId
            val existingStatus = routeSessionStatus
            val existingStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()

            val request = RouteRequest(
                city = selectedCity?.slug ?: DefaultCitySlug,
                start_lat = startPoint.lat,
                start_lon = startPoint.lon,
                available_minutes = availableMinutes,
                interests = selectedInterests,
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
                val generatedRoute = repository.generateRoute(request)
                if (generatedRoute.route.isEmpty()) {
                    routeResponse = null
                    currentRouteRequest = null
                    hasPendingRouteChanges = false
                    hasNoGeneratedStops = true
                    return@launch
                }
                if (
                    existingRouteId != null &&
                    existingSnapshot != null &&
                    existingStatus.isRestorable()
                ) {
                    repository.enqueuePendingRouteSession(
                        buildRouteSessionSyncRequest(
                            sessionRouteId = existingRouteId,
                            status = RouteSessionStatus.CANCELLED,
                            startedAtValue = existingStartedAt,
                            snapshot = existingSnapshot,
                            finishedAt = defaultRouteStartDateTime().toString()
                        )
                    )
                    repository.scheduleImmediateSync()
                }
                resetRouteSession()
                currentRouteRequest = request
                routeResponse = generatedRoute
                hasPendingRouteChanges = false
                repository.saveSnapshot(
                    SavedRouteSnapshot(
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

    fun activateRouteTracking() {
        if (hasPendingRouteChanges) {
            return
        }
        val response = routeResponse
        if (response?.route.isNullOrEmpty() || currentRouteRequest == null) {
            return
        }

        if (
            routeSessionStatus == RouteSessionStatus.NOT_STARTED ||
            routeSessionStatus == RouteSessionStatus.COMPLETED ||
            routeSessionStatus == RouteSessionStatus.CANCELLED
        ) {
            visitedPoiIds = emptyList()
            skippedPoiIds = emptyList()
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

    fun pauseRoute() {
        if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS) {
            routeSessionStatus = RouteSessionStatus.PAUSED
            persistRouteSession(status = RouteSessionStatus.PAUSED)
        }
    }

    fun resumeRoute() {
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

    fun markRouteStopVisited(poiId: Int) {
        if (poiId in visitedPoiIds || poiId in skippedPoiIds) {
            return
        }

        val updatedVisited = (visitedPoiIds + poiId).distinct()
        visitedPoiIds = updatedVisited
        syncVisitedPoisToBackend(listOf(poiId))
        val nextPendingPoi = nextPendingPoi(routeItems, updatedVisited, skippedPoiIds)
        currentTargetPoiId = nextPendingPoi?.poi_id

        if (nextPendingPoi == null) {
            routeSessionStatus = RouteSessionStatus.COMPLETED
            persistRouteSession(
                status = RouteSessionStatus.COMPLETED,
                visitedIds = updatedVisited
            )
        } else {
            persistRouteSession(visitedIds = updatedVisited)
        }
    }

    fun skipRouteStop(poiId: Int) {
        if (poiId in visitedPoiIds || poiId in skippedPoiIds) {
            return
        }

        val updatedSkipped = (skippedPoiIds + poiId).distinct()
        skippedPoiIds = updatedSkipped
        currentRouteRequest = currentRouteRequest?.copy(
            exclude_poi_ids = (currentRouteRequest?.exclude_poi_ids.orEmpty() + poiId).distinct()
        )
        val nextPendingPoi = nextPendingPoi(routeItems, visitedPoiIds, updatedSkipped)
        currentTargetPoiId = nextPendingPoi?.poi_id
        currentRouteSnapshot()?.let { snapshot ->
            viewModelScope.launch {
                repository.saveSnapshot(snapshot)
            }
        }
        syncSkippedPoisToBackend(listOf(poiId))

        if (nextPendingPoi == null) {
            routeSessionStatus = RouteSessionStatus.COMPLETED
            persistRouteSession(
                status = RouteSessionStatus.COMPLETED,
                skippedIds = updatedSkipped
            )
            return
        }

        persistRouteSession(
            status = routeSessionStatus,
            skippedIds = updatedSkipped
        )

        if (routeResponse == null || currentRouteRequest == null || !repository.isNetworkAvailable()) {
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

    fun recalculateFromCurrentLocation() {
        val location = currentRouteLocation ?: return
        recalculateRouteFromPoint(location, false, emptyList())
    }

    fun handleTrackedLocation(routeLocation: RouteStartDto) {
        currentRouteLocation = routeLocation
        trackingError = null

        val mutableVisited = visitedPoiIds.toMutableList()
        val newlyVisitedPoiIds = markNearbyPoisVisited(
            routeItems = routeItems,
            currentLocation = routeLocation,
            visitedPoiIds = mutableVisited,
            skippedPoiIds = skippedPoiIds
        )
        visitedPoiIds = mutableVisited.distinct()
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

        if (nextTarget == null && routeSessionStatus != RouteSessionStatus.COMPLETED) {
            routeSessionStatus = RouteSessionStatus.COMPLETED
            persistRouteSession(
                status = RouteSessionStatus.COMPLETED,
                visitedIds = visitedPoiIds,
                skippedIds = skippedPoiIds
            )
            syncVisitedPoisToBackend(newlyVisitedPoiIds)
        } else if (newlyVisitedPoiIds.isNotEmpty()) {
            persistRouteSession(
                status = RouteSessionStatus.IN_PROGRESS,
                visitedIds = visitedPoiIds,
                skippedIds = skippedPoiIds
            )
            syncVisitedPoisToBackend(newlyVisitedPoiIds)
        }
    }

    fun handleTrackingError(message: String) {
        routeSessionStatus = RouteSessionStatus.PAUSED
        trackingError = message
        offRouteDetectedAtMs = null
        persistRouteSession(status = RouteSessionStatus.PAUSED)
    }

    fun downloadOfflineMap() {
        val city = selectedCity ?: return
        val offlineRegion = city.toOfflineCityRegion() ?: run {
            offlineMapMessage = null
            return
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
                viewModelScope.launch {
                    offlineStoredRegion = offlineMapManager.findRegionBySlug(city.slug)
                }
            },
            onError = { error ->
                isOfflineMapBusy = false
                offlineMapMessage = "$offlineMapDownloadFailedMessage $error"
            }
        )
    }

    fun deleteOfflineMap() {
        val city = selectedCity ?: return
        val storedRegion = offlineStoredRegion ?: return

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

    fun clearDisplayedRoute(cancelActiveSession: Boolean = true) {
        val snapshot = currentRouteSnapshot()
        val activeRouteId = routeId
        val activeStatus = routeSessionStatus
        val activeStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()

        hasPendingRouteChanges = false
        routeResponse = null
        currentRouteRequest = null
        clearRouteMessages()

        if (
            cancelActiveSession &&
            activeRouteId != null &&
            snapshot != null &&
            (activeStatus == RouteSessionStatus.IN_PROGRESS || activeStatus == RouteSessionStatus.PAUSED)
        ) {
            viewModelScope.launch {
                val response = snapshot.response
                repository.enqueuePendingRouteSession(
                    RouteSessionCreateRequest(
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
                pendingSyncOperationCount = repository.getPendingSyncOperationCount()
                repository.scheduleImmediateSync()
            }
        }

        resetRouteSession()
    }

    private suspend fun bootstrap() {
        val activeSession = repository.loadActiveSession()
        val savedSnapshot = activeSession?.snapshot ?: repository.loadSnapshot()
        var restoredCityToken = savedSnapshot?.request?.city
        pendingSyncOperationCount = repository.getPendingSyncOperationCount()

        savedSnapshot?.let { snapshot ->
            restoreSnapshot(snapshot)
        }

        activeSession?.let { session ->
            restoreActiveSession(session)
            if (
                routeSessionStatus == RouteSessionStatus.COMPLETED &&
                RouteSessionStatus.fromRawValue(session.status) != RouteSessionStatus.COMPLETED
            ) {
                persistRouteSession(
                    status = RouteSessionStatus.COMPLETED,
                    routeIdValue = session.route_id,
                    startedAtValue = session.started_at,
                    visitedIds = visitedPoiIds,
                    skippedIds = skippedPoiIds,
                    feedback = routeFeedback,
                    snapshotOverride = session.snapshot
                )
            }
        }

        repository.scheduleImmediateSync()

        runCatching {
            val remoteSession = if (routeId != null && routeSessionStatus.isRestorable()) {
                repository.getRouteSession(routeId!!)
            } else {
                repository.getRouteSessions(deviceId)
                    .firstOrNull { session ->
                        RouteSessionStatus.fromRawValue(session.status).isRestorable()
                    }
            }

            if (remoteSession != null) {
                remoteSession.toSavedRouteSnapshot()?.let { snapshot ->
                    restoredCityToken = snapshot.request.city
                    restoreSnapshot(snapshot)
                    routeId = remoteSession.id
                    routeStartedAt = remoteSession.started_at
                    val restoredStatus = RouteSessionStatus.fromRawValue(remoteSession.status)
                    routeFeedback = remoteSession.toRouteFeedback()
                    visitedPoiIds = remoteSession.pois
                        .orEmpty()
                        .filter { poi -> poi.visited && !poi.skipped }
                        .map { poi -> poi.poi_id }
                        .distinct()
                    skippedPoiIds = remoteSession.pois
                        .orEmpty()
                        .filter { poi -> poi.skipped }
                        .map { poi -> poi.poi_id }
                        .distinct()
                    val nextPendingPoiId = nextPendingPoi(
                        snapshot.response.route,
                        visitedPoiIds,
                        skippedPoiIds
                    )?.poi_id
                    currentTargetPoiId = nextPendingPoiId
                    val normalizedStatus = if (restoredStatus.isRestorable() && nextPendingPoiId == null) {
                        RouteSessionStatus.COMPLETED
                    } else {
                        restoredStatus
                    }
                    routeSessionStatus = normalizedStatus
                    repository.saveSnapshot(snapshot)
                    repository.saveActiveSession(
                        ActiveRouteSession(
                            route_id = remoteSession.id,
                            status = normalizedStatus.rawValue,
                            started_at = remoteSession.started_at,
                            current_target_poi_id = currentTargetPoiId,
                            visited_poi_ids = visitedPoiIds,
                            skipped_poi_ids = skippedPoiIds,
                            progress_visited_count = visitedPoiIds.size,
                            progress_total_count = progressTotalCount(snapshot.response.route, skippedPoiIds),
                            snapshot = snapshot,
                            feedback = routeFeedback
                        )
                    )
                    if (normalizedStatus != restoredStatus) {
                        persistRouteSession(
                            status = normalizedStatus,
                            routeIdValue = remoteSession.id,
                            startedAtValue = remoteSession.started_at,
                            visitedIds = visitedPoiIds,
                            skippedIds = skippedPoiIds,
                            feedback = routeFeedback,
                            snapshotOverride = snapshot
                        )
                    }
                }
            }
        }

        loadCities(restoredCityToken)
    }

    private fun restoreSnapshot(snapshot: SavedRouteSnapshot) {
        hasPendingRouteChanges = false
        hasNoGeneratedStops = false
        currentRouteRequest = snapshot.request.copy(
            transport_mode = snapshot.request.transport_mode ?: "walk"
        )
        routeResponse = snapshot.response
        startPoint = RouteStartDto(snapshot.request.start_lat, snapshot.request.start_lon)
        availableMinutes = snapshot.request.available_minutes
        pace = snapshot.request.pace
        returnToStart = snapshot.request.return_to_start
        respectOpeningHours = snapshot.request.respect_opening_hours
        allowPublicTransport = snapshot.request.transport_mode == "walk_or_mhd"
        startDateTime = parseRouteStartDateTime(snapshot.request.start_datetime)
        selectedInterests = snapshot.request.interests
    }

    private fun restoreActiveSession(session: ActiveRouteSession) {
        routeId = session.route_id
        routeStartedAt = session.started_at
        val restoredStatus = RouteSessionStatus.fromRawValue(session.status)
        routeFeedback = session.feedback
        visitedPoiIds = session.visited_poi_ids.distinct()
        skippedPoiIds = session.skipped_poi_ids.orEmpty().distinct()
        val nextPendingPoiId = nextPendingPoi(
            session.snapshot.response.route,
            visitedPoiIds,
            skippedPoiIds
        )?.poi_id
        currentTargetPoiId = nextPendingPoiId ?: session.current_target_poi_id
        routeSessionStatus = if (restoredStatus.isRestorable() && nextPendingPoiId == null) {
            RouteSessionStatus.COMPLETED
        } else {
            restoredStatus
        }
    }

    private suspend fun loadCities(restoredCityToken: String?) {
        try {
            val remoteCities = repository.fetchCities()
            repository.cacheCities(remoteCities)
            cities = remoteCities
            offlineStatusMessage = null
            refreshPendingSyncOperationCount()
        } catch (_: Exception) {
            cities = repository.getCachedCities()
            offlineStatusMessage = if (cities.isNotEmpty()) offlineCitiesFallbackMessage else null
        }

        selectedCity = cities.firstOrNull { city -> city.matchesToken(restoredCityToken) }
            ?: cities.firstOrNull { city -> city.matchesToken(DefaultCitySlug) }
            ?: cities.firstOrNull()

        if (routeResponse == null) {
            selectedCity?.let { city ->
                startPoint = city.toStartPoint()
            }
        }

        selectedCity?.let { city ->
            loadPoisForCity(city)
        }
    }

    private suspend fun loadPoisForCity(city: CityDto) {
        selectedCity = city
        isPoiLoading = true
        offlineMapProgress = null

        if (selectedInterests.isEmpty()) {
            selectedInterests = city.availableCategories()
        } else {
            val allowedCategories = city.availableCategories().toSet()
            val filteredInterests = selectedInterests.filter { interest -> interest in allowedCategories }
            selectedInterests = filteredInterests.ifEmpty { city.availableCategories() }
        }

        if (routeResponse == null) {
            startPoint = city.toStartPoint()
        }

        if (!city.supportsPublicTransport()) {
            allowPublicTransport = false
        }

        try {
            val remotePois = repository.fetchPois(city.slug)
            repository.cachePois(city.slug, remotePois)
            pois = remotePois
            poiError = null
            offlineStatusMessage = null
            refreshPendingSyncOperationCount()
        } catch (e: Exception) {
            val cachedPois = repository.getCachedPois(city.slug)
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

    private fun refreshPendingSyncOperationCount() {
        viewModelScope.launch {
            pendingSyncOperationCount = repository.getPendingSyncOperationCount()
        }
    }

    private fun invalidateRoutePreviewIfAllowed() {
        if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS || routeSessionStatus == RouteSessionStatus.PAUSED) {
            return
        }
        if (routeResponse != null) {
            hasPendingRouteChanges = true
        }
        clearRouteMessages()
    }

    private fun clearRouteMessages() {
        routeError = null
        hasNoGeneratedStops = false
    }

    private fun resetRouteSession(clearStoredSession: Boolean = true) {
        routeSessionStatus = RouteSessionStatus.NOT_STARTED
        routeId = null
        routeStartedAt = null
        currentTargetPoiId = null
        currentRouteLocation = null
        trackingError = null
        routeFeedback = null
        offRouteDetectedAtMs = null
        lastAutoRerouteAtMs = null
        visitedPoiIds = emptyList()
        skippedPoiIds = emptyList()
        if (clearStoredSession) {
            viewModelScope.launch {
                repository.clearActiveSession()
            }
        }
    }

    private fun currentRouteSnapshot(): SavedRouteSnapshot? {
        val request = currentRouteRequest
        val response = routeResponse
        return if (request != null && response != null) {
            SavedRouteSnapshot(request = request, response = response)
        } else {
            null
        }
    }

    private fun buildRouteSessionSyncRequest(
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

    private suspend fun enqueueRouteSessionSync(
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAtValue: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ) {
        repository.enqueuePendingRouteSession(
            buildRouteSessionSyncRequest(
                sessionRouteId = sessionRouteId,
                status = status,
                startedAtValue = startedAtValue,
                snapshot = snapshot,
                finishedAt = finishedAt
            )
        )
        pendingSyncOperationCount = repository.getPendingSyncOperationCount()
        repository.scheduleImmediateSync()
    }

    private fun persistRouteSession(
        status: RouteSessionStatus = routeSessionStatus,
        routeIdValue: String? = routeId,
        startedAtValue: String? = routeStartedAt,
        visitedIds: List<Int> = visitedPoiIds,
        skippedIds: List<Int> = skippedPoiIds,
        feedback: RouteFeedback? = routeFeedback,
        snapshotOverride: SavedRouteSnapshot? = null
    ) {
        val snapshot = snapshotOverride ?: currentRouteSnapshot() ?: return
        val savedRouteId = routeIdValue ?: return
        val savedStartedAt = startedAtValue ?: defaultRouteStartDateTime().toString()
        val nextTargetId = nextPendingPoi(snapshot.response.route, visitedIds, skippedIds)?.poi_id
        val totalCount = progressTotalCount(snapshot.response.route, skippedIds)

        currentTargetPoiId = nextTargetId

        viewModelScope.launch {
            val finishedAt = if (status == RouteSessionStatus.COMPLETED || status == RouteSessionStatus.CANCELLED) {
                defaultRouteStartDateTime().toString()
            } else {
                null
            }

            repository.saveActiveSession(
                ActiveRouteSession(
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

    private fun syncVisitedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        viewModelScope.launch {
            poiIds.distinct().forEach { poiId ->
                repository.enqueuePendingPoiVisit(
                    sessionId = sessionRouteId,
                    poiId = poiId,
                    request = RouteSessionPoiVisitRequest(
                        visited_at = defaultRouteStartDateTime().toString(),
                        skipped = false
                    )
                )
            }
            pendingSyncOperationCount = repository.getPendingSyncOperationCount()
            repository.scheduleImmediateSync()
        }
    }

    private fun syncSkippedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        viewModelScope.launch {
            poiIds.distinct().forEach { poiId ->
                repository.enqueuePendingPoiVisit(
                    sessionId = sessionRouteId,
                    poiId = poiId,
                    request = RouteSessionPoiVisitRequest(
                        visited_at = defaultRouteStartDateTime().toString(),
                        skipped = true
                    )
                )
            }
            pendingSyncOperationCount = repository.getPendingSyncOperationCount()
            repository.scheduleImmediateSync()
        }
    }

    private fun syncFeedbackToBackend(feedback: RouteFeedback) {
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

        viewModelScope.launch {
            repository.enqueuePendingFeedback(
                sessionId = sessionRouteId,
                request = feedbackRequest
            )
            pendingSyncOperationCount = repository.getPendingSyncOperationCount()
            repository.scheduleImmediateSync()
            offlineStatusMessage = if (repository.isNetworkAvailable()) {
                null
            } else {
                pendingSyncQueuedMessage
            }
        }
    }

    private fun recalculateRouteFromPoint(
        currentLocation: RouteStartDto,
        autoTriggered: Boolean,
        additionalExcludedPoiIds: List<Int>
    ) {
        val baseRequest = currentRouteRequest ?: return
        val response = routeResponse ?: return

        viewModelScope.launch {
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
            ).coerceIn(30, maxOf(30, baseRequest.available_minutes))
            val request = baseRequest.copy(
                start_lat = currentLocation.lat,
                start_lon = currentLocation.lon,
                available_minutes = remainingMinutes,
                start_datetime = defaultRouteStartDateTime().toString(),
                exclude_poi_ids = (visitedPoiIds + effectiveSkippedPoiIds).distinct(),
                transport_mode = baseRequest.transport_mode ?: "walk"
            )

            try {
                val generatedRoute = repository.generateRoute(request)
                val mergedRoute = mergeReroutedRouteResponse(
                    previousResponse = response,
                    reroutedResponse = generatedRoute,
                    visitedPoiIds = visitedPoiIds
                )
                val nextPendingPoiId = nextPendingPoi(
                    mergedRoute.route,
                    visitedPoiIds,
                    effectiveSkippedPoiIds
                )?.poi_id
                val finalizedRoute = if (nextPendingPoiId == null) {
                    finalizedHandledRouteResponse(
                        previousResponse = mergedRoute,
                        visitedPoiIds = visitedPoiIds,
                        skippedPoiIds = effectiveSkippedPoiIds
                    )
                } else {
                    mergedRoute
                }
                val snapshot = SavedRouteSnapshot(
                    request = request,
                    response = finalizedRoute
                )

                currentRouteRequest = request
                routeResponse = finalizedRoute
                startPoint = RouteStartDto(currentLocation.lat, currentLocation.lon)
                currentTargetPoiId = nextPendingPoiId
                val updatedStatus = if (nextPendingPoiId == null) {
                    RouteSessionStatus.COMPLETED
                } else if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS) {
                    RouteSessionStatus.IN_PROGRESS
                } else {
                    routeSessionStatus
                }
                routeSessionStatus = updatedStatus
                repository.saveSnapshot(snapshot)
                persistRouteSession(
                    status = updatedStatus,
                    skippedIds = effectiveSkippedPoiIds,
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
}
