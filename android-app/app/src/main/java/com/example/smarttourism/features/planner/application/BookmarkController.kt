package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.domain.history.defaultRouteBookmarkTitle
import java.util.UUID
import javax.inject.Inject

internal data class BookmarkSaveResult(
    val activeBookmarkId: String,
    val bookmarks: List<RouteBookmark>
)

internal class BookmarkController @Inject constructor(
    private val repository: PlannerRepository
) {
    suspend fun saveCurrentRouteBookmark(
        snapshot: SavedRouteSnapshot,
        activeBookmarkId: String?,
        routeBookmarks: List<RouteBookmark>,
        selectedCityName: String?,
        selectedCitySlug: String?
    ): BookmarkSaveResult {
        val now = System.currentTimeMillis()
        val existingBookmark = activeBookmarkId?.let { bookmarkId ->
            routeBookmarks.firstOrNull { bookmark -> bookmark.id == bookmarkId }
        }
        val bookmark = RouteBookmark(
            id = existingBookmark?.id ?: UUID.randomUUID().toString(),
            title = existingBookmark?.title
                ?: defaultRouteBookmarkTitle(snapshot, selectedCityName ?: snapshot.response.city),
            citySlug = selectedCitySlug ?: snapshot.request.city,
            snapshot = snapshot,
            createdAtEpochMs = existingBookmark?.createdAtEpochMs ?: now,
            updatedAtEpochMs = now
        )
        repository.saveRouteBookmark(bookmark)
        return BookmarkSaveResult(
            activeBookmarkId = bookmark.id,
            bookmarks = repository.loadRouteBookmarks()
        )
    }

    suspend fun loadRouteBookmark(bookmarkId: String): RouteBookmark? =
        repository.loadRouteBookmark(bookmarkId)

    suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) {
        repository.saveSnapshot(snapshot)
    }

    suspend fun deleteRouteBookmark(bookmarkId: String): List<RouteBookmark> {
        repository.deleteRouteBookmark(bookmarkId)
        return repository.loadRouteBookmarks()
    }
}
