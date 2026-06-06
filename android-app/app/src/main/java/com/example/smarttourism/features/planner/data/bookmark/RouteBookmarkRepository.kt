package com.example.smarttourism.features.planner.data.bookmark

import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import javax.inject.Inject

internal interface RouteBookmarkRepository {
    suspend fun saveBookmark(bookmark: RouteBookmark)

    suspend fun loadBookmarks(): List<RouteBookmark>

    suspend fun loadBookmark(bookmarkId: String): RouteBookmark?

    suspend fun deleteBookmark(bookmarkId: String)
}

internal class DefaultRouteBookmarkRepository @Inject constructor(
    private val localDataSource: PlannerLocalDataSource
) : RouteBookmarkRepository {
    override suspend fun saveBookmark(bookmark: RouteBookmark) {
        localDataSource.saveRouteBookmark(bookmark)
    }

    override suspend fun loadBookmarks(): List<RouteBookmark> =
        localDataSource.loadRouteBookmarks()

    override suspend fun loadBookmark(bookmarkId: String): RouteBookmark? =
        localDataSource.loadRouteBookmark(bookmarkId)

    override suspend fun deleteBookmark(bookmarkId: String) {
        localDataSource.deleteRouteBookmark(bookmarkId)
    }
}
