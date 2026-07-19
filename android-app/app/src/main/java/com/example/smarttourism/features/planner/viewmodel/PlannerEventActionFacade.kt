package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.state.RoutePlannerUiState

internal class PlannerEventActionFacade(
    private val state: PlannerStateStore,
    private val catalogActions: PlannerCatalogActions,
    private val curatedRouteActions: PlannerCuratedRouteActions,
    private val routePlanningActions: PlannerRoutePlanningActions,
    private val bookmarkActions: PlannerBookmarkActions,
    private val activeRouteActions: PlannerActiveRouteActions,
    private val offlineMapActions: PlannerOfflineMapActions,
    private val sessionCoordinator: PlannerSessionCoordinator
) : PlannerEventActions {
    override fun updateUiState(transform: (RoutePlannerUiState) -> RoutePlannerUiState) {
        state.update(transform)
    }

    override fun initialize() {
        catalogActions.initialize()
    }

    override fun loadRouteHistory(forceRefresh: Boolean) {
        catalogActions.loadRouteHistory(forceRefresh)
    }

    override fun selectCity(city: City) {
        catalogActions.selectCity(city)
    }

    override fun loadCuratedRoutes() {
        curatedRouteActions.loadCuratedRoutes()
    }

    override fun openCuratedRoute(routeId: Int) {
        curatedRouteActions.openCuratedRoute(routeId)
    }

    override fun generateRoute() {
        routePlanningActions.generateRoute()
    }

    override fun addPublicTransportToRoute() {
        routePlanningActions.addPublicTransportToRoute()
    }

    override fun saveCurrentRouteBookmark() {
        bookmarkActions.saveCurrentRouteBookmark()
    }

    override fun openRouteBookmark(bookmarkId: String) {
        bookmarkActions.openRouteBookmark(bookmarkId)
    }

    override fun deleteRouteBookmark(bookmarkId: String) {
        bookmarkActions.deleteRouteBookmark(bookmarkId)
    }

    override fun activateRouteTracking() {
        activeRouteActions.activateRouteTracking()
    }

    override fun pauseRoute() {
        activeRouteActions.pauseRoute()
    }

    override fun resumeRoute() {
        activeRouteActions.resumeRoute()
    }

    override fun finishRoute() {
        activeRouteActions.finishRoute()
    }

    override fun cancelRoute() {
        activeRouteActions.cancelRoute()
    }

    override fun updateFeedback(feedback: RouteFeedback) {
        activeRouteActions.updateFeedback(feedback)
    }

    override fun markRouteStopVisited(poiId: Int) {
        activeRouteActions.markRouteStopVisited(poiId)
    }

    override fun skipRouteStop(poiId: Int) {
        activeRouteActions.skipRouteStop(poiId)
    }

    override fun removePreviewStop(poiId: Int) {
        routePlanningActions.removePreviewStop(poiId)
    }

    override fun movePreviewStop(poiId: Int, direction: Int) {
        routePlanningActions.movePreviewStop(poiId, direction)
    }

    override fun replacePreviewStop(poiId: Int, preferredPoiId: Int?) {
        routePlanningActions.replacePreviewStop(poiId, preferredPoiId)
    }

    override fun recalculateFromCurrentLocation() {
        routePlanningActions.recalculateFromCurrentLocation()
    }

    override fun handleTrackedLocation(routeLocation: RoutePoint) {
        activeRouteActions.handleTrackedLocation(routeLocation)
    }

    override fun handleTrackingError(message: String) {
        activeRouteActions.handleTrackingError(message)
    }

    override fun downloadOfflineMap() {
        offlineMapActions.downloadOfflineMap()
    }

    override fun deleteOfflineMap() {
        offlineMapActions.deleteOfflineMap()
    }

    override fun clearDisplayedRoute(cancelActiveSession: Boolean) {
        sessionCoordinator.clearDisplayedRoute(cancelActiveSession)
    }
}
