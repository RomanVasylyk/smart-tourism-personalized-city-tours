package com.example.smarttourism.features.planner.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.smarttourism.R
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.application.ActiveRouteController
import com.example.smarttourism.features.planner.application.BookmarkController
import com.example.smarttourism.features.planner.application.CityCatalogLoadInput
import com.example.smarttourism.features.planner.application.OfflineMapController
import com.example.smarttourism.features.planner.application.PersistRouteSessionInput
import com.example.smarttourism.features.planner.application.PlannerCatalogUseCase
import com.example.smarttourism.features.planner.application.PlannerBootstrapUseCase
import com.example.smarttourism.features.planner.application.PoiCatalogLoadInput
import com.example.smarttourism.features.planner.application.RouteGenerationInput
import com.example.smarttourism.features.planner.application.RouteGenerationResult
import com.example.smarttourism.features.planner.application.RouteGenerationUseCase
import com.example.smarttourism.features.planner.application.RouteHistoryController
import com.example.smarttourism.features.planner.application.RoutePreviewController
import com.example.smarttourism.features.planner.domain.city.availableCategories
import com.example.smarttourism.features.planner.domain.city.matchesToken
import com.example.smarttourism.features.planner.domain.city.supportsPublicTransport
import com.example.smarttourism.features.planner.domain.city.toStartPoint
import com.example.smarttourism.features.planner.domain.history.buildRouteHistoryEntry
import com.example.smarttourism.features.planner.domain.history.isRestorable
import com.example.smarttourism.features.planner.domain.history.routeHistoryTimestamp
import com.example.smarttourism.features.planner.domain.history.toRouteFeedback
import com.example.smarttourism.features.planner.domain.history.toSavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.history.upsertRouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.route.buildPreviewReplacementCandidates
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.domain.route.estimateRemainingMinutes
import com.example.smarttourism.features.planner.domain.route.finalizedHandledRoutePlan
import com.example.smarttourism.features.planner.domain.route.mergeReroutedRoutePlan
import com.example.smarttourism.features.planner.domain.route.nextPendingPoi
import com.example.smarttourism.features.planner.domain.route.parseRouteStartDateTime
import com.example.smarttourism.features.planner.domain.route.progressTotalCount
import com.example.smarttourism.features.planner.domain.route.replaceActiveRouteApproachLeg
import com.example.smarttourism.features.planner.domain.route.routeProgressMetrics
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.state.EmptyStartPoint
import com.example.smarttourism.features.planner.state.OfflineDownloadProgress
import com.example.smarttourism.features.planner.state.PlannerStateReducer
import com.example.smarttourism.features.planner.state.PlannerEvent
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import com.example.smarttourism.features.planner.state.maxAvailableMinutesFor
import com.example.smarttourism.features.planner.application.toUserMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.util.Locale
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
internal class RoutePlannerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val repository: PlannerRepository,
    private val plannerBootstrapUseCase: PlannerBootstrapUseCase,
    private val plannerCatalogUseCase: PlannerCatalogUseCase,
    private val routeGenerationUseCase: RouteGenerationUseCase,
    private val activeRouteController: ActiveRouteController,
    private val routeHistoryController: RouteHistoryController,
    private val bookmarkController: BookmarkController,
    private val offlineMapController: OfflineMapController,
    private val routePreviewController: RoutePreviewController
) : ViewModel() {
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
        updateUiState { state ->
            PlannerStateReducer.updateStartPoint(state, RoutePoint(lat = lat, lon = lon))
        }
    }

    private fun updateAvailableMinutes(value: Int) {
        updateUiState { state ->
            PlannerStateReducer.updateAvailableMinutes(state, value)
        }
    }

    private fun toggleInterest(interest: String, checked: Boolean) {
        updateUiState { state ->
            PlannerStateReducer.toggleInterest(state, interest, checked)
        }
    }

    private fun updatePace(value: String) {
        updateUiState { state ->
            PlannerStateReducer.updatePace(state, value)
        }
    }

    private fun updateReturnToStart(value: Boolean) {
        updateUiState { state ->
            PlannerStateReducer.updateReturnToStart(state, value)
        }
    }

    private fun updateRespectOpeningHours(value: Boolean) {
        updateUiState { state ->
            PlannerStateReducer.updateRespectOpeningHours(state, value)
        }
    }

    private fun updateAllowPublicTransport(value: Boolean) {
        updateUiState { state ->
            PlannerStateReducer.updateAllowPublicTransport(state, value)
        }
    }

    private fun useCurrentTime() {
        updateUiState { state ->
            PlannerStateReducer.useCurrentTime(state, defaultRouteStartDateTime())
        }
    }

    private fun updateStartDateTime(value: LocalDateTime) {
        updateUiState { state ->
            PlannerStateReducer.updateStartDateTime(state, value)
        }
    }

    private fun toggleRequiredPoi(poiId: Int) {
        updateUiState { state ->
            PlannerStateReducer.toggleRequiredPoi(state, poiId)
        }
    }

    private fun removeRequiredPoi(poiId: Int) {
        updateUiState { state ->
            PlannerStateReducer.removeRequiredPoi(state, poiId)
        }
    }

    private fun moveRequiredPoi(poiId: Int, direction: Int) {
        updateUiState { state ->
            PlannerStateReducer.moveRequiredPoi(state, poiId, direction)
        }
    }

    private fun clearRequiredPois() {
        updateUiState { state ->
            PlannerStateReducer.clearRequiredPois(state)
        }
    }

    private fun generateRoute() {
        viewModelScope.launch {
            isRouteLoading = true
            clearRouteMessages()
            val existingSnapshot = currentRouteSnapshot()
            val existingRouteId = routeId
            val existingStatus = routeSessionStatus
            val existingStartedAt = routeStartedAt ?: defaultRouteStartDateTime().toString()
            val result = routeGenerationUseCase.generateRoute(
                RouteGenerationInput(
                    selectedCity = selectedCity,
                    startPoint = startPoint,
                    availableMinutes = availableMinutes,
                    selectedInterests = selectedInterests,
                    pace = pace,
                    returnToStart = returnToStart,
                    startDateTime = startDateTime,
                    respectOpeningHours = respectOpeningHours,
                    requiredPoiIds = requiredPoiIds,
                    visitedPoiIds = visitedPoiIds,
                    skippedPoiIds = skippedPoiIds,
                    allowPublicTransport = allowPublicTransport,
                    isPublicTransportAvailable = isPublicTransportAvailable,
                    pois = pois,
                    offlineRouteGenerationMessage = offlineRouteGenerationMessage,
                    routeGenerationFailedMessage = routeGenerationFailedMessage,
                    requiredPlacesMissingMessage = requiredPlacesMissingMessage
                )
            )

            when (result) {
                is RouteGenerationResult.Error -> {
                    routeError = result.message
                    hasNoGeneratedStops = false
                }

                is RouteGenerationResult.MissingRequiredPlaces -> {
                    routeResponse = null
                    currentRouteRequest = result.request
                    hasPendingRouteChanges = false
                    hasNoGeneratedStops = false
                    routeError = result.message
                }

                is RouteGenerationResult.EmptyRoute -> {
                    routeResponse = null
                    currentRouteRequest = null
                    hasPendingRouteChanges = false
                    hasNoGeneratedStops = true
                }

                is RouteGenerationResult.Success -> {
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
                    currentRouteRequest = result.request
                    routeResponse = result.response
                    hasPendingRouteChanges = false
                    hasNoGeneratedStops = false
                    offlineStatusMessage = null
                    refreshPendingSyncOperationCount()
                }
            }
            isRouteLoading = false
        }
    }

    private fun saveCurrentRouteBookmark() {
        val snapshot = currentRouteSnapshot() ?: return
        viewModelScope.launch {
            val result = bookmarkController.saveCurrentRouteBookmark(
                snapshot = snapshot,
                activeBookmarkId = activeBookmarkId,
                routeBookmarks = routeBookmarks,
                selectedCityName = selectedCity?.name ?: snapshot.response.city,
                selectedCitySlug = selectedCity?.slug ?: snapshot.request.city
            )
            activeBookmarkId = result.activeBookmarkId
            routeBookmarks = result.bookmarks
        }
    }

    private fun openRouteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            val bookmark = bookmarkController.loadRouteBookmark(bookmarkId) ?: return@launch
            resetRouteSession()
            clearRouteMessages()
            activeBookmarkId = bookmark.id
            restoreSnapshot(bookmark.snapshot)
            val bookmarkCity = cities.firstOrNull { city -> city.matchesToken(bookmark.citySlug) }
                ?: cities.firstOrNull { city -> city.matchesToken(bookmark.snapshot.request.city) }
            if (bookmarkCity != null) {
                loadPoisForCity(bookmarkCity)
            }
            bookmarkController.saveSnapshot(bookmark.snapshot)
        }
    }

    private fun deleteRouteBookmark(bookmarkId: String) {
        viewModelScope.launch {
            routeBookmarks = bookmarkController.deleteRouteBookmark(bookmarkId)
            if (activeBookmarkId == bookmarkId) {
                activeBookmarkId = null
            }
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
        val result = activeRouteController.activateRouteTracking(
            state = uiState.value,
            newRouteId = UUID.randomUUID().toString(),
            startedAtNow = defaultRouteStartDateTime().toString()
        ) ?: return
        updateUiState { result.state }
        persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    private fun pauseRoute() {
        val result = activeRouteController.pauseRoute(uiState.value) ?: return
        updateUiState { result.state }
        persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    private fun resumeRoute() {
        val result = activeRouteController.resumeRoute(
            state = uiState.value,
            newRouteId = UUID.randomUUID().toString(),
            startedAtNow = defaultRouteStartDateTime().toString()
        )
        updateUiState { result.state }
        persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    private fun finishRoute() {
        val result = activeRouteController.finishRoute(uiState.value, progressMetrics) ?: return
        updateUiState { result.state }
        persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    private fun cancelRoute() {
        val result = activeRouteController.cancelRoute(uiState.value) ?: return
        updateUiState { result.state }
        persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    private fun updateFeedback(feedback: RouteFeedback) {
        val result = activeRouteController.updateFeedback(uiState.value, feedback) ?: return
        updateUiState { result.state }
        persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
        syncFeedbackToBackend(feedback)
    }

    private fun markRouteStopVisited(poiId: Int) {
        val result = activeRouteController.markRouteStopVisited(
            state = uiState.value,
            poiId = poiId,
            isNetworkAvailable = repository.isNetworkAvailable()
        ) ?: return
        updateUiState { result.state }
        syncVisitedPoisToBackend(result.syncVisitedPoiIds)
        persistRouteSession(
            status = result.statusToPersist,
            visitedIds = result.visitedPoiIds,
            skippedIds = result.skippedPoiIds
        )
        result.snapshotToSave?.let { snapshot ->
            viewModelScope.launch {
                repository.saveSnapshot(snapshot)
            }
        }
        result.approachRefresh?.let { refresh ->
            refreshActiveRouteApproachLeg(
                previousResponse = refresh.previousResponse,
                currentLocation = refresh.currentLocation,
                nextTarget = refresh.nextTarget
            )
        }
    }

    private fun skipRouteStop(poiId: Int) {
        val result = activeRouteController.skipRouteStop(
            state = uiState.value,
            poiId = poiId,
            isNetworkAvailable = repository.isNetworkAvailable()
        ) ?: return
        updateUiState { result.state }
        result.snapshotToSave?.let { snapshot ->
            viewModelScope.launch {
                repository.saveSnapshot(snapshot)
            }
        }
        syncSkippedPoisToBackend(result.syncSkippedPoiIds)
        persistRouteSession(
            status = result.statusToPersist,
            visitedIds = result.visitedPoiIds,
            skippedIds = result.skippedPoiIds
        )
        result.approachRefresh?.let { refresh ->
            refreshActiveRouteApproachLeg(
                previousResponse = refresh.previousResponse,
                currentLocation = refresh.currentLocation,
                nextTarget = refresh.nextTarget
            )
        }
    }

    private fun recalculateFromCurrentLocation() {
        val location = currentRouteLocation ?: return
        recalculateRouteFromPoint(location, false, emptyList())
    }

    private fun removePreviewStop(poiId: Int) {
        viewModelScope.launch {
            isRerouting = true
            clearRouteMessages()
            val result = routePreviewController.removeStop(
                state = uiState.value,
                poiId = poiId,
                offlineRouteGenerationMessage = offlineRouteGenerationMessage,
                routeGenerationFailedMessage = routeGenerationFailedMessage
            )
            updateUiState {
                result.state.copy(
                    isRerouting = false,
                    routeError = result.error ?: result.state.routeError
                )
            }
        }
    }

    private fun movePreviewStop(poiId: Int, direction: Int) {
        viewModelScope.launch {
            isRerouting = true
            clearRouteMessages()
            val result = routePreviewController.moveStop(
                state = uiState.value,
                poiId = poiId,
                direction = direction,
                offlineRouteGenerationMessage = offlineRouteGenerationMessage,
                routeGenerationFailedMessage = routeGenerationFailedMessage
            )
            updateUiState {
                result.state.copy(
                    isRerouting = false,
                    routeError = result.error ?: result.state.routeError
                )
            }
        }
    }

    private fun replacePreviewStop(poiId: Int, preferredPoiId: Int? = null) {
        viewModelScope.launch {
            isRerouting = true
            clearRouteMessages()
            val result = routePreviewController.replaceStop(
                state = uiState.value,
                poiId = poiId,
                preferredPoiId = preferredPoiId,
                offlineRouteGenerationMessage = offlineRouteGenerationMessage,
                routeGenerationFailedMessage = routeGenerationFailedMessage
            )
            updateUiState {
                result.state.copy(
                    isRerouting = false,
                    routeError = result.error ?: result.state.routeError
                )
            }
        }
    }

    private fun handleTrackedLocation(routeLocation: RoutePoint) {
        val result = activeRouteController.handleTrackedLocation(
            state = uiState.value,
            routeLocation = routeLocation,
            nowMs = System.currentTimeMillis(),
            isNetworkAvailable = repository.isNetworkAvailable(),
            isRerouting = isRerouting
        )
        updateUiState { result.state }
        result.statusToPersist?.let { status ->
            persistRouteSession(
                status = status,
                visitedIds = result.visitedPoiIds,
                skippedIds = result.skippedPoiIds
            )
        }
        syncVisitedPoisToBackend(result.syncVisitedPoiIds)
        result.snapshotToSave?.let { snapshot ->
            viewModelScope.launch {
                repository.saveSnapshot(snapshot)
            }
        }
        result.approachRefresh?.let { refresh ->
            refreshActiveRouteApproachLeg(
                previousResponse = refresh.previousResponse,
                currentLocation = refresh.currentLocation,
                nextTarget = refresh.nextTarget,
                autoTriggered = result.approachRefreshAutoTriggered
            )
        }
    }

    private fun handleTrackingError(message: String) {
        val result = activeRouteController.handleTrackingError(uiState.value, message) ?: return
        updateUiState { result.state }
        persistRouteSession(
            status = result.status,
            routeIdValue = result.routeId,
            startedAtValue = result.startedAt,
            feedback = result.feedback
        )
    }

    private fun downloadOfflineMap() {
        val city = selectedCity ?: return
        isOfflineMapBusy = true
        offlineMapProgress = OfflineDownloadProgress(0, 0, 0.0)
        offlineMapMessage = null
        val started = offlineMapController.downloadCityRegion(
            city = city,
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
                    offlineStoredRegion = offlineMapController.findStoredRegion(city.slug)
                }
            },
            onError = { error ->
                isOfflineMapBusy = false
                offlineMapMessage = "$offlineMapDownloadFailedMessage $error"
            }
        )
        if (!started) {
            isOfflineMapBusy = false
            offlineMapProgress = null
            offlineMapMessage = null
            return
        }
    }

    private fun deleteOfflineMap() {
        val city = selectedCity ?: return
        val storedRegion = offlineStoredRegion ?: return

        isOfflineMapBusy = true
        offlineMapController.deleteRegion(
            storedRegion = storedRegion,
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
        val localState = plannerBootstrapUseCase.loadLocalState()
        routeBookmarks = localState.bookmarks
        routeHistory = localState.history
        val activeSession = localState.activeSession
        val savedSnapshot = localState.savedSnapshot
        var restoredCityToken = savedSnapshot?.request?.city ?: savedSnapshot?.response?.city
        pendingSyncOperationCount = localState.pendingSyncOperationCount

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

        plannerBootstrapUseCase.scheduleImmediateSync()

        val remoteSession = plannerBootstrapUseCase.findRestorableRemoteSession(
            deviceId = deviceId,
            routeId = routeId,
            routeSessionStatus = routeSessionStatus
        )
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

        loadCities(restoredCityToken)
        refreshRouteHistory(forceRefresh = true)
    }

    private fun restoreSnapshot(snapshot: SavedRouteSnapshot) {
        updateUiState { state ->
            PlannerStateReducer.restoreSnapshot(
                state = state,
                snapshot = snapshot,
                parsedStartDateTime = parseRouteStartDateTime(snapshot.request.startDateTime)
            )
        }
    }

    private fun restoreActiveSession(session: ActiveRouteSession) {
        updateUiState { state ->
            activeRouteController.restoreActiveSession(state, session)
        }
    }

    private suspend fun loadCities(restoredCityToken: String?) {
        plannerCatalogUseCase.loadCities(
            input = CityCatalogLoadInput(
                restoredCityToken = restoredCityToken,
                currentState = uiState.value,
                offlineCitiesFallbackMessage = offlineCitiesFallbackMessage
            )
        ) { stage ->
            updateUiState { state ->
                state.copy(
                    cities = stage.cities,
                    selectedCity = stage.selectedCity,
                    currentRouteRequest = stage.currentRouteRequest,
                    routeResponse = stage.routeResponse,
                    startPoint = stage.startPoint,
                    offlineStatusMessage = stage.offlineStatusMessage,
                    pendingSyncOperationCount = stage.pendingSyncOperationCount
                        ?: state.pendingSyncOperationCount
                )
            }
            if (stage.shouldLoadPoisForSelectedCity && stage.selectedCity != null) {
                loadPoisForCity(stage.selectedCity)
            }
        }
    }

    private suspend fun loadPoisForCity(city: City) {
        plannerCatalogUseCase.loadPoisForCity(
            input = PoiCatalogLoadInput(
                city = city,
                currentState = uiState.value,
                poiPreviewFailedMessage = poiPreviewFailedMessage,
                offlinePoisFallbackMessage = offlinePoisFallbackMessage
            )
        ) { stage ->
            updateUiState { stage.state }
        }
    }

    private fun refreshPendingSyncOperationCount() {
        viewModelScope.launch {
            pendingSyncOperationCount = repository.getPendingSyncOperationCount()
        }
    }

    private fun clearRouteMessages() {
        updateUiState { state ->
            PlannerStateReducer.clearRouteMessages(state)
        }
    }

    private fun resetRouteSession(clearStoredSession: Boolean = true) {
        updateUiState { state ->
            activeRouteController.resetRouteSession(state)
        }
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

    private suspend fun enqueueRouteSessionSync(
        sessionRouteId: String,
        status: RouteSessionStatus,
        startedAtValue: String,
        snapshot: SavedRouteSnapshot,
        finishedAt: String? = null
    ) {
        pendingSyncOperationCount = activeRouteController.enqueueRouteSessionSync(
            deviceId = deviceId,
            sessionRouteId = sessionRouteId,
            status = status,
            startedAt = startedAtValue,
            snapshot = snapshot,
            finishedAt = finishedAt
        )
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
        currentTargetPoiId = nextPendingPoi(snapshot.response.route, visitedIds, skippedIds)?.poiId
        viewModelScope.launch {
            val result = activeRouteController.persistRouteSession(
                PersistRouteSessionInput(
                    deviceId = deviceId,
                    routeId = savedRouteId,
                    status = status,
                    startedAt = savedStartedAt,
                    snapshot = snapshot,
                    visitedPoiIds = visitedIds,
                    skippedPoiIds = skippedIds,
                    feedback = feedback,
                    currentHistory = routeHistory
                )
            )
            currentTargetPoiId = result.nextTargetPoiId
            routeHistory = result.history
            pendingSyncOperationCount = result.pendingSyncOperationCount
        }
    }

    private suspend fun refreshRouteHistory(forceRefresh: Boolean) {
        isRouteHistoryLoading = true
        routeHistoryError = null
        val result = routeHistoryController.refreshRouteHistory(
            deviceId = deviceId,
            forceRefresh = forceRefresh,
            currentHistory = routeHistory,
            currentEntry = buildCurrentRouteHistoryEntry(),
            routeHistoryLoadFailedMessage = routeHistoryLoadFailedMessage
        )
        routeHistory = result.history
        routeHistoryError = result.error
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

    private fun syncVisitedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        viewModelScope.launch {
            pendingSyncOperationCount = activeRouteController.syncVisitedPois(
                sessionId = sessionRouteId,
                poiIds = poiIds
            )
        }
    }

    private fun syncSkippedPoisToBackend(poiIds: List<Int>) {
        val sessionRouteId = routeId ?: return
        if (poiIds.isEmpty()) {
            return
        }

        viewModelScope.launch {
            pendingSyncOperationCount = activeRouteController.syncSkippedPois(
                sessionId = sessionRouteId,
                poiIds = poiIds
            )
        }
    }

    private fun syncFeedbackToBackend(feedback: RouteFeedback) {
        val sessionRouteId = routeId ?: return
        if (feedback.rating !in 1..5) {
            return
        }

        viewModelScope.launch {
            pendingSyncOperationCount = activeRouteController.syncFeedback(
                sessionId = sessionRouteId,
                feedback = feedback
            )
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
