package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.route.defaultRouteStartDateTime
import com.example.smarttourism.features.planner.state.PlannerEvent
import com.example.smarttourism.features.planner.state.PlannerStateReducer
import com.example.smarttourism.features.planner.state.RoutePlannerUiState

internal fun interface PlannerEventHandler {
    fun handle(event: PlannerEvent): Boolean
}

internal interface PlannerEventActions {
    fun updateUiState(transform: (RoutePlannerUiState) -> RoutePlannerUiState)
    fun initialize()
    fun loadRouteHistory(forceRefresh: Boolean)
    fun selectCity(city: City)
    fun loadCuratedRoutes()
    fun openCuratedRoute(routeId: Int)
    fun generateRoute()
    fun addPublicTransportToRoute()
    fun saveCurrentRouteBookmark()
    fun openRouteBookmark(bookmarkId: String)
    fun deleteRouteBookmark(bookmarkId: String)
    fun activateRouteTracking()
    fun pauseRoute()
    fun resumeRoute()
    fun finishRoute()
    fun cancelRoute()
    fun updateFeedback(feedback: RouteFeedback)
    fun markRouteStopVisited(poiId: Int)
    fun skipRouteStop(poiId: Int)
    fun removePreviewStop(poiId: Int)
    fun movePreviewStop(poiId: Int, direction: Int)
    fun replacePreviewStop(poiId: Int, preferredPoiId: Int?)
    fun recalculateFromCurrentLocation()
    fun handleTrackedLocation(routeLocation: RoutePoint)
    fun handleTrackingError(message: String)
    fun downloadOfflineMap()
    fun deleteOfflineMap()
    fun clearDisplayedRoute(cancelActiveSession: Boolean)
}

internal class PlannerEventDispatcher(
    actions: PlannerEventActions
) {
    private val eventHandlers = listOf(
        PlannerInitializationHandler(
            initialize = actions::initialize,
            selectCity = actions::selectCity
        ),
        HistoryHandler(
            loadRouteHistory = actions::loadRouteHistory
        ),
        CuratedRoutesHandler(
            loadCuratedRoutes = actions::loadCuratedRoutes,
            openCuratedRoute = actions::openCuratedRoute
        ),
        RoutePlanningHandler(
            updateUiState = actions::updateUiState,
            generateRoute = actions::generateRoute,
            addPublicTransportToRoute = actions::addPublicTransportToRoute,
            removePreviewStop = actions::removePreviewStop,
            movePreviewStop = actions::movePreviewStop,
            replacePreviewStop = actions::replacePreviewStop,
            recalculateFromCurrentLocation = actions::recalculateFromCurrentLocation,
            clearDisplayedRoute = actions::clearDisplayedRoute
        ),
        BookmarksHandler(
            saveCurrentRouteBookmark = actions::saveCurrentRouteBookmark,
            openRouteBookmark = actions::openRouteBookmark,
            deleteRouteBookmark = actions::deleteRouteBookmark
        ),
        ActiveRouteHandler(
            activateRouteTracking = actions::activateRouteTracking,
            pauseRoute = actions::pauseRoute,
            resumeRoute = actions::resumeRoute,
            finishRoute = actions::finishRoute,
            cancelRoute = actions::cancelRoute,
            updateFeedback = actions::updateFeedback,
            markRouteStopVisited = actions::markRouteStopVisited,
            skipRouteStop = actions::skipRouteStop,
            handleTrackedLocation = actions::handleTrackedLocation,
            handleTrackingError = actions::handleTrackingError
        ),
        OfflineMapHandler(
            downloadOfflineMap = actions::downloadOfflineMap,
            deleteOfflineMap = actions::deleteOfflineMap
        )
    )

    fun dispatch(event: PlannerEvent) {
        check(eventHandlers.any { handler -> handler.handle(event) }) {
            "Unhandled planner event: $event"
        }
    }
}

