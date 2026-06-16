package com.example.smarttourism.data.repository

import com.example.smarttourism.data.local.BookmarkedRouteEntity
import com.example.smarttourism.data.local.CachedCityEntity
import com.example.smarttourism.data.local.CachedLastRouteEntity
import com.example.smarttourism.data.local.CachedPoiEntity
import com.example.smarttourism.data.local.CachedRouteSessionEntity
import com.example.smarttourism.data.local.LocalSyncStatus
import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.local.PendingFeedbackEntity
import com.example.smarttourism.data.local.PendingPoiVisitSyncEntity
import com.example.smarttourism.data.local.PendingRouteSessionSyncEntity
import com.example.smarttourism.data.local.RouteHistoryEntryEntity
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.ActiveRouteSessionCache
import com.example.smarttourism.data.model.CacheEnvelopeType
import com.example.smarttourism.data.model.CurrentCacheEnvelopeVersion
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.RouteHistoryEntryCache
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.data.model.SavedRouteSnapshotCache
import com.example.smarttourism.data.model.VersionedCacheEnvelope
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.remote.dto.CityBboxDto
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.remote.dto.RoutingLimitsDto
import com.example.smarttourism.data.remote.dto.TransportProfileDto
import com.example.smarttourism.features.planner.data.mapper.toDomain
import com.example.smarttourism.features.planner.data.mapper.toDto
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import javax.inject.Inject

data class OfflineSyncSummary(
    val syncedRouteSessions: Int,
    val failedRouteSessions: Int,
    val syncedPoiVisits: Int,
    val failedPoiVisits: Int,
    val syncedFeedback: Int,
    val failedFeedback: Int
) {
    val hasFailures: Boolean
        get() = failedRouteSessions > 0 || failedPoiVisits > 0 || failedFeedback > 0
}

private const val LastRouteCacheKey = "last_route"

