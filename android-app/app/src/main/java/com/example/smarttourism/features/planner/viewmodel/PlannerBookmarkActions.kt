package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.application.BookmarkController
import com.example.smarttourism.features.planner.domain.route.parseRouteStartDateTime
import com.example.smarttourism.features.planner.state.PlannerStateReducer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

internal class PlannerBookmarkActions(
    private val state: PlannerStateStore,
    private val scope: CoroutineScope,
    private val bookmarkController: BookmarkController,
    private val catalogActions: PlannerCatalogActions,
    private val sessionCoordinator: PlannerSessionCoordinator
) {
    fun saveCurrentRouteBookmark() {
        val snapshot = sessionCoordinator.currentRouteSnapshot() ?: return
        scope.launch {
            val result = bookmarkController.saveCurrentRouteBookmark(
                snapshot = snapshot,
                activeBookmarkId = state.activeBookmarkId,
                routeBookmarks = state.routeBookmarks,
                selectedCityName = state.selectedCity?.name ?: snapshot.response.city,
                selectedCitySlug = state.selectedCity?.slug ?: snapshot.request.city
            )
            state.activeBookmarkId = result.activeBookmarkId
            state.routeBookmarks = result.bookmarks
        }
    }

    fun openRouteBookmark(bookmarkId: String) {
        scope.launch {
            val bookmark = bookmarkController.loadRouteBookmark(bookmarkId) ?: return@launch
            sessionCoordinator.resetRouteSession()
            sessionCoordinator.clearRouteMessages()
            state.activeBookmarkId = bookmark.id
            restoreSnapshot(bookmark.snapshot)
            val bookmarkCity = catalogActions.cityForBookmark(
                citySlug = bookmark.citySlug,
                requestCity = bookmark.snapshot.request.city
            )
            if (bookmarkCity != null) {
                catalogActions.loadPoisForCity(bookmarkCity)
            }
            bookmarkController.saveSnapshot(bookmark.snapshot)
        }
    }

    fun deleteRouteBookmark(bookmarkId: String) {
        scope.launch {
            state.routeBookmarks = bookmarkController.deleteRouteBookmark(bookmarkId)
            if (state.activeBookmarkId == bookmarkId) {
                state.activeBookmarkId = null
            }
        }
    }

    private fun restoreSnapshot(snapshot: SavedRouteSnapshot) {
        state.update { currentState ->
            PlannerStateReducer.restoreSnapshot(
                state = currentState,
                snapshot = snapshot,
                parsedStartDateTime = parseRouteStartDateTime(snapshot.request.startDateTime)
            )
        }
    }
}
