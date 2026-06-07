package com.example.smarttourism.data.repository

import android.content.Context
import com.example.smarttourism.data.local.BookmarkedRouteEntity
import com.example.smarttourism.data.local.CachedCityEntity
import com.example.smarttourism.data.local.CachedLastRouteEntity
import com.example.smarttourism.data.local.CachedPoiEntity
import com.example.smarttourism.data.local.CachedRouteSessionEntity
import com.example.smarttourism.data.local.LocalSyncStatus
import com.example.smarttourism.data.local.OfflineCacheDatabase
import com.example.smarttourism.data.local.PendingFeedbackEntity
import com.example.smarttourism.data.local.PendingPoiVisitSyncEntity
import com.example.smarttourism.data.local.PendingRouteSessionSyncEntity
import com.example.smarttourism.data.local.RouteHistoryEntryEntity
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.CacheEnvelopeType
import com.example.smarttourism.data.model.CurrentCacheEnvelopeVersion
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
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
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken

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

object OfflineCacheRepository {
    private val gson = Gson()
    private const val LastRouteCacheKey = "last_route"

    suspend fun cacheCities(context: Context, cities: List<CityDto>) {
        val updatedAt = System.currentTimeMillis()
        dao(context).replaceCachedCities(
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

    suspend fun getCachedCities(context: Context): List<CityDto> =
        dao(context).getCachedCities().map { entity ->
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

    suspend fun cachePois(context: Context, citySlug: String, pois: List<PoiDto>) {
        val updatedAt = System.currentTimeMillis()
        dao(context).replaceCachedPois(
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

    suspend fun getCachedPois(context: Context, citySlug: String): List<PoiDto> =
        dao(context).getCachedPois(citySlug).map { entity ->
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

    suspend fun saveLastRoute(context: Context, snapshot: SavedRouteSnapshot) {
        dao(context).upsertLastRoute(
            CachedLastRouteEntity(
                cacheKey = LastRouteCacheKey,
                snapshotJson = toVersionedJson(CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT, snapshot),
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun loadLastRoute(context: Context): SavedRouteSnapshot? =
        dao(context)
            .getLastRoute()
            ?.snapshotJson
            ?.let { rawJson ->
                fromVersionedJsonOrNull(
                    rawJson = rawJson,
                    expectedType = CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT,
                    clazz = SavedRouteSnapshot::class.java
                )
            }

    suspend fun saveRouteBookmark(
        context: Context,
        bookmark: RouteBookmark
    ) {
        dao(context).upsertBookmarkedRoute(
            BookmarkedRouteEntity(
                bookmarkId = bookmark.id,
                title = bookmark.title,
                citySlug = bookmark.citySlug,
                snapshotJson = toVersionedJson(CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT, bookmark.snapshot),
                createdAtEpochMs = bookmark.createdAtEpochMs,
                updatedAtEpochMs = bookmark.updatedAtEpochMs
            )
        )
    }

    suspend fun getRouteBookmarks(context: Context): List<RouteBookmark> =
        dao(context)
            .getBookmarkedRoutes()
            .mapNotNull { entity -> entity.toRouteBookmarkOrNull() }

    suspend fun getRouteBookmark(context: Context, bookmarkId: String): RouteBookmark? =
        dao(context)
            .getBookmarkedRoute(bookmarkId)
            ?.toRouteBookmarkOrNull()

    suspend fun deleteRouteBookmark(context: Context, bookmarkId: String) {
        dao(context).deleteBookmarkedRoute(bookmarkId)
    }

    suspend fun saveRouteHistoryEntry(context: Context, entry: RouteHistoryEntry) {
        dao(context).upsertRouteHistoryEntry(
            RouteHistoryEntryEntity(
                routeId = entry.routeId,
                historyJson = toVersionedJson(CacheEnvelopeType.ROUTE_HISTORY_ENTRY, entry),
                updatedAtEpochMs = entry.updatedAtEpochMs
            )
        )
    }

    suspend fun saveRouteHistoryEntries(context: Context, entries: List<RouteHistoryEntry>) {
        dao(context).upsertRouteHistoryEntries(
            entries.map { entry ->
                RouteHistoryEntryEntity(
                    routeId = entry.routeId,
                    historyJson = toVersionedJson(CacheEnvelopeType.ROUTE_HISTORY_ENTRY, entry),
                    updatedAtEpochMs = entry.updatedAtEpochMs
                )
            }
        )
    }

    suspend fun getRouteHistoryEntries(context: Context): List<RouteHistoryEntry> =
        dao(context)
            .getRouteHistoryEntries()
            .mapNotNull { entity ->
                fromVersionedJsonOrNull(
                    rawJson = entity.historyJson,
                    expectedType = CacheEnvelopeType.ROUTE_HISTORY_ENTRY,
                    clazz = RouteHistoryEntry::class.java
                )
                    ?.copy(updatedAtEpochMs = entity.updatedAtEpochMs)
            }

    suspend fun saveActiveRouteSession(context: Context, session: ActiveRouteSession) {
        dao(context).saveActiveRouteSession(
            CachedRouteSessionEntity(
                routeId = session.route_id,
                sessionJson = toVersionedJson(CacheEnvelopeType.ACTIVE_ROUTE_SESSION, session),
                isActive = true,
                updatedAtEpochMs = System.currentTimeMillis()
            )
        )
    }

    suspend fun loadActiveRouteSession(context: Context): ActiveRouteSession? =
        dao(context)
            .getActiveRouteSession()
            ?.sessionJson
            ?.let { rawJson ->
                fromVersionedJsonOrNull(
                    rawJson = rawJson,
                    expectedType = CacheEnvelopeType.ACTIVE_ROUTE_SESSION,
                    clazz = ActiveRouteSession::class.java
                )
            }

    suspend fun clearActiveRouteSession(context: Context) {
        dao(context).deleteActiveRouteSession()
    }

    suspend fun enqueuePendingRouteSession(
        context: Context,
        request: RouteSessionCreateRequest
    ) {
        val now = System.currentTimeMillis()
        dao(context).upsertPendingRouteSessionSync(
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
        context: Context,
        sessionId: String,
        poiId: Int,
        request: RouteSessionPoiVisitRequest
    ) {
        val now = System.currentTimeMillis()
        dao(context).upsertPendingPoiVisitSync(
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
        context: Context,
        sessionId: String,
        request: RouteFeedbackRequest
    ) {
        val now = System.currentTimeMillis()
        dao(context).upsertPendingFeedback(
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

    suspend fun deletePendingFeedback(context: Context, sessionId: String) {
        dao(context).deletePendingFeedback(sessionId)
    }

    suspend fun getPendingFeedbackCount(context: Context): Int =
        dao(context).getPendingFeedbackCount()

    suspend fun getPendingSyncOperationCount(context: Context): Int =
        dao(context).getPendingRouteSessionSyncCount() +
            dao(context).getPendingPoiVisitSyncCount() +
            dao(context).getPendingFeedbackCount()

    suspend fun syncPendingOperations(context: Context, api: PoiApi): OfflineSyncSummary {
        val routeSessionResult = syncPendingRouteSessions(context, api)
        val poiVisitResult = syncPendingPoiVisits(context, api)
        val feedbackResult = syncPendingFeedback(context, api)
        return OfflineSyncSummary(
            syncedRouteSessions = routeSessionResult.syncedCount,
            failedRouteSessions = routeSessionResult.failedCount,
            syncedPoiVisits = poiVisitResult.syncedCount,
            failedPoiVisits = poiVisitResult.failedCount,
            syncedFeedback = feedbackResult.syncedCount,
            failedFeedback = feedbackResult.failedCount
        )
    }

    private suspend fun syncPendingRouteSessions(
        context: Context,
        api: PoiApi
    ): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao(context).getPendingRouteSessionSyncs().forEach { pendingRouteSession ->
            val request = fromVersionedJsonOrNull(
                rawJson = pendingRouteSession.requestJson,
                expectedType = CacheEnvelopeType.ROUTE_SESSION_CREATE_REQUEST,
                clazz = RouteSessionCreateRequest::class.java
            )
            if (request == null) {
                dao(context).deletePendingRouteSessionSync(pendingRouteSession.sessionId)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.createRouteSession(request)
            }.onSuccess {
                dao(context).deletePendingRouteSessionSync(pendingRouteSession.sessionId)
                syncedCount += 1
            }.onFailure {
                dao(context).upsertPendingRouteSessionSync(
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

    private suspend fun syncPendingPoiVisits(
        context: Context,
        api: PoiApi
    ): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao(context).getPendingPoiVisitSyncs().forEach { pendingPoiVisit ->
            val request = fromVersionedJsonOrNull(
                rawJson = pendingPoiVisit.requestJson,
                expectedType = CacheEnvelopeType.ROUTE_SESSION_POI_VISIT_REQUEST,
                clazz = RouteSessionPoiVisitRequest::class.java
            )
            if (request == null) {
                dao(context).deletePendingPoiVisitSync(pendingPoiVisit.requestKey)
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
                dao(context).deletePendingPoiVisitSync(pendingPoiVisit.requestKey)
                syncedCount += 1
            }.onFailure {
                dao(context).upsertPendingPoiVisitSync(
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

    private suspend fun syncPendingFeedback(
        context: Context,
        api: PoiApi
    ): SyncBatchResult {
        var syncedCount = 0
        var failedCount = 0

        dao(context).getPendingFeedback().forEach { pendingFeedback ->
            val request = fromVersionedJsonOrNull(
                rawJson = pendingFeedback.feedbackJson,
                expectedType = CacheEnvelopeType.ROUTE_FEEDBACK_REQUEST,
                clazz = RouteFeedbackRequest::class.java
            )
            if (request == null) {
                dao(context).deletePendingFeedback(pendingFeedback.sessionId)
                return@forEach
            }

            val attemptTime = System.currentTimeMillis()
            runCatching {
                api.saveRouteFeedback(
                    sessionId = pendingFeedback.sessionId,
                    request = request
                )
            }.onSuccess {
                dao(context).deletePendingFeedback(pendingFeedback.sessionId)
                syncedCount += 1
            }.onFailure {
                dao(context).upsertPendingFeedback(
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

    private fun dao(context: Context) =
        OfflineCacheDatabase.getInstance(context).offlineCacheDao()

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

    private fun BookmarkedRouteEntity.toRouteBookmarkOrNull(): RouteBookmark? {
        val snapshot = fromVersionedJsonOrNull(
            rawJson = snapshotJson,
            expectedType = CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT,
            clazz = SavedRouteSnapshot::class.java
        ) ?: return null
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
