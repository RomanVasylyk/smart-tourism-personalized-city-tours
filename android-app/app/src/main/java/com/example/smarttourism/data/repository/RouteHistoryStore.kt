package com.example.smarttourism.data.repository

import com.example.smarttourism.data.local.BookmarkedRouteEntity
import com.example.smarttourism.data.local.CachedLastRouteEntity
import com.example.smarttourism.data.local.CachedRouteSessionEntity
import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.local.RouteHistoryEntryEntity
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.ActiveRouteSessionCache
import com.example.smarttourism.data.model.CacheEnvelopeType
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.RouteHistoryEntryCache
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.model.SavedRouteSnapshotCache
import com.example.smarttourism.features.planner.data.mapper.toDomain
import com.example.smarttourism.features.planner.data.mapper.toDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val LastRouteCacheKey = "last_route"

internal class RouteHistoryStore(
    private val dao: OfflineCacheDao,
    private val serializer: CacheEnvelopeSerializer
) {
    suspend fun saveLastRoute(snapshot: SavedRouteSnapshot) {
        dao.upsertLastRoute(
            CachedLastRouteEntity(
                cacheKey = LastRouteCacheKey,
                snapshotJson = serializer.toVersionedJson(CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT, snapshot.toCache()),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun loadLastRoute(): SavedRouteSnapshot? =
        dao
            .getLastRoute()
            ?.snapshotJson
            ?.let { rawJson -> savedRouteSnapshotFromJsonOrNull(rawJson) }

    suspend fun saveRouteBookmark(bookmark: RouteBookmark) {
        dao.upsertBookmarkedRoute(
            BookmarkedRouteEntity(
                bookmarkId = bookmark.id,
                title = bookmark.title,
                citySlug = bookmark.citySlug,
                snapshotJson = serializer.toVersionedJson(CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT, bookmark.snapshot.toCache()),
                createdAtEpochMs = bookmark.createdAtEpochMs,
                updatedAtEpochMs = bookmark.updatedAtEpochMs
            )
        )
    }

    suspend fun getRouteBookmarks(): List<RouteBookmark> =
        dao
            .getBookmarkedRoutes()
            .mapNotNull { entity -> entity.toRouteBookmarkOrNull() }

    suspend fun getRouteBookmark(bookmarkId: String): RouteBookmark? =
        dao
            .getBookmarkedRoute(bookmarkId)
            ?.toRouteBookmarkOrNull()

    suspend fun deleteRouteBookmark(bookmarkId: String) {
        dao.deleteBookmarkedRoute(bookmarkId)
    }

    suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry) {
        dao.upsertRouteHistoryEntry(
            RouteHistoryEntryEntity(
                routeId = entry.routeId,
                historyJson = serializer.toVersionedJson(CacheEnvelopeType.ROUTE_HISTORY_ENTRY, entry.toCache()),
                updatedAtEpochMs = entry.updatedAtEpochMs
            )
        )
    }

    suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) {
        val rows = withContext(Dispatchers.Default) {
            entries.map { entry ->
                RouteHistoryEntryEntity(
                    routeId = entry.routeId,
                    historyJson = serializer.toVersionedJson(CacheEnvelopeType.ROUTE_HISTORY_ENTRY, entry.toCache()),
                    updatedAtEpochMs = entry.updatedAtEpochMs
                )
            }
        }
        dao.upsertRouteHistoryEntries(rows)
    }

    suspend fun getRouteHistoryEntries(): List<RouteHistoryEntry> {
        val entities = dao.getRouteHistoryEntries()
        return withContext(Dispatchers.Default) {
            entities.mapNotNull { entity ->
                routeHistoryEntryFromJsonOrNull(entity.historyJson)
                    ?.copy(updatedAtEpochMs = entity.updatedAtEpochMs)
            }
        }
    }

    suspend fun saveActiveRouteSession(session: ActiveRouteSession) {
        dao.saveActiveRouteSession(
            CachedRouteSessionEntity(
                routeId = session.route_id,
                sessionJson = serializer.toVersionedJson(CacheEnvelopeType.ACTIVE_ROUTE_SESSION, session.toCache()),
                isActive = true,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun loadActiveRouteSession(): ActiveRouteSession? =
        dao
            .getActiveRouteSession()
            ?.sessionJson
            ?.let { rawJson -> activeRouteSessionFromJsonOrNull(rawJson) }

    suspend fun clearActiveRouteSession() {
        dao.deleteActiveRouteSession()
    }

    private fun savedRouteSnapshotFromJsonOrNull(rawJson: String): SavedRouteSnapshot? =
        runCatching {
            serializer.fromVersionedJsonOrNull(
                rawJson = rawJson,
                expectedType = CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT,
                clazz = SavedRouteSnapshotCache::class.java
            )?.toDomain()
        }.getOrNull()

    private fun routeHistoryEntryFromJsonOrNull(rawJson: String): RouteHistoryEntry? =
        runCatching {
            serializer.fromVersionedJsonOrNull(
                rawJson = rawJson,
                expectedType = CacheEnvelopeType.ROUTE_HISTORY_ENTRY,
                clazz = RouteHistoryEntryCache::class.java
            )?.toDomain()
        }.getOrNull()

    private fun activeRouteSessionFromJsonOrNull(rawJson: String): ActiveRouteSession? =
        runCatching {
            serializer.fromVersionedJsonOrNull(
                rawJson = rawJson,
                expectedType = CacheEnvelopeType.ACTIVE_ROUTE_SESSION,
                clazz = ActiveRouteSessionCache::class.java
            )?.toDomain()
        }.getOrNull()

    private fun SavedRouteSnapshot.toCache(): SavedRouteSnapshotCache =
        SavedRouteSnapshotCache(
            request = request.toDto(),
            response = response.toDto()
        )

    private fun SavedRouteSnapshotCache.toDomain(): SavedRouteSnapshot =
        SavedRouteSnapshot(
            request = request.toDomain(),
            response = response.toDomain()
        )

    private fun RouteHistoryEntry.toCache(): RouteHistoryEntryCache =
        RouteHistoryEntryCache(
            routeId = routeId,
            cityName = cityName,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            availableMinutes = availableMinutes,
            usedMinutes = usedMinutes,
            totalWalkMinutes = totalWalkMinutes,
            totalVisitMinutes = totalVisitMinutes,
            snapshot = snapshot.toCache(),
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            feedback = feedback,
            updatedAtEpochMs = updatedAtEpochMs
        )

    private fun RouteHistoryEntryCache.toDomain(): RouteHistoryEntry =
        RouteHistoryEntry(
            routeId = routeId,
            cityName = cityName,
            status = status,
            startedAt = startedAt,
            finishedAt = finishedAt,
            availableMinutes = availableMinutes,
            usedMinutes = usedMinutes,
            totalWalkMinutes = totalWalkMinutes,
            totalVisitMinutes = totalVisitMinutes,
            snapshot = snapshot.toDomain(),
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            feedback = feedback,
            updatedAtEpochMs = updatedAtEpochMs
        )

    private fun ActiveRouteSession.toCache(): ActiveRouteSessionCache =
        ActiveRouteSessionCache(
            route_id = route_id,
            status = status,
            started_at = started_at,
            current_target_poi_id = current_target_poi_id,
            visited_poi_ids = visited_poi_ids,
            skipped_poi_ids = skipped_poi_ids,
            progress_visited_count = progress_visited_count,
            progress_total_count = progress_total_count,
            snapshot = snapshot.toCache(),
            feedback = feedback
        )

    private fun ActiveRouteSessionCache.toDomain(): ActiveRouteSession =
        ActiveRouteSession(
            route_id = route_id,
            status = status,
            started_at = started_at,
            current_target_poi_id = current_target_poi_id,
            visited_poi_ids = visited_poi_ids,
            skipped_poi_ids = skipped_poi_ids,
            progress_visited_count = progress_visited_count,
            progress_total_count = progress_total_count,
            snapshot = snapshot.toDomain(),
            feedback = feedback
        )

    private fun BookmarkedRouteEntity.toRouteBookmarkOrNull(): RouteBookmark? {
        val snapshot = savedRouteSnapshotFromJsonOrNull(snapshotJson) ?: return null
        return RouteBookmark(
            id = bookmarkId,
            title = title,
            citySlug = citySlug,
            snapshot = snapshot,
            createdAtEpochMs = createdAtEpochMs,
            updatedAtEpochMs = updatedAtEpochMs
        )
    }
}