class OfflineCacheStore @Inject constructor(
    private val dao: OfflineCacheDao,
    private val gson: Gson
) {
    suspend fun cacheCities(cities: List<CityDto>) {
        val updatedAt = System.currentTimeMillis()
        dao.replaceCachedCities(
            cities.map { city ->
                CachedCityEntity(
                    slug = city.slug,
                    cityId = city.id,
                    name = city.name,
                    country = city.country,
                    centerLat = city.center_lat,
                    centerLon = city.center_lon,
                    bboxSouth = city.bbox?.south,
                    bboxWest = city.bbox?.west,
                    bboxNorth = city.bbox?.north,
                    bboxEast = city.bbox?.east,
                    availableCategoriesJson = gson.toJson(city.available_categories.orEmpty()),
                    defaultZoom = city.default_zoom,
                    routingMaxAvailableMinutes = city.routing_limits?.max_available_minutes,
                    routingMaxPoiCandidates = city.routing_limits?.max_poi_candidates,
                    transportEnabled = city.transport?.mhd_enabled == true,
                    transportProvider = city.transport?.provider,
                    transportMode = city.transport?.mode,
                    updatedAtEpochMs = updatedAt
                )
            }
        )
    }

    suspend fun getCachedCities(): List<CityDto> =
        dao.getCachedCities().map { entity ->
            CityDto(
                id = entity.cityId,
                slug = entity.slug,
                name = entity.name,
                country = entity.country,
                center_lat = entity.centerLat,
                center_lon = entity.centerLon,
                bbox = if (
                    entity.bboxSouth != null &&
                    entity.bboxWest != null &&
                    entity.bboxNorth != null &&
                    entity.bboxEast != null
                ) {
                    CityBboxDto(
                        south = entity.bboxSouth,
                        west = entity.bboxWest,
                        north = entity.bboxNorth,
                        east = entity.bboxEast
                    )
                } else {
                    null
                },
                available_categories = decodeStringList(entity.availableCategoriesJson),
                default_zoom = entity.defaultZoom,
                routing_limits = RoutingLimitsDto(
                    max_available_minutes = entity.routingMaxAvailableMinutes,
                    max_poi_candidates = entity.routingMaxPoiCandidates
                ),
                transport = TransportProfileDto(
                    mhd_enabled = entity.transportEnabled,
                    provider = entity.transportProvider,
                    mode = entity.transportMode
                )
            )
        }

    suspend fun cachePois(citySlug: String, pois: List<PoiDto>) {
        val updatedAt = System.currentTimeMillis()
        dao.replaceCachedPois(
            citySlug = citySlug,
            pois = pois.map { poi ->
                CachedPoiEntity(
                    id = poi.id,
                    citySlug = citySlug,
                    name = poi.name,
                    category = poi.category,
                    lat = poi.lat,
                    lon = poi.lon,
                    openingHoursRaw = poi.opening_hours_raw,
                    visitDurationMin = poi.visit_duration_min,
                    baseScore = poi.base_score,
                    wikipediaUrl = poi.wikipedia_url,
                    updatedAtEpochMs = updatedAt
                )
            }
        )
    }

    suspend fun getCachedPois(citySlug: String): List<PoiDto> =
        dao.getCachedPois(citySlug).map { entity ->
            PoiDto(
                id = entity.id,
                name = entity.name,
                category = entity.category,
                lat = entity.lat,
                lon = entity.lon,
                opening_hours_raw = entity.openingHoursRaw,
                visit_duration_min = entity.visitDurationMin,
                base_score = entity.baseScore,
                wikipedia_url = entity.wikipediaUrl
            )
        }

    suspend fun saveLastRoute(snapshot: SavedRouteSnapshot) {
        dao.upsertLastRoute(
            CachedLastRouteEntity(
                cacheKey = LastRouteCacheKey,
                snapshotJson = toVersionedJson(CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT, snapshot.toCache()),
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
                snapshotJson = toVersionedJson(CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT, bookmark.snapshot.toCache()),
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
                historyJson = toVersionedJson(CacheEnvelopeType.ROUTE_HISTORY_ENTRY, entry.toCache()),
                updatedAtEpochMs = entry.updatedAtEpochMs
            )
        )
    }

    suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) {
        dao.upsertRouteHistoryEntries(
            entries.map { entry ->
                RouteHistoryEntryEntity(
                    routeId = entry.routeId,
                    historyJson = toVersionedJson(CacheEnvelopeType.ROUTE_HISTORY_ENTRY, entry.toCache()),
                    updatedAtEpochMs = entry.updatedAtEpochMs
                )
            }
        )
    }

    suspend fun getRouteHistoryEntries(): List<RouteHistoryEntry> =
        dao
            .getRouteHistoryEntries()
            .mapNotNull { entity ->
                routeHistoryEntryFromJsonOrNull(entity.historyJson)
                    ?.copy(updatedAtEpochMs = entity.updatedAtEpochMs)
            }

    suspend fun saveActiveRouteSession(session: ActiveRouteSession) {
        dao.saveActiveRouteSession(
            CachedRouteSessionEntity(
                routeId = session.route_id,
                sessionJson = toVersionedJson(CacheEnvelopeType.ACTIVE_ROUTE_SESSION, session.toCache()),
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

    suspend fun enqueuePendingRouteSession(request: RouteSessionCreateRequest) {
        val now = System.currentTimeMillis()
        dao.upsertPendingRouteSessionSync(
            PendingRouteSessionSyncEntity(
                sessionId = request.id,
                requestJson = toVersionedJson(CacheEnvelopeType.ROUTE_SESSION_CREATE_REQUEST, request),
                syncStatus = LocalSyncStatus.PENDING,
                lastSyncAttemptAtEpochMs = null,
                retryCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun enqueuePendingPoiVisit(
        sessionId: String,
        poiId: Int,
        request: RouteSessionPoiVisitRequest
    ) {
        val now = System.currentTimeMillis()
        dao.upsertPendingPoiVisitSync(
            PendingPoiVisitSyncEntity(
                requestKey = "$sessionId:$poiId",
                sessionId = sessionId,
                poiId = poiId,
                requestJson = toVersionedJson(CacheEnvelopeType.ROUTE_SESSION_POI_VISIT_REQUEST, request),
                syncStatus = LocalSyncStatus.PENDING,
                lastSyncAttemptAtEpochMs = null,
                retryCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun enqueuePendingFeedback(
        sessionId: String,
        request: RouteFeedbackRequest
    ) {
        val now = System.currentTimeMillis()
        dao.upsertPendingFeedback(
            PendingFeedbackEntity(
                sessionId = sessionId,
                feedbackJson = toVersionedJson(CacheEnvelopeType.ROUTE_FEEDBACK_REQUEST, request),
                syncStatus = LocalSyncStatus.PENDING,
                lastSyncAttemptAtEpochMs = null,
                retryCount = 0,
                createdAtEpochMs = now,
                updatedAtEpochMs = now
            )
        )
    }

    suspend fun deletePendingFeedback(sessionId: String) {
        dao.deletePendingFeedback(sessionId)
    }

    suspend fun getPendingFeedbackCount(): Int =
        dao.getPendingFeedbackCount()

    suspend fun getPendingSyncOperationCount(): Int =
        dao.getPendingRouteSessionSyncCount() +
            dao.getPendingPoiVisitSyncCount() +
            dao.getPendingFeedbackCount()

    suspend fun syncPendingOperations(api: PoiApi): OfflineSyncSummary {
        val routeSessionResult = syncPendingRouteSessions(api)
        val poiVisitResult = syncPendingPoiVisits(api)
        val feedbackResult = syncPendingFeedback(api)
        return OfflineSyncSummary(
            syncedRouteSessions = routeSessionResult.syncedCount,
            failedRouteSessions = routeSessionResult.failedCount,
            syncedPoiVisits = poiVisitResult.syncedCount,
            failedPoiVisits = poiVisitResult.failedCount,
            syncedFeedback = feedbackResult.syncedCount,
            failedFeedback = feedbackResult.failedCount
        )
    }

    private suspend fun syncPendingRouteSessions(api: PoiApi): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao.getPendingRouteSessionSyncs().forEach { pendingRouteSession ->
            val request = fromVersionedJsonOrNull(
                rawJson = pendingRouteSession.requestJson,
                expectedType = CacheEnvelopeType.ROUTE_SESSION_CREATE_REQUEST,
                clazz = RouteSessionCreateRequest::class.java
            )
            if (request == null) {
                dao.deletePendingRouteSessionSync(pendingRouteSession.sessionId)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.createRouteSession(request)
            }.onSuccess {
                dao.deletePendingRouteSessionSync(pendingRouteSession.sessionId)
                syncedCount += 1
            }.onFailure {
                dao.upsertPendingRouteSessionSync(
                    pendingRouteSession.copy(
                        syncStatus = LocalSyncStatus.FAILED,
                        lastSyncAttemptAtEpochMs = attemptTime,
                        retryCount = pendingRouteSession.retryCount + 1,
                        updatedAtEpochMs = attemptTime
                    )
                )
                failedCount += 1
            }
        }

        return SyncBatchResult(syncedCount = syncedCount, failedCount = failedCount)
    }

    private suspend fun syncPendingPoiVisits(api: PoiApi): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao.getPendingPoiVisitSyncs().forEach { pendingPoiVisit ->
            val request = fromVersionedJsonOrNull(
                rawJson = pendingPoiVisit.requestJson,
                expectedType = CacheEnvelopeType.ROUTE_SESSION_POI_VISIT_REQUEST,
                clazz = RouteSessionPoiVisitRequest::class.java
            )
            if (request == null) {
                dao.deletePendingPoiVisitSync(pendingPoiVisit.requestKey)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.markRouteSessionPoiVisited(
                    sessionId = pendingPoiVisit.sessionId,
                    poiId = pendingPoiVisit.poiId,
                    request = request
                )
            }.onSuccess {
                dao.deletePendingPoiVisitSync(pendingPoiVisit.requestKey)
                syncedCount += 1
            }.onFailure {
                dao.upsertPendingPoiVisitSync(
                    pendingPoiVisit.copy(
                        syncStatus = LocalSyncStatus.FAILED,
                        lastSyncAttemptAtEpochMs = attemptTime,
                        retryCount = pendingPoiVisit.retryCount + 1,
                        updatedAtEpochMs = attemptTime
                    )
                )
                failedCount += 1
            }
        }

        return SyncBatchResult(syncedCount = syncedCount, failedCount = failedCount)
    }

    private suspend fun syncPendingFeedback(api: PoiApi): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao.getPendingFeedback().forEach { pendingFeedback ->
            val request = fromVersionedJsonOrNull(
                rawJson = pendingFeedback.feedbackJson,
                expectedType = CacheEnvelopeType.ROUTE_FEEDBACK_REQUEST,
                clazz = RouteFeedbackRequest::class.java
            )
            if (request == null) {
                dao.deletePendingFeedback(pendingFeedback.sessionId)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.saveRouteFeedback(
                    sessionId = pendingFeedback.sessionId,
                    request = request
                )
            }.onSuccess {
                dao.deletePendingFeedback(pendingFeedback.sessionId)
                syncedCount += 1
            }.onFailure {
                dao.upsertPendingFeedback(
                    pendingFeedback.copy(
                        syncStatus = LocalSyncStatus.FAILED,
                        lastSyncAttemptAtEpochMs = attemptTime,
                        retryCount = pendingFeedback.retryCount + 1,
                        updatedAtEpochMs = attemptTime
                    )
                )
                failedCount += 1
            }
        }

        return SyncBatchResult(syncedCount = syncedCount, failedCount = failedCount)
    }

    private fun decodeStringList(rawJson: String?): List<String> {
        if (rawJson.isNullOrBlank()) {
            return emptyList()
        }
        return runCatching {
            gson.fromJson<List<String>>(
                rawJson,
                object : TypeToken<List<String>>() {}.type
            )
        }.getOrDefault(emptyList())
    }

    private fun <T> fromJsonOrNull(rawJson: String, clazz: Class<T>): T? =
        runCatching { gson.fromJson(rawJson, clazz) }.getOrNull()

    private fun <T> toVersionedJson(type: String, value: T): String =
        gson.toJson(
            VersionedCacheEnvelope(
                type = type,
                payload = gson.toJsonTree(value)
            )
        )

    private fun <T> fromVersionedJsonOrNull(
        rawJson: String,
        expectedType: String,
        clazz: Class<T>
    ): T? {
        val root = runCatching { JsonParser.parseString(rawJson) }.getOrNull()
        val envelope = root?.asJsonObjectOrNull()
        if (envelope?.has("payload") == true && envelope.hasSchemaVersion()) {
            val schemaVersion = envelope.schemaVersionOrNull() ?: return null
            val type = envelope.get("type").asStringOrNull() ?: return null
            val payload = envelope.get("payload") ?: return null
            if (schemaVersion != CurrentCacheEnvelopeVersion || type != expectedType) {
                return null
            }
            return runCatching { gson.fromJson(payload, clazz) }.getOrNull()
        }

        return fromJsonOrNull(rawJson, clazz)
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

    private fun JsonObject.hasSchemaVersion(): Boolean =
        has("schema_version") || has("schemaVersion")

    private fun JsonObject.schemaVersionOrNull(): Int? =
        (get("schema_version") ?: get("schemaVersion")).asIntOrNull()

    private fun JsonElement?.asIntOrNull(): Int? =
        runCatching { this?.asInt }.getOrNull()

    private fun JsonElement?.asStringOrNull(): String? =
        runCatching { this?.asString }.getOrNull()

    private fun savedRouteSnapshotFromJsonOrNull(rawJson: String): SavedRouteSnapshot? =
        runCatching {
            fromVersionedJsonOrNull(
                rawJson = rawJson,
                expectedType = CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT,
                clazz = SavedRouteSnapshotCache::class.java
            )?.toDomain()
        }.getOrNull()

    private fun routeHistoryEntryFromJsonOrNull(rawJson: String): RouteHistoryEntry? =
        runCatching {
            fromVersionedJsonOrNull(
                rawJson = rawJson,
                expectedType = CacheEnvelopeType.ROUTE_HISTORY_ENTRY,
                clazz = RouteHistoryEntryCache::class.java
            )?.toDomain()
        }.getOrNull()

    private fun activeRouteSessionFromJsonOrNull(rawJson: String): ActiveRouteSession? =
        runCatching {
            fromVersionedJsonOrNull(
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

    private data class SyncBatchResult(
        val syncedCount: Int,
        val failedCount: Int
    )
}
