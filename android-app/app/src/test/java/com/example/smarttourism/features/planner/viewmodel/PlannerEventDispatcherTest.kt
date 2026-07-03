package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.state.PlannerEvent
import com.example.smarttourism.features.planner.state.RoutePlannerUiState
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerEventDispatcherTest {
    @Test
    fun dispatchRoutesReducerEventsThroughPlanningHandler() {
        val actions = RecordingPlannerEventActions()
        val dispatcher = PlannerEventDispatcher(actions)

        dispatcher.dispatch(PlannerEvent.UpdateAvailableMinutes(75))
        dispatcher.dispatch(PlannerEvent.ToggleInterest("museum", checked = true))

        assertEquals(75, actions.state.availableMinutes)
        assertEquals(listOf("museum"), actions.state.selectedInterests)
    }

    @Test
    fun dispatchRoutesWorkflowEventsToFeatureActions() {
        val actions = RecordingPlannerEventActions()
        val dispatcher = PlannerEventDispatcher(actions)

        dispatcher.dispatch(PlannerEvent.GenerateRoute)
        dispatcher.dispatch(PlannerEvent.SaveCurrentRouteBookmark)
        dispatcher.dispatch(PlannerEvent.ActivateRouteTracking)
        dispatcher.dispatch(PlannerEvent.DownloadOfflineMap)
        dispatcher.dispatch(PlannerEvent.LoadCuratedRoutes)
        dispatcher.dispatch(PlannerEvent.OpenCuratedRoute(7))

        assertEquals(
            listOf(
                "generateRoute",
                "saveCurrentRouteBookmark",
                "activateRouteTracking",
                "downloadOfflineMap",
                "loadCuratedRoutes",
                "openCuratedRoute:7"
            ),
            actions.calls
        )
    }

    private class RecordingPlannerEventActions : PlannerEventActions {
        var state = RoutePlannerUiState()
        val calls = mutableListOf<String>()

        override fun updateUiState(transform: (RoutePlannerUiState) -> RoutePlannerUiState) {
            state = transform(state)
        }

        override fun initialize() {
            calls += "initialize"
        }

        override fun loadRouteHistory(forceRefresh: Boolean) {
            calls += "loadRouteHistory:$forceRefresh"
        }

        override fun selectCity(city: City) {
            calls += "selectCity:${city.slug}"
        }

        override fun loadCuratedRoutes() {
            calls += "loadCuratedRoutes"
        }

        override fun openCuratedRoute(routeId: Int) {
            calls += "openCuratedRoute:$routeId"
        }

        override fun generateRoute() {
            calls += "generateRoute"
        }

        override fun saveCurrentRouteBookmark() {
            calls += "saveCurrentRouteBookmark"
        }

        override fun openRouteBookmark(bookmarkId: String) {
            calls += "openRouteBookmark:$bookmarkId"
        }

        override fun deleteRouteBookmark(bookmarkId: String) {
            calls += "deleteRouteBookmark:$bookmarkId"
        }

        override fun activateRouteTracking() {
            calls += "activateRouteTracking"
        }

        override fun pauseRoute() {
            calls += "pauseRoute"
        }

        override fun resumeRoute() {
            calls += "resumeRoute"
        }

        override fun finishRoute() {
            calls += "finishRoute"
        }

        override fun cancelRoute() {
            calls += "cancelRoute"
        }

        override fun updateFeedback(feedback: RouteFeedback) {
            calls += "updateFeedback:${feedback.rating}"
        }

        override fun markRouteStopVisited(poiId: Int) {
            calls += "markRouteStopVisited:$poiId"
        }

        override fun skipRouteStop(poiId: Int) {
            calls += "skipRouteStop:$poiId"
        }

        override fun removePreviewStop(poiId: Int) {
            calls += "removePreviewStop:$poiId"
        }

        override fun movePreviewStop(poiId: Int, direction: Int) {
            calls += "movePreviewStop:$poiId:$direction"
        }

        override fun replacePreviewStop(poiId: Int, preferredPoiId: Int?) {
            calls += "replacePreviewStop:$poiId:$preferredPoiId"
        }

        override fun recalculateFromCurrentLocation() {
            calls += "recalculateFromCurrentLocation"
        }

        override fun handleTrackedLocation(routeLocation: RoutePoint) {
            calls += "handleTrackedLocation:${routeLocation.lat}:${routeLocation.lon}"
        }

        override fun handleTrackingError(message: String) {
            calls += "handleTrackingError:$message"
        }

        override fun downloadOfflineMap() {
            calls += "downloadOfflineMap"
        }

        override fun deleteOfflineMap() {
            calls += "deleteOfflineMap"
        }

        override fun clearDisplayedRoute(cancelActiveSession: Boolean) {
            calls += "clearDisplayedRoute:$cancelActiveSession"
        }
    }
}