internal class PlannerInitializationHandler(
    private val initialize: () -> Unit,
    private val selectCity: (City) -> Unit
) : PlannerEventHandler {
    override fun handle(event: PlannerEvent): Boolean =
        when (event) {
            PlannerEvent.Initialize -> {
                initialize()
                true
            }

            is PlannerEvent.SelectCity -> {
                selectCity(event.city)
                true
            }

            else -> false
        }
}

internal class HistoryHandler(
    private val loadRouteHistory: (forceRefresh: Boolean) -> Unit
) : PlannerEventHandler {
    override fun handle(event: PlannerEvent): Boolean =
        when (event) {
            is PlannerEvent.LoadRouteHistory -> {
                loadRouteHistory(event.forceRefresh)
                true
            }

            else -> false
        }
}

internal class CuratedRoutesHandler(
    private val loadCuratedRoutes: () -> Unit,
    private val openCuratedRoute: (Int) -> Unit
) : PlannerEventHandler {
    override fun handle(event: PlannerEvent): Boolean =
        when (event) {
            PlannerEvent.LoadCuratedRoutes -> {
                loadCuratedRoutes()
                true
            }

            is PlannerEvent.OpenCuratedRoute -> {
                openCuratedRoute(event.routeId)
                true
            }

            else -> false
        }
}

internal class RoutePlanningHandler(
    private val updateUiState: ((RoutePlannerUiState) -> RoutePlannerUiState) -> Unit,
    private val generateRoute: () -> Unit,
    private val addPublicTransportToRoute: () -> Unit,
    private val removePreviewStop: (Int) -> Unit,
    private val movePreviewStop: (poiId: Int, direction: Int) -> Unit,
    private val replacePreviewStop: (poiId: Int, preferredPoiId: Int?) -> Unit,
    private val recalculateFromCurrentLocation: () -> Unit,
    private val clearDisplayedRoute: (cancelActiveSession: Boolean) -> Unit
) : PlannerEventHandler {
    override fun handle(event: PlannerEvent): Boolean =
        when (event) {
            is PlannerEvent.UpdateStartPoint -> {
                updateUiState { state ->
                    PlannerStateReducer.updateStartPoint(
                        state,
                        RoutePoint(lat = event.lat, lon = event.lon)
                    )
                }
                true
            }

            is PlannerEvent.UpdateAvailableMinutes -> {
                updateUiState { state -> PlannerStateReducer.updateAvailableMinutes(state, event.value) }
                true
            }

            is PlannerEvent.ToggleInterest -> {
                updateUiState { state ->
                    PlannerStateReducer.toggleInterest(state, event.interest, event.checked)
                }
                true
            }

            is PlannerEvent.UpdatePace -> {
                updateUiState { state -> PlannerStateReducer.updatePace(state, event.value) }
                true
            }

            is PlannerEvent.UpdateReturnToStart -> {
                updateUiState { state -> PlannerStateReducer.updateReturnToStart(state, event.value) }
                true
            }

            is PlannerEvent.UpdateRespectOpeningHours -> {
                updateUiState { state -> PlannerStateReducer.updateRespectOpeningHours(state, event.value) }
                true
            }

            is PlannerEvent.UpdateAllowPublicTransport -> {
                updateUiState { state -> PlannerStateReducer.updateAllowPublicTransport(state, event.value) }
                true
            }

            PlannerEvent.UseCurrentTime -> {
                updateUiState { state -> PlannerStateReducer.useCurrentTime(state, defaultRouteStartDateTime()) }
                true
            }

            is PlannerEvent.UpdateStartDateTime -> {
                updateUiState { state -> PlannerStateReducer.updateStartDateTime(state, event.value) }
                true
            }

            is PlannerEvent.ToggleRequiredPoi -> {
                updateUiState { state -> PlannerStateReducer.toggleRequiredPoi(state, event.poiId) }
                true
            }

            is PlannerEvent.RemoveRequiredPoi -> {
                updateUiState { state -> PlannerStateReducer.removeRequiredPoi(state, event.poiId) }
                true
            }

            PlannerEvent.ClearRequiredPois -> {
                updateUiState { state -> PlannerStateReducer.clearRequiredPois(state) }
                true
            }

            PlannerEvent.GenerateRoute -> {
                generateRoute()
                true
            }

            PlannerEvent.AddPublicTransportToRoute -> {
                addPublicTransportToRoute()
                true
            }

            is PlannerEvent.RemovePreviewStop -> {
                removePreviewStop(event.poiId)
                true
            }

            is PlannerEvent.MovePreviewStop -> {
                movePreviewStop(event.poiId, event.direction)
                true
            }

            is PlannerEvent.ReplacePreviewStop -> {
                replacePreviewStop(event.poiId, event.preferredPoiId)
                true
            }

            PlannerEvent.RecalculateFromCurrentLocation -> {
                recalculateFromCurrentLocation()
                true
            }

            is PlannerEvent.ClearDisplayedRoute -> {
                clearDisplayedRoute(event.cancelActiveSession)
                true
            }

            else -> false
        }
}

