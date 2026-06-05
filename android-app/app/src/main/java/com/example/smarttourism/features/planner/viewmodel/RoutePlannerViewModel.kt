package com.example.smarttourism.features.planner.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttourism.R
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.map.offline.OfflineMapManager
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.state.AutoRerouteCooldownMs
import com.example.smarttourism.features.planner.state.AvailableMinutesStepMinutes
import com.example.smarttourism.features.planner.state.DefaultCitySlug
import com.example.smarttourism.features.planner.state.EmptyStartPoint
import com.example.smarttourism.features.planner.state.MaximumAvailableMinutes
import com.example.smarttourism.features.planner.state.MinimumAvailableMinutes
import com.example.smarttourism.features.planner.state.OffRouteDistanceMeters
import com.example.smarttourism.features.planner.state.OffRouteSustainDurationMs
import com.example.smarttourism.features.planner.state.OfflineDownloadProgress
import com.example.smarttourism.features.planner.state.PlannerEvent
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
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
    private val routePreviewMutationUseCase = RoutePreviewMutationUseCase(repository)
    private val offlineMapManager = OfflineMapManager(appContext)
    private val deviceId = repository.getOrCreateDeviceId()

    private val poiPreviewFailedMessage = appContext.getString(R.string.error_poi_preview_failed)
    private val routeGenerationFailedMessage = appContext.getString(R.string.error_route_generation_failed_default)
    private val requiredPlacesMissingMessage = appContext.getString(R.string.error_required_places_missing)
    private val offlineCitiesFallbackMessage = appContext.getString(R.string.offline_cities_cache_used)
    private val offlinePoisFallbackMessage = appContext.getString(R.string.offline_pois_cache_used)
    internal val offlineRouteGenerationMessage = appContext.getString(R.string.offline_route_generation_unavailable)
    private val offlineMapDownloadFailedMessage = appContext.getString(R.string.offline_map_download_failed)
    private val offlineMapDeleteFailedMessage = appContext.getString(R.string.offline_map_delete_failed)
    private val offlineMapDownloadedMessage = appContext.getString(R.string.offline_map_download_complete)
    private val offlineMapDeletedMessage = appContext.getString(R.string.offline_map_delete_complete)
    private val pendingSyncQueuedMessage = appContext.getString(R.string.pending_sync_queued)
    private val routeHistoryLoadFailedMessage = appContext.getString(R.string.route_history_load_failed)

    private var initialized = false

    private val _uiState = MutableStateFlow(
        RoutePlannerUiState(startDateTime = defaultRouteStartDateTime())
    )
    val uiState: StateFlow<RoutePlannerUiState> = _uiState.asStateFlow()

    private fun updateUiState(transform: (RoutePlannerUiState) -> RoutePlannerUiState) {
        _uiState.update(transform)
    }

    private var cities: List<City>
        get() = uiState.value.cities
        set(value) = updateUiState { state -> state.copy(cities = value) }
    private var selectedCity: City?
        get() = uiState.value.selectedCity
        set(value) = updateUiState { state -> state.copy(selectedCity = value) }
    private var pois: List<Poi>
        get() = uiState.value.pois
        set(value) = updateUiState { state -> state.copy(pois = value) }
    private var isPoiLoading: Boolean
        get() = uiState.value.isPoiLoading
        set(value) = updateUiState { state -> state.copy(isPoiLoading = value) }
    private var poiError: String?
        get() = uiState.value.poiError
        set(value) = updateUiState { state -> state.copy(poiError = value) }
    private var offlineStatusMessage: String?
        get() = uiState.value.offlineStatusMessage
        set(value) = updateUiState { state -> state.copy(offlineStatusMessage = value) }
    private var pendingSyncOperationCount: Int
        get() = uiState.value.pendingSyncOperationCount
        set(value) = updateUiState { state -> state.copy(pendingSyncOperationCount = value) }
    private var offlineStoredRegion: OfflineStoredRegion?
        get() = uiState.value.offlineStoredRegion
        set(value) = updateUiState { state -> state.copy(offlineStoredRegion = value) }
    private var isOfflineMapBusy: Boolean
        get() = uiState.value.isOfflineMapBusy
        set(value) = updateUiState { state -> state.copy(isOfflineMapBusy = value) }
    private var offlineMapProgress: OfflineDownloadProgress?
        get() = uiState.value.offlineMapProgress
        set(value) = updateUiState { state -> state.copy(offlineMapProgress = value) }
    private var offlineMapMessage: String?
        get() = uiState.value.offlineMapMessage
        set(value) = updateUiState { state -> state.copy(offlineMapMessage = value) }
    private var routeResponse: RoutePlan?
        get() = uiState.value.routeResponse
        set(value) = updateUiState { state -> state.copy(routeResponse = value) }
    private var routeBookmarks: List<RouteBookmark>
        get() = uiState.value.routeBookmarks
        set(value) = updateUiState { state -> state.copy(routeBookmarks = value) }
    private var routeHistory: List<RouteHistoryEntry>
        get() = uiState.value.routeHistory
        set(value) = updateUiState { state -> state.copy(routeHistory = value) }
    private var currentRouteRequest: PlannerPreferences?
        get() = uiState.value.currentRouteRequest
        set(value) = updateUiState { state -> state.copy(currentRouteRequest = value) }
    private var activeBookmarkId: String?
        get() = uiState.value.activeBookmarkId
        set(value) = updateUiState { state -> state.copy(activeBookmarkId = value) }
    private var hasPendingRouteChanges: Boolean
        get() = uiState.value.hasPendingRouteChanges
        set(value) = updateUiState { state -> state.copy(hasPendingRouteChanges = value) }
    private var isRouteLoading: Boolean
        get() = uiState.value.isRouteLoading
        set(value) = updateUiState { state -> state.copy(isRouteLoading = value) }
    private var isRouteHistoryLoading: Boolean
        get() = uiState.value.isRouteHistoryLoading
        set(value) = updateUiState { state -> state.copy(isRouteHistoryLoading = value) }
    private var routeError: String?
        get() = uiState.value.routeError
        set(value) = updateUiState { state -> state.copy(routeError = value) }
    private var routeHistoryError: String?
        get() = uiState.value.routeHistoryError
        set(value) = updateUiState { state -> state.copy(routeHistoryError = value) }
    private var hasNoGeneratedStops: Boolean
        get() = uiState.value.hasNoGeneratedStops
        set(value) = updateUiState { state -> state.copy(hasNoGeneratedStops = value) }
    private var isRerouting: Boolean
        get() = uiState.value.isRerouting
        set(value) = updateUiState { state -> state.copy(isRerouting = value) }
    private var availableMinutes: Int
        get() = uiState.value.availableMinutes
        set(value) = updateUiState { state -> state.copy(availableMinutes = value) }
    private var pace: String
        get() = uiState.value.pace
        set(value) = updateUiState { state -> state.copy(pace = value) }
    private var returnToStart: Boolean
        get() = uiState.value.returnToStart
        set(value) = updateUiState { state -> state.copy(returnToStart = value) }
    private var respectOpeningHours: Boolean
        get() = uiState.value.respectOpeningHours
        set(value) = updateUiState { state -> state.copy(respectOpeningHours = value) }
    private var allowPublicTransport: Boolean
        get() = uiState.value.allowPublicTransport
        set(value) = updateUiState { state -> state.copy(allowPublicTransport = value) }
    private var startPoint: RoutePoint
        get() = uiState.value.startPoint
        set(value) = updateUiState { state -> state.copy(startPoint = value) }
    private var startDateTime: LocalDateTime
        get() = uiState.value.startDateTime
        set(value) = updateUiState { state -> state.copy(startDateTime = value) }
    private var routeSessionStatus: RouteSessionStatus
        get() = uiState.value.routeSessionStatus
        set(value) = updateUiState { state -> state.copy(routeSessionStatus = value) }
    private var routeId: String?
        get() = uiState.value.routeId
        set(value) = updateUiState { state -> state.copy(routeId = value) }
    private var routeStartedAt: String?
        get() = uiState.value.routeStartedAt
        set(value) = updateUiState { state -> state.copy(routeStartedAt = value) }
    private var currentTargetPoiId: Int?
        get() = uiState.value.currentTargetPoiId
        set(value) = updateUiState { state -> state.copy(currentTargetPoiId = value) }
    private var currentRouteLocation: RoutePoint?
        get() = uiState.value.currentRouteLocation
        set(value) = updateUiState { state -> state.copy(currentRouteLocation = value) }
    private var trackingError: String?
        get() = uiState.value.trackingError
        set(value) = updateUiState { state -> state.copy(trackingError = value) }
    private var routeFeedback: RouteFeedback?
        get() = uiState.value.routeFeedback
        set(value) = updateUiState { state -> state.copy(routeFeedback = value) }
    private var offRouteDetectedAtMs: Long?
        get() = uiState.value.offRouteDetectedAtMs
        set(value) = updateUiState { state -> state.copy(offRouteDetectedAtMs = value) }
    private var lastAutoRerouteAtMs: Long?
        get() = uiState.value.lastAutoRerouteAtMs
        set(value) = updateUiState { state -> state.copy(lastAutoRerouteAtMs = value) }
    private var selectedInterests: List<String>
        get() = uiState.value.selectedInterests
        set(value) = updateUiState { state -> state.copy(selectedInterests = value) }
    private var requiredPoiIds: List<Int>
        get() = uiState.value.requiredPoiIds
        set(value) = updateUiState { state -> state.copy(requiredPoiIds = value) }
    private var visitedPoiIds: List<Int>
        get() = uiState.value.visitedPoiIds
        set(value) = updateUiState { state -> state.copy(visitedPoiIds = value) }
    private var skippedPoiIds: List<Int>
        get() = uiState.value.skippedPoiIds
        set(value) = updateUiState { state -> state.copy(skippedPoiIds = value) }

    val routeItems: List<RouteStop>
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

    val isCurrentRouteBookmarked: Boolean
        get() = activeBookmarkId != null

    val maxAvailableMinutesLimit: Int
        get() = maxAvailableMinutesFor(selectedCity)

    fun onEvent(event: PlannerEvent) {
        when (event) {
            PlannerEvent.Initialize -> initialize()
            is PlannerEvent.LoadRouteHistory -> loadRouteHistory(event.forceRefresh)
            is PlannerEvent.SelectCity -> selectCity(event.city)
            is PlannerEvent.UpdateStartPoint -> updateStartPoint(event.lat, event.lon)
            is PlannerEvent.UpdateAvailableMinutes -> updateAvailableMinutes(event.value)
            is PlannerEvent.ToggleInterest -> toggleInterest(event.interest, event.checked)
            is PlannerEvent.UpdatePace -> updatePace(event.value)
            is PlannerEvent.UpdateReturnToStart -> updateReturnToStart(event.value)
            is PlannerEvent.UpdateRespectOpeningHours -> updateRespectOpeningHours(event.value)
            is PlannerEvent.UpdateAllowPublicTransport -> updateAllowPublicTransport(event.value)
            PlannerEvent.UseCurrentTime -> useCurrentTime()
            is PlannerEvent.UpdateStartDateTime -> updateStartDateTime(event.value)
            is PlannerEvent.ToggleRequiredPoi -> toggleRequiredPoi(event.poiId)
            is PlannerEvent.RemoveRequiredPoi -> removeRequiredPoi(event.poiId)
            is PlannerEvent.MoveRequiredPoi -> moveRequiredPoi(event.poiId, event.direction)
            PlannerEvent.ClearRequiredPois -> clearRequiredPois()
            PlannerEvent.GenerateRoute -> generateRoute()
            PlannerEvent.SaveCurrentRouteBookmark -> saveCurrentRouteBookmark()
            is PlannerEvent.OpenRouteBookmark -> openRouteBookmark(event.bookmarkId)
            is PlannerEvent.DeleteRouteBookmark -> deleteRouteBookmark(event.bookmarkId)
            PlannerEvent.ActivateRouteTracking -> activateRouteTracking()
            PlannerEvent.PauseRoute -> pauseRoute()
            PlannerEvent.ResumeRoute -> resumeRoute()
            PlannerEvent.FinishRoute -> finishRoute()
            PlannerEvent.CancelRoute -> cancelRoute()
            is PlannerEvent.UpdateFeedback -> updateFeedback(event.feedback)
            is PlannerEvent.MarkRouteStopVisited -> markRouteStopVisited(event.poiId)
            is PlannerEvent.SkipRouteStop -> skipRouteStop(event.poiId)
            is PlannerEvent.RemovePreviewStop -> removePreviewStop(event.poiId)
            is PlannerEvent.MovePreviewStop -> movePreviewStop(event.poiId, event.direction)
            is PlannerEvent.ReplacePreviewStop -> replacePreviewStop(event.poiId, event.preferredPoiId)
            PlannerEvent.RecalculateFromCurrentLocation -> recalculateFromCurrentLocation()
            is PlannerEvent.TrackedLocation -> handleTrackedLocation(event.point)
            is PlannerEvent.TrackingError -> handleTrackingError(event.message)
            PlannerEvent.DownloadOfflineMap -> downloadOfflineMap()
            PlannerEvent.DeleteOfflineMap -> deleteOfflineMap()
            is PlannerEvent.ClearDisplayedRoute -> clearDisplayedRoute(event.cancelActiveSession)
        }
    }

    private fun initialize() {
        if (initialized) {
            return
        }
        initialized = true
        viewModelScope.launch {
            bootstrap()
        }
    }

    private fun loadRouteHistory(forceRefresh: Boolean = true) {
        viewModelScope.launch {
            refreshRouteHistory(forceRefresh)
        }
    }

    private fun selectCity(city: City) {
        if (selectedCity?.slug == city.slug) {
            return
        }
        selectedCity = city
        activeBookmarkId = null
        currentRouteRequest = null
        requiredPoiIds = emptyList()
        clearRouteMessages()
        clearDisplayedRoute()
        startPoint = city.toStartPoint()
        viewModelScope.launch {
            loadPoisForCity(city)
        }
    }

    private fun updateStartPoint(lat: Double, lon: Double) {
        startPoint = RoutePoint(lat = lat, lon = lon)
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun updateAvailableMinutes(value: Int) {
        availableMinutes = clampAvailableMinutes(value)
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun toggleInterest(interest: String, checked: Boolean) {
        selectedInterests = if (checked) {
            (selectedInterests + interest).distinct()
        } else {
            selectedInterests.filterNot { selected -> selected == interest }
        }
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun updatePace(value: String) {
        pace = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun updateReturnToStart(value: Boolean) {
        returnToStart = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun updateRespectOpeningHours(value: Boolean) {
        respectOpeningHours = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun updateAllowPublicTransport(value: Boolean) {
        allowPublicTransport = value
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun useCurrentTime() {
        startDateTime = defaultRouteStartDateTime()
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun updateStartDateTime(value: LocalDateTime) {
        startDateTime = value.truncatedTo(ChronoUnit.MINUTES)
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun toggleRequiredPoi(poiId: Int) {
        if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS || routeSessionStatus == RouteSessionStatus.PAUSED) {
            return
        }
        requiredPoiIds = if (poiId in requiredPoiIds) {
            requiredPoiIds.filterNot { selectedPoiId -> selectedPoiId == poiId }
        } else {
            (requiredPoiIds + poiId).distinct()
        }
        currentRouteRequest = currentRouteRequest?.copy(preferredPoiIds = requiredPoiIds)
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun removeRequiredPoi(poiId: Int) {
        if (poiId !in requiredPoiIds) {
            return
        }
        requiredPoiIds = requiredPoiIds.filterNot { selectedPoiId -> selectedPoiId == poiId }
        currentRouteRequest = currentRouteRequest?.copy(preferredPoiIds = requiredPoiIds)
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun moveRequiredPoi(poiId: Int, direction: Int) {
        if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS || routeSessionStatus == RouteSessionStatus.PAUSED) {
            return
        }

        val currentIndex = requiredPoiIds.indexOf(poiId)
        if (currentIndex == -1) {
            return
        }

        val targetIndex = (currentIndex + direction).coerceIn(0, requiredPoiIds.lastIndex)
        if (targetIndex == currentIndex) {
            return
        }

        val reorderedIds = requiredPoiIds.toMutableList()
        val movedPoiId = reorderedIds.removeAt(currentIndex)
        reorderedIds.add(targetIndex, movedPoiId)

        requiredPoiIds = reorderedIds
        currentRouteRequest = currentRouteRequest?.copy(preferredPoiIds = requiredPoiIds)
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun clearRequiredPois() {
        if (requiredPoiIds.isEmpty()) {
            return
        }
        requiredPoiIds = emptyList()
        currentRouteRequest = currentRouteRequest?.copy(preferredPoiIds = emptyList())
        hasNoGeneratedStops = false
        invalidateRoutePreviewIfAllowed()
    }

    private fun generateRoute() {
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

            val request = PlannerPreferences(
                city = selectedCity?.slug ?: DefaultCitySlug,
                startLat = startPoint.lat,
                startLon = startPoint.lon,
                availableMinutes = availableMinutes,
                interests = selectedInterests,
                pace = pace,
                returnToStart = returnToStart,
                startDateTime = startDateTime.truncatedTo(ChronoUnit.MINUTES).toString(),
                respectOpeningHours = respectOpeningHours,
                preferredPoiIds = requiredPoiIds
                    .filterNot { poiId -> poiId in visitedPoiIds || poiId in skippedPoiIds },
                transportMode = if (allowPublicTransport && isPublicTransportAvailable) {
                    "walk_or_mhd"
                } else {
                    "walk"
                }
            )

            try {
                val generatedRoute = repository.generateRoute(request)
                val missingRequiredPlaces = missingRequiredPoiLabels(
                    request = request,
                    response = generatedRoute
                )
                if (missingRequiredPlaces.isNotEmpty()) {
                    routeResponse = null
                    currentRouteRequest = request
                    hasPendingRouteChanges = false
                    hasNoGeneratedStops = false
                    routeError = String.format(
                        Locale.getDefault(),
                        requiredPlacesMissingMessage,
                        missingRequiredPlaces.joinToString(", ")
                    )
                    return@launch
                }
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
                    enqueueRouteSessionSync(
                        sessionRouteId = existingRouteId,
                        status = RouteSessionStatus.CANCELLED,
                        startedAtValue = existingStartedAt,
                        snapshot = existingSnapshot,
                        finishedAt = defaultRouteStartDateTime().toString()
                    )
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

    private fun saveCurrentRouteBookmark() {
        val snapshot = currentRouteSnapshot() ?: return
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val existingBookmark = activeBookmarkId?.let { bookmarkId ->
                routeBookmarks.firstOrNull { bookmark -> bookmark.id == bookmarkId }
            }
            val bookmark = RouteBookmark(
                id = existingBookmark?.id ?: UUID.randomUUID().toString(),
                title = existingBookmark?.title
                    ?: defaultRouteBookmarkTitle(snapshot, selectedCity?.name ?: snapshot.response.city),
                citySlug = selectedCity?.slug ?: snapshot.request.city,
                snapshot = snapshot,
                createdAtEpochMs = existingBookmark?.createdAtEpochMs ?: now,
                updatedAtEpochMs = now
            )
            repository.saveRouteBookmark(bookmark)
            activeBookmarkId = bookmark.id
            routeBookmarks = repository.loadRouteBookmarks()
        }
    }

    private fun openRouteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            val bookmark = repository.loadRouteBookmark(bookmarkId) ?: return@launch
            resetRouteSession()
            clearRouteMessages()
            activeBookmarkId = bookmark.id
            restoreSnapshot(bookmark.snapshot)
            val bookmarkCity = cities.firstOrNull { city -> city.matchesToken(bookmark.citySlug) }
                ?: cities.firstOrNull { city -> city.matchesToken(bookmark.snapshot.request.city) }
            if (bookmarkCity != null) {
                loadPoisForCity(bookmarkCity)
            }
            repository.saveSnapshot(bookmark.snapshot)
        }
    }

    private fun deleteRouteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            repository.deleteRouteBookmark(bookmarkId)
            if (activeBookmarkId == bookmarkId) {
                activeBookmarkId = null
            }
            routeBookmarks = repository.loadRouteBookmarks()
        }
    }

    fun previewReplacementCandidates(poiId: Int): List<Poi> =
        buildPreviewReplacementCandidates(
            targetPoiId = poiId,
            routeItems = routeItems,
            pois = pois,
            selectedInterests = selectedInterests,
            excludePoiIds = currentRouteRequest?.excludedPoiIds.orEmpty()
        )

    private fun activateRouteTracking() {
        if (hasPendingRouteChanges) {
            return
        }
        val response = routeResponse
        if (response?.route.isNullOrEmpty() || currentRouteRequest == null) {
            return
        }

        val shouldStartFreshSession =
            routeSessionStatus == RouteSessionStatus.NOT_STARTED ||
                routeSessionStatus == RouteSessionStatus.COMPLETED ||
                routeSessionStatus == RouteSessionStatus.CANCELLED

        if (shouldStartFreshSession) {
            visitedPoiIds = emptyList()
            skippedPoiIds = emptyList()
            routeFeedback = null
        }

        val activeRouteId = if (shouldStartFreshSession) {
            UUID.randomUUID().toString()
        } else {
            routeId ?: UUID.randomUUID().toString()
        }
        val activeStartedAt = if (shouldStartFreshSession) {
            defaultRouteStartDateTime().toString()
        } else {
            routeStartedAt ?: defaultRouteStartDateTime().toString()
        }

        routeId = activeRouteId
        routeStartedAt = activeStartedAt
        currentTargetPoiId = nextPendingPoi(response!!.route, visitedPoiIds, skippedPoiIds)?.poiId
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

    private fun pauseRoute() {
        if (routeSessionStatus == RouteSessionStatus.IN_PROGRESS) {
            routeSessionStatus = RouteSessionStatus.PAUSED
            persistRouteSession(status = RouteSessionStatus.PAUSED)
        }
    }

    private fun resumeRoute() {
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

    private fun finishRoute() {
        if (!progressMetrics.canComplete) {
            return
        }
        routeSessionStatus = RouteSessionStatus.COMPLETED
        persistRouteSession(status = RouteSessionStatus.COMPLETED)
    }

    private fun cancelRoute() {
        routeSessionStatus = RouteSessionStatus.CANCELLED
        persistRouteSession(status = RouteSessionStatus.CANCELLED)
    }

    private fun updateFeedback(feedback: RouteFeedback) {
        routeFeedback = feedback
        persistRouteSession(
            status = RouteSessionStatus.COMPLETED,
            feedback = feedback
        )
        syncFeedbackToBackend(feedback)
    }

    private fun markRouteStopVisited(poiId: Int) {
        if (poiId in visitedPoiIds || poiId in skippedPoiIds) {
            return
        }

        val responseBeforeVisit = routeResponse
        val locationAtVisit = currentRouteLocation
        val statusAtVisit = routeSessionStatus
        val updatedVisited = (visitedPoiIds + poiId).distinct()
        visitedPoiIds = updatedVisited
        syncVisitedPoisToBackend(listOf(poiId))
        val nextPendingPoi = nextPendingPoi(routeItems, updatedVisited, skippedPoiIds)
        currentTargetPoiId = nextPendingPoi?.poiId

        if (nextPendingPoi == null) {
            routeSessionStatus = RouteSessionStatus.COMPLETED
            persistRouteSession(
                status = RouteSessionStatus.COMPLETED,
                visitedIds = updatedVisited
            )
        } else {
            persistRouteSession(visitedIds = updatedVisited)
            val rerouteLocation = rerouteStartPoint(
                routeItems = routeItems,
                visitedPoiIds = updatedVisited,
                currentLocation = locationAtVisit,
                fallbackStart = startPoint
            )
            if (
                statusAtVisit == RouteSessionStatus.IN_PROGRESS &&
                responseBeforeVisit != null &&
                repository.isNetworkAvailable()
            ) {
                refreshActiveRouteApproachLeg(
                    previousResponse = responseBeforeVisit,
                    currentLocation = rerouteLocation,
                    nextTarget = nextPendingPoi
                )
            } else {
                currentRouteSnapshot()?.let { snapshot ->
                    viewModelScope.launch {
                        repository.saveSnapshot(snapshot)
                    }
                }
            }
        }
    }

    private fun skipRouteStop(poiId: Int) {
        if (poiId in visitedPoiIds || poiId in skippedPoiIds) {
            return
        }

        val responseBeforeSkip = routeResponse
        val locationAtSkip = currentRouteLocation
        val statusAtSkip = routeSessionStatus
        val updatedSkipped = (skippedPoiIds + poiId).distinct()
        skippedPoiIds = updatedSkipped
        currentRouteRequest = currentRouteRequest?.copy(
            excludedPoiIds = (currentRouteRequest?.excludedPoiIds.orEmpty() + poiId).distinct(),
            preferredPoiIds = currentRouteRequest?.preferredPoiIds
                .orEmpty()
                .filterNot { preferredPoiId -> preferredPoiId == poiId }
        )
        requiredPoiIds = requiredPoiIds.filterNot { requiredPoiId -> requiredPoiId == poiId }
        val nextPendingPoi = nextPendingPoi(routeItems, visitedPoiIds, updatedSkipped)
        currentTargetPoiId = nextPendingPoi?.poiId
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

        if (
            statusAtSkip == RouteSessionStatus.IN_PROGRESS &&
            responseBeforeSkip != null &&
            repository.isNetworkAvailable()
        ) {
            val rerouteLocation = rerouteStartPoint(
                routeItems = routeItems,
                visitedPoiIds = visitedPoiIds,
                currentLocation = locationAtSkip,
                fallbackStart = startPoint
            )
            refreshActiveRouteApproachLeg(
                previousResponse = responseBeforeSkip,
                currentLocation = rerouteLocation,
                nextTarget = nextPendingPoi
            )
        } else {
            currentRouteSnapshot()?.let { snapshot ->
                viewModelScope.launch {
                    repository.saveSnapshot(snapshot)
                }
            }
        }
    }

    private fun recalculateFromCurrentLocation() {
        val location = currentRouteLocation ?: return
        recalculateRouteFromPoint(location, false, emptyList())
    }

    private fun removePreviewStop(poiId: Int) {
        if (routeSessionStatus != RouteSessionStatus.NOT_STARTED) {
            return
        }
        val response = routeResponse ?: return
        val request = currentRouteRequest ?: return
        if (!repository.isNetworkAvailable()) {
            routeError = offlineRouteGenerationMessage
            return
        }
        val updatedRequiredPoiIds = requiredPoiIds.filterNot { requiredPoiId -> requiredPoiId == poiId }
        val updatedRequest = request.copy(
            excludedPoiIds = (request.excludedPoiIds.orEmpty() + poiId).distinct(),
            preferredPoiIds = updatedRequiredPoiIds
        )

        viewModelScope.launch {
            isRerouting = true
            clearRouteMessages()
            try {
                val updatedResponse = routePreviewMutationUseCase.removeStop(
                    previousResponse = response,
                    poiId = poiId,
                    context = routePreviewMutationContext()
                )
                currentRouteRequest = updatedRequest
                requiredPoiIds = updatedRequiredPoiIds
                if (updatedResponse.route.isEmpty()) {
                    routeResponse = null
                    hasPendingRouteChanges = false
                    hasNoGeneratedStops = true
                } else {
                    routeResponse = updatedResponse
                    hasPendingRouteChanges = false
                    hasNoGeneratedStops = false
                    repository.saveSnapshot(
                        SavedRouteSnapshot(
                            request = updatedRequest,
                            response = updatedResponse
                        )
                    )
                }
            } catch (e: Exception) {
                routeError = e.toUserMessage(routeGenerationFailedMessage)
            } finally {
                isRerouting = false
            }
        }
    }

    private fun movePreviewStop(poiId: Int, direction: Int) {
        if (routeSessionStatus != RouteSessionStatus.NOT_STARTED) {
            return
        }
        val response = routeResponse ?: return
        val request = currentRouteRequest ?: return
        val originalItems = response.route.sortedBy { item -> item.order }
        val currentIndex = originalItems.indexOfFirst { item -> item.poiId == poiId }
        if (currentIndex == -1) {
            return
        }
        val targetIndex = (currentIndex + direction).coerceIn(0, originalItems.lastIndex)
        if (targetIndex == currentIndex) {
            return
        }
        if (!repository.isNetworkAvailable()) {
            routeError = offlineRouteGenerationMessage
            return
        }

        viewModelScope.launch {
            isRerouting = true
            clearRouteMessages()
            try {
                val updatedResponse = routePreviewMutationUseCase.moveStop(
                    previousResponse = response,
                    poiId = poiId,
                    direction = direction,
                    context = routePreviewMutationContext()
                )
                val updatedRequest = request.copy(preferredPoiIds = requiredPoiIds)
                currentRouteRequest = updatedRequest
                routeResponse = updatedResponse
                hasPendingRouteChanges = false
                hasNoGeneratedStops = false
                repository.saveSnapshot(
                    SavedRouteSnapshot(
                        request = updatedRequest,
                        response = updatedResponse
                    )
                )
            } catch (e: Exception) {
                routeError = e.toUserMessage(routeGenerationFailedMessage)
            } finally {
                isRerouting = false
            }
        }
    }

    private fun replacePreviewStop(poiId: Int, preferredPoiId: Int? = null) {
        if (routeSessionStatus != RouteSessionStatus.NOT_STARTED) {
            return
        }
        val response = routeResponse ?: return
        val request = currentRouteRequest ?: return
        if (!repository.isNetworkAvailable()) {
            routeError = offlineRouteGenerationMessage
            return
        }
        val replacementPoi = if (preferredPoiId != null) {
            pois.firstOrNull { poi -> poi.id == preferredPoiId }
        } else {
            previewReplacementCandidates(poiId).firstOrNull()
        }
        if (replacementPoi == null) {
            routeError = routeGenerationFailedMessage
            return
        }

        viewModelScope.launch {
            isRerouting = true
            clearRouteMessages()
            val updatedRequiredPoiIds = requiredPoiIds.filterNot { requiredPoiId -> requiredPoiId == poiId }
            val updatedRequest = request.copy(
                excludedPoiIds = (request.excludedPoiIds.orEmpty() + poiId).distinct(),
                preferredPoiIds = updatedRequiredPoiIds
            )

            try {
                val updatedResponse = routePreviewMutationUseCase.replaceStop(
                    previousResponse = response,
                    targetPoiId = poiId,
                    replacementPoi = replacementPoi,
                    context = routePreviewMutationContext()
                )
                currentRouteRequest = updatedRequest
                requiredPoiIds = updatedRequiredPoiIds
                routeResponse = updatedResponse
                hasPendingRouteChanges = false
                repository.saveSnapshot(
                    SavedRouteSnapshot(
                        request = updatedRequest,
                        response = updatedResponse
                    )
                )
            } catch (e: Exception) {
                routeError = e.toUserMessage(routeGenerationFailedMessage)
            } finally {
                isRerouting = false
            }
        }
    }

    private fun handleTrackedLocation(routeLocation: RoutePoint) {
        currentRouteLocation = routeLocation
        trackingError = null
        var approachLegRefreshed = false

        val mutableVisited = visitedPoiIds.toMutableList()
        val newlyVisitedPoiIds = markNearbyPoisVisited(
            routeItems = routeItems,
            currentLocation = routeLocation,
            visitedPoiIds = mutableVisited,
            skippedPoiIds = skippedPoiIds
        )
        visitedPoiIds = mutableVisited.distinct()
        val nextTarget = nextPendingPoi(routeItems, visitedPoiIds, skippedPoiIds)
        currentTargetPoiId = nextTarget?.poiId

        val isCurrentlyOffRoute = routeResponse != null &&
            nextTarget != null &&
            distanceToNextRouteSegmentMeters(routeResponse, nextTarget.poiId, routeLocation) > OffRouteDistanceMeters

        val nowMs = System.currentTimeMillis()
        if (isCurrentlyOffRoute) {
            offRouteDetectedAtMs = offRouteDetectedAtMs ?: nowMs
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
            if (
                routeSessionStatus == RouteSessionStatus.IN_PROGRESS &&
                routeResponse != null &&
                nextTarget != null &&
                repository.isNetworkAvailable()
            ) {
                refreshActiveRouteApproachLeg(
                    previousResponse = routeResponse!!,
                    currentLocation = routeLocation,
                    nextTarget = nextTarget
                )
                approachLegRefreshed = true
            } else {
                currentRouteSnapshot()?.let { snapshot ->
                    viewModelScope.launch {
                        repository.saveSnapshot(snapshot)
                    }
                }
            }
        }

        val offRouteDetectedAt = offRouteDetectedAtMs
        val autoRerouteCooldownElapsed = lastAutoRerouteAtMs?.let { lastTriggeredAt ->
            nowMs - lastTriggeredAt >= AutoRerouteCooldownMs
        } ?: true
        val offRouteSustained = offRouteDetectedAt != null &&
            nowMs - offRouteDetectedAt >= OffRouteSustainDurationMs

        if (
            !approachLegRefreshed &&
            routeSessionStatus == RouteSessionStatus.IN_PROGRESS &&
            routeResponse != null &&
            nextTarget != null &&
            isCurrentlyOffRoute &&
            offRouteSustained &&
            autoRerouteCooldownElapsed &&
            repository.isNetworkAvailable() &&
            !isRerouting
        ) {
            refreshActiveRouteApproachLeg(
                previousResponse = routeResponse!!,
                currentLocation = routeLocation,
                nextTarget = nextTarget,
                autoTriggered = true
            )
        }
    }

    private fun handleTrackingError(message: String) {
        routeSessionStatus = RouteSessionStatus.PAUSED
        trackingError = message
        offRouteDetectedAtMs = null
        persistRouteSession(status = RouteSessionStatus.PAUSED)
    }

    private fun downloadOfflineMap() {
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

    private fun deleteOfflineMap() {
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

    private fun clearDisplayedRoute(cancelActiveSession: Boolean = true) {
        val snapshot = currentRouteSnapshot()
        val activeRouteId = routeId
        val activeStatus = routeSessionStatus
        val activeStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()
        val cancelledFinishedAt = defaultRouteStartDateTime().toString()
        val existingHistoryEntry = activeRouteId?.let { activeId ->
            routeHistory.firstOrNull { entry -> entry.routeId == activeId }
        }

        activeBookmarkId = null
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
            val cancelledHistoryEntry = buildRouteHistoryEntry(
                routeId = activeRouteId,
                cityName = snapshot.response.city,
                status = RouteSessionStatus.CANCELLED,
                startedAt = activeStartedAt,
                finishedAt = existingHistoryEntry?.finishedAt ?: cancelledFinishedAt,
                snapshot = snapshot,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = skippedPoiIds,
                feedback = routeFeedback,
                updatedAtEpochMs = existingHistoryEntry?.updatedAtEpochMs
                    ?: routeHistoryTimestamp(existingHistoryEntry?.finishedAt ?: cancelledFinishedAt)
            )
            viewModelScope.launch {
                repository.saveRouteHistoryEntry(cancelledHistoryEntry)
                routeHistory = upsertRouteHistoryEntry(routeHistory, cancelledHistoryEntry)
                enqueueRouteSessionSync(
                    sessionRouteId = activeRouteId,
                    status = RouteSessionStatus.CANCELLED,
                    startedAtValue = activeStartedAt,
                    snapshot = snapshot,
                    finishedAt = cancelledFinishedAt
                )
            }
        }

        resetRouteSession()
    }

    private suspend fun bootstrap() {
        routeBookmarks = repository.loadRouteBookmarks()
        routeHistory = sortRouteHistoryEntries(repository.loadRouteHistoryEntries())
        val activeSession = repository.loadActiveSession()
        val savedSnapshot = activeSession?.snapshot ?: repository.loadSnapshot()
        var restoredCityToken = savedSnapshot?.request?.city ?: savedSnapshot?.response?.city
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
                    restoredCityToken = snapshot.request.city.ifBlank { snapshot.response.city }
                    restoreSnapshot(snapshot)
                    routeId = remoteSession.id
                    routeStartedAt = remoteSession.startedAt
                    val restoredStatus = RouteSessionStatus.fromRawValue(remoteSession.status)
                    routeFeedback = remoteSession.toRouteFeedback()
                    visitedPoiIds = remoteSession.pois
                        .orEmpty()
                        .filter { poi -> poi.visited && !poi.skipped }
                        .map { poi -> poi.poiId }
                        .distinct()
                    skippedPoiIds = remoteSession.pois
                        .orEmpty()
                        .filter { poi -> poi.skipped }
                        .map { poi -> poi.poiId }
                        .distinct()
                    val nextPendingPoiId = nextPendingPoi(
                        snapshot.response.route,
                        visitedPoiIds,
                        skippedPoiIds
                    )?.poiId
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
                            started_at = remoteSession.startedAt,
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
                            startedAtValue = remoteSession.startedAt,
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
        refreshRouteHistory(forceRefresh = true)
    }

    private fun restoreSnapshot(snapshot: SavedRouteSnapshot) {
        hasPendingRouteChanges = false
        hasNoGeneratedStops = false
        currentRouteRequest = PlannerPreferences(
            city = snapshot.request.city,
            startLat = snapshot.request.startLat,
            startLon = snapshot.request.startLon,
            availableMinutes = snapshot.request.availableMinutes,
            interests = snapshot.request.interests,
            pace = snapshot.request.pace,
            returnToStart = snapshot.request.returnToStart,
            startDateTime = snapshot.request.startDateTime,
            respectOpeningHours = snapshot.request.respectOpeningHours,
            excludedPoiIds = snapshot.request.excludedPoiIds.orEmpty(),
            preferredPoiIds = snapshot.request.preferredPoiIds.orEmpty(),
            transportMode = snapshot.request.transportMode ?: "walk"
        )
        routeResponse = snapshot.response
        startPoint = RoutePoint(snapshot.request.startLat, snapshot.request.startLon)
        availableMinutes = clampAvailableMinutes(snapshot.request.availableMinutes, city = null)
        pace = snapshot.request.pace
        returnToStart = snapshot.request.returnToStart
        respectOpeningHours = snapshot.request.respectOpeningHours
        allowPublicTransport = snapshot.request.transportMode == "walk_or_mhd"
        startDateTime = parseRouteStartDateTime(snapshot.request.startDateTime)
        selectedInterests = snapshot.request.interests
        requiredPoiIds = snapshot.request.preferredPoiIds.orEmpty().distinct()
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
        )?.poiId
        currentTargetPoiId = nextPendingPoiId ?: session.current_target_poi_id
        routeSessionStatus = if (restoredStatus.isRestorable() && nextPendingPoiId == null) {
            RouteSessionStatus.COMPLETED
        } else {
            restoredStatus
        }
    }

    private suspend fun loadCities(restoredCityToken: String?) {
        val cachedCities = repository.getCachedCities()
        if (cachedCities.isNotEmpty()) {
            cities = cachedCities
            selectLoadedCity(restoredCityToken)
            offlineStatusMessage = offlineCitiesFallbackMessage
            selectedCity?.let { city ->
                loadPoisForCity(city)
            }
        }

        try {
            val remoteCities = repository.fetchCities()
            repository.cacheCities(remoteCities)
            cities = remoteCities
            selectLoadedCity(restoredCityToken)
            offlineStatusMessage = null
            refreshPendingSyncOperationCount()
        } catch (_: Exception) {
            if (cachedCities.isEmpty()) {
                cities = repository.getCachedCities()
                selectLoadedCity(restoredCityToken)
            }
            offlineStatusMessage = if (cities.isNotEmpty()) {
                offlineCitiesFallbackMessage
            } else {
                null
            }
        }

        if (cachedCities.isEmpty()) {
            selectedCity?.let { city ->
                loadPoisForCity(city)
            }
        }
    }

    private fun selectLoadedCity(restoredCityToken: String?) {
        selectedCity = cities.firstOrNull { city -> city.matchesToken(restoredCityToken) }
            ?: cities.firstOrNull { city -> city.matchesToken(routeResponse?.city) }
            ?: selectedCity?.let { previousCity ->
                cities.firstOrNull { city -> city.matchesToken(previousCity.slug) }
            }
            ?: cities.firstOrNull { city -> city.matchesToken(DefaultCitySlug) }
            ?: cities.firstOrNull()

        selectedCity?.let { city ->
            currentRouteRequest = currentRouteRequest?.copy(city = city.slug)
            routeResponse = routeResponse?.copy(city = city.slug)
        }

        if (routeResponse == null) {
            selectedCity?.let { city ->
                startPoint = city.toStartPoint()
            }
        }
    }

    private suspend fun loadPoisForCity(city: City) {
        selectedCity = city
        isPoiLoading = true
        offlineMapProgress = null
        availableMinutes = clampAvailableMinutes(availableMinutes, city)

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

        val cachedPois = repository.getCachedPois(city.slug)
        if (cachedPois.isNotEmpty()) {
            pois = cachedPois
            poiError = null
        }

        try {
            val remotePois = repository.fetchPois(city.slug)
            repository.cachePois(city.slug, remotePois)
            pois = remotePois
            poiError = null
            offlineStatusMessage = null
            refreshPendingSyncOperationCount()
        } catch (e: Exception) {
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

    private fun maxAvailableMinutesFor(city: City?): Int =
        city?.routingLimits?.maxAvailableMinutes
            ?.coerceIn(MinimumAvailableMinutes, MaximumAvailableMinutes)
            ?: MaximumAvailableMinutes

    private fun clampAvailableMinutes(value: Int, city: City? = selectedCity): Int {
        val maxAllowedMinutes = maxAvailableMinutesFor(city)
        val clampedValue = value.coerceIn(MinimumAvailableMinutes, maxAllowedMinutes)
        val relativeMinutes = clampedValue - MinimumAvailableMinutes
        val remainder = relativeMinutes % AvailableMinutesStepMinutes

        if (remainder == 0) {
            return clampedValue
        }

        val roundedValue = if (remainder >= AvailableMinutesStepMinutes / 2) {
            clampedValue + (AvailableMinutesStepMinutes - remainder)
        } else {
            clampedValue - remainder
        }

        return roundedValue.coerceIn(MinimumAvailableMinutes, maxAllowedMinutes)
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

    private fun missingRequiredPoiLabels(
        request: PlannerPreferences,
        response: RoutePlan
    ): List<String> {
        val requiredIds = request.preferredPoiIds.orEmpty().distinct()
        if (requiredIds.isEmpty()) {
            return emptyList()
        }

        val generatedIds = response.route.map { item -> item.poiId }.toSet()
        val poisById = pois.associateBy { poi -> poi.id }
        return requiredIds
            .filterNot { poiId -> poiId in generatedIds }
            .map { poiId -> poisById[poiId]?.name ?: "#$poiId" }
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

    private fun routePreviewMutationContext(): RoutePreviewMutationContext =
        RoutePreviewMutationContext(
            city = currentRouteRequest?.city ?: selectedCity?.slug,
            pace = currentRouteRequest?.pace,
            startDateTime = currentRouteRequest?.startDateTime,
            transportMode = currentRouteRequest?.transportMode
        )

    private suspend fun enqueueRouteSessionSync(
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAtValue: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ) {
        repository.enqueuePendingRouteSession(
            sessionRouteId = sessionRouteId,
            deviceId = deviceId,
            status = status.rawValue,
            startedAt = startedAtValue,
            snapshot = snapshot,
            finishedAt = finishedAt
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
        val nextTargetId = nextPendingPoi(snapshot.response.route, visitedIds, skippedIds)?.poiId
        val totalCount = progressTotalCount(snapshot.response.route, skippedIds)
        val existingHistoryEntry = routeHistory.firstOrNull { entry -> entry.routeId == savedRouteId }
        val finishedAt = if (status == RouteSessionStatus.COMPLETED || status == RouteSessionStatus.CANCELLED) {
            existingHistoryEntry?.finishedAt ?: defaultRouteStartDateTime().toString()
        } else {
            null
        }
        val historyUpdatedAt = when {
            status == RouteSessionStatus.COMPLETED || status == RouteSessionStatus.CANCELLED ->
                existingHistoryEntry?.updatedAtEpochMs ?: routeHistoryTimestamp(finishedAt)
            else -> System.currentTimeMillis()
        }
        val historyEntry = buildRouteHistoryEntry(
            routeId = savedRouteId,
            cityName = snapshot.response.city,
            status = status,
            startedAt = savedStartedAt,
            finishedAt = finishedAt,
            snapshot = snapshot,
            visitedPoiIds = visitedIds,
            skippedPoiIds = skippedIds,
            feedback = feedback,
            updatedAtEpochMs = historyUpdatedAt
        )

        currentTargetPoiId = nextTargetId

        viewModelScope.launch {
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
            repository.saveRouteHistoryEntry(historyEntry)
            routeHistory = upsertRouteHistoryEntry(routeHistory, historyEntry)
            enqueueRouteSessionSync(
                sessionRouteId = savedRouteId,
                status = status,
                startedAtValue = savedStartedAt,
                snapshot = snapshot,
                finishedAt = finishedAt
            )
        }
    }

    private suspend fun refreshRouteHistory(forceRefresh: Boolean) {
        val cachedHistory = sortRouteHistoryEntries(repository.loadRouteHistoryEntries())
        if (routeHistory.isEmpty()) {
            routeHistory = cachedHistory
        }
        if (!forceRefresh && routeHistory.isNotEmpty()) {
            return
        }

        isRouteHistoryLoading = true
        routeHistoryError = null

        if (!repository.isNetworkAvailable()) {
            isRouteHistoryLoading = false
            return
        }

        runCatching {
            val remoteEntries = repository.getRouteSessions(deviceId)
                .mapNotNull { session ->
                    runCatching { session.toRouteHistoryEntry() }.getOrNull()
                }
            val mergedEntries = mergeRouteHistoryEntries(
                cachedHistory,
                remoteEntries,
                buildCurrentRouteHistoryEntry()
            )
            repository.saveRouteHistoryEntries(mergedEntries)
            routeHistory = mergedEntries
        }.onFailure { error ->
            routeHistory = cachedHistory
            routeHistoryError = if (cachedHistory.isEmpty()) {
                error.toUserMessage(routeHistoryLoadFailedMessage)
            } else {
                null
            }
        }

        isRouteHistoryLoading = false
    }

    private fun buildCurrentRouteHistoryEntry(): RouteHistoryEntry? {
        val currentSnapshot = currentRouteSnapshot() ?: return null
        val currentRouteId = routeId ?: return null
        val startedAtValue = routeStartedAt ?: defaultRouteStartDateTime().toString()
        val existingHistoryEntry = routeHistory.firstOrNull { entry -> entry.routeId == currentRouteId }
        val finishedAtValue = if (
            routeSessionStatus == RouteSessionStatus.COMPLETED ||
                routeSessionStatus == RouteSessionStatus.CANCELLED
        ) {
            existingHistoryEntry?.finishedAt ?: defaultRouteStartDateTime().toString()
        } else {
            null
        }
        val historyUpdatedAt = when {
            routeSessionStatus == RouteSessionStatus.COMPLETED ||
                routeSessionStatus == RouteSessionStatus.CANCELLED ->
                existingHistoryEntry?.updatedAtEpochMs ?: routeHistoryTimestamp(finishedAtValue)
            else -> System.currentTimeMillis()
        }

        return buildRouteHistoryEntry(
            routeId = currentRouteId,
            cityName = currentSnapshot.response.city,
            status = routeSessionStatus,
            startedAt = startedAtValue,
            finishedAt = finishedAtValue,
            snapshot = currentSnapshot,
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            feedback = routeFeedback,
            updatedAtEpochMs = historyUpdatedAt
        )
    }

    private fun sortRouteHistoryEntries(entries: List<RouteHistoryEntry>): List<RouteHistoryEntry> =
        entries.sortedByDescending { entry -> entry.updatedAtEpochMs }

    private fun mergeRouteHistoryEntries(
        cachedEntries: List<RouteHistoryEntry>,
        remoteEntries: List<RouteHistoryEntry>,
        currentEntry: RouteHistoryEntry?
    ): List<RouteHistoryEntry> {
        val merged = linkedMapOf<String, RouteHistoryEntry>()
        cachedEntries.forEach { entry ->
            merged[entry.routeId] = entry
        }
        remoteEntries.forEach { entry ->
            val existing = merged[entry.routeId]
            merged[entry.routeId] = if (existing == null) {
                entry
            } else {
                mergeRemoteHistoryEntry(existing, entry)
            }
        }
        if (currentEntry != null) {
            val existing = merged[currentEntry.routeId]
            merged[currentEntry.routeId] = if (existing == null) {
                currentEntry
            } else {
                choosePreferredHistoryEntry(existing, currentEntry)
            }
        }
        return sortRouteHistoryEntries(merged.values.toList())
    }

    private fun mergeRemoteHistoryEntry(
        localEntry: RouteHistoryEntry,
        remoteEntry: RouteHistoryEntry
    ): RouteHistoryEntry =
        remoteEntry.copy(
            feedback = remoteEntry.feedback ?: localEntry.feedback,
            visitedPoiIds = if (
                remoteEntry.visitedPoiIds.isNotEmpty() ||
                    remoteEntry.skippedPoiIds.isNotEmpty()
            ) {
                remoteEntry.visitedPoiIds
            } else {
                localEntry.visitedPoiIds
            },
            skippedPoiIds = if (
                remoteEntry.visitedPoiIds.isNotEmpty() ||
                    remoteEntry.skippedPoiIds.isNotEmpty()
            ) {
                remoteEntry.skippedPoiIds
            } else {
                localEntry.skippedPoiIds
            }
        )

    private fun upsertRouteHistoryEntry(
        currentEntries: List<RouteHistoryEntry>,
        entry: RouteHistoryEntry
    ): List<RouteHistoryEntry> =
        sortRouteHistoryEntries(
            buildList {
                val existing = currentEntries.firstOrNull { current -> current.routeId == entry.routeId }
                add(if (existing == null) entry else choosePreferredHistoryEntry(existing, entry))
                addAll(currentEntries.filterNot { existingEntry -> existingEntry.routeId == entry.routeId })
            }
        )

    private fun choosePreferredHistoryEntry(
        existing: RouteHistoryEntry,
        candidate: RouteHistoryEntry
    ): RouteHistoryEntry {
        val existingStatus = RouteSessionStatus.fromRawValue(existing.status)
        val candidateStatus = RouteSessionStatus.fromRawValue(candidate.status)

        if (existingStatus.isTerminal() && !candidateStatus.isTerminal()) {
            return existing
        }
        if (!existingStatus.isTerminal() && candidateStatus.isTerminal()) {
            return candidate
        }
        if (existing.feedback != null && candidate.feedback == null) {
            return existing
        }
        if (existing.feedback == null && candidate.feedback != null) {
            return candidate
        }

        return if (candidate.updatedAtEpochMs >= existing.updatedAtEpochMs) candidate else existing
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
                    visitedAt = defaultRouteStartDateTime().toString(),
                    skipped = false
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
                    visitedAt = defaultRouteStartDateTime().toString(),
                    skipped = true
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

        viewModelScope.launch {
            repository.enqueuePendingFeedback(
                sessionId = sessionRouteId,
                feedback = feedback
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
        currentLocation: RoutePoint,
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
            ).coerceIn(30, maxOf(30, baseRequest.availableMinutes))
            val request = baseRequest.copy(
                startLat = currentLocation.lat,
                startLon = currentLocation.lon,
                availableMinutes = remainingMinutes,
                startDateTime = defaultRouteStartDateTime().toString(),
                excludedPoiIds = (visitedPoiIds + effectiveSkippedPoiIds).distinct(),
                preferredPoiIds = baseRequest.preferredPoiIds
                    .orEmpty()
                    .filterNot { poiId -> poiId in visitedPoiIds || poiId in effectiveSkippedPoiIds },
                transportMode = baseRequest.transportMode ?: "walk"
            )

            try {
                val generatedRoute = repository.generateRoute(request)
                val mergedRoute = mergeReroutedRoutePlan(
                    previousResponse = response,
                    reroutedResponse = generatedRoute,
                    visitedPoiIds = visitedPoiIds
                )
                val nextPendingPoiId = nextPendingPoi(
                    mergedRoute.route,
                    visitedPoiIds,
                    effectiveSkippedPoiIds
                )?.poiId
                val finalizedRoute = if (nextPendingPoiId == null) {
                    finalizedHandledRoutePlan(
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
                startPoint = RoutePoint(currentLocation.lat, currentLocation.lon)
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

    private fun refreshActiveRouteApproachLeg(
        previousResponse: RoutePlan,
        currentLocation: RoutePoint,
        nextTarget: RouteStop,
        autoTriggered: Boolean = false
    ) {
        val cityToken = currentRouteRequest?.city ?: selectedCity?.slug ?: previousResponse.city
        val routeLegRequest = RouteLegQuery(
            city = cityToken,
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon,
            endPoiId = nextTarget.poiId,
            endName = nextTarget.name,
            pace = currentRouteRequest?.pace ?: previousResponse.pace,
            startDateTime = defaultRouteStartDateTime().toString(),
            transportMode = currentRouteRequest?.transportMode ?: previousResponse.transportMode ?: "walk"
        )

        viewModelScope.launch {
            isRerouting = true
            try {
                val replacementLeg = repository.generateRouteLeg(routeLegRequest)
                val updatedResponse = replaceActiveRouteApproachLeg(
                    previousResponse = previousResponse,
                    nextPoiId = nextTarget.poiId,
                    replacementLeg = replacementLeg
                )
                routeResponse = updatedResponse
                val snapshot = currentRouteSnapshot()
                if (snapshot != null) {
                    repository.saveSnapshot(snapshot)
                    persistRouteSession(
                        status = routeSessionStatus,
                        skippedIds = skippedPoiIds,
                        snapshotOverride = snapshot
                    )
                }
            } catch (_: Exception) {
                currentRouteSnapshot()?.let { snapshot ->
                    repository.saveSnapshot(snapshot)
                }
            } finally {
                if (autoTriggered) {
                    lastAutoRerouteAtMs = System.currentTimeMillis()
                    offRouteDetectedAtMs = null
                }
                isRerouting = false
            }
        }
    }
}