internal class BookmarksHandler(
    private val saveCurrentRouteBookmark: () -> Unit,
    private val openRouteBookmark: (String) -> Unit,
    private val deleteRouteBookmark: (String) -> Unit
) : PlannerEventHandler {
    override fun handle(event: PlannerEvent): Boolean =
        when (event) {
            PlannerEvent.SaveCurrentRouteBookmark -> {
                saveCurrentRouteBookmark()
                true
            }

            is PlannerEvent.OpenRouteBookmark -> {
                openRouteBookmark(event.bookmarkId)
                true
            }

            is PlannerEvent.DeleteRouteBookmark -> {
                deleteRouteBookmark(event.bookmarkId)
                true
            }

            else -> false
        }
}

internal class ActiveRouteHandler(
    private val activateRouteTracking: () -> Unit,
    private val pauseRoute: () -> Unit,
    private val resumeRoute: () -> Unit,
    private val finishRoute: () -> Unit,
    private val cancelRoute: () -> Unit,
    private val updateFeedback: (RouteFeedback) -> Unit,
    private val markRouteStopVisited: (Int) -> Unit,
    private val skipRouteStop: (Int) -> Unit,
    private val handleTrackedLocation: (RoutePoint) -> Unit,
    private val handleTrackingError: (String) -> Unit
) : PlannerEventHandler {
    override fun handle(event: PlannerEvent): Boolean =
        when (event) {
            PlannerEvent.ActivateRouteTracking -> {
                activateRouteTracking()
                true
            }

            PlannerEvent.PauseRoute -> {
                pauseRoute()
                true
            }

            PlannerEvent.ResumeRoute -> {
                resumeRoute()
                true
            }

            PlannerEvent.FinishRoute -> {
                finishRoute()
                true
            }

            PlannerEvent.CancelRoute -> {
                cancelRoute()
                true
            }

            is PlannerEvent.UpdateFeedback -> {
                updateFeedback(event.feedback)
                true
            }

            is PlannerEvent.MarkRouteStopVisited -> {
                markRouteStopVisited(event.poiId)
                true
            }

            is PlannerEvent.SkipRouteStop -> {
                skipRouteStop(event.poiId)
                true
            }

            is PlannerEvent.TrackedLocation -> {
                handleTrackedLocation(event.point)
                true
            }

            is PlannerEvent.TrackingError -> {
                handleTrackingError(event.message)
                true
            }

            else -> false
        }
}

internal class OfflineMapHandler(
    private val downloadOfflineMap: () -> Unit,
    private val deleteOfflineMap: () -> Unit
) : PlannerEventHandler {
    override fun handle(event: PlannerEvent): Boolean =
        when (event) {
            PlannerEvent.DownloadOfflineMap -> {
                downloadOfflineMap()
                true
            }

            PlannerEvent.DeleteOfflineMap -> {
                deleteOfflineMap()
                true
            }

            else -> false
        }
}
