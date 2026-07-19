package com.example.smarttourism.data.repository

import com.example.smarttourism.data.local.BookmarkedRouteEntity
import com.example.smarttourism.data.local.CachedCityEntity
import com.example.smarttourism.data.local.CachedLastRouteEntity
import com.example.smarttourism.data.local.CachedPoiEntity
import com.example.smarttourism.data.local.CachedRouteSessionEntity
import com.example.smarttourism.data.local.OfflineCacheDao
import com.example.smarttourism.data.local.PendingFeedbackEntity
import com.example.smarttourism.data.local.PendingPoiVisitSyncEntity
import com.example.smarttourism.data.local.PendingRouteSessionSyncEntity
import com.example.smarttourism.data.local.RouteHistoryEntryEntity
import com.example.smarttourism.data.model.CacheEnvelopeType
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.CuratedRouteDetailDto
import com.example.smarttourism.data.remote.dto.CuratedRouteSummaryDto
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteLegDto
import com.example.smarttourism.data.remote.dto.RouteLegRequest
import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionDto
import com.example.smarttourism.data.remote.dto.RouteSessionPoiDto
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.remote.dto.RouteSessionUpdateRequest
import com.example.smarttourism.features.planner.data.mapper.toDto
import com.example.smarttourism.features.planner.sampleSnapshot
import com.google.gson.Gson
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCacheRepositoryEnvelopeTest {
    @Test
    fun saveLastRoutePersistsVersionedDtoEnvelopeAndRoundTripsToDomain() = runBlocking {
        val dao = FakeOfflineCacheDao()
        val store = OfflineCacheStore(dao = dao, gson = Gson())
        val snapshot = sampleSnapshot()

        store.saveLastRoute(snapshot)

        val rawJson = dao.lastRoute?.snapshotJson
        assertNotNull(rawJson)
        val envelope = JsonParser.parseString(rawJson).asJsonObject
        assertEquals(1, envelope.get("schema_version").asInt)
        assertEquals(CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT, envelope.get("type").asString)

        val requestJson = envelope.getAsJsonObject("payload").getAsJsonObject("request")
        assertTrue(requestJson.has("start_lat"))
        assertTrue(requestJson.has("available_minutes"))
        assertFalse(requestJson.has("startLat"))
        assertFalse(requestJson.has("availableMinutes"))

        val restored = store.loadLastRoute()
        assertEquals(snapshot.request.startLat, restored?.request?.startLat)
        assertEquals(snapshot.request.availableMinutes, restored?.request?.availableMinutes)
        assertEquals(snapshot.response.usedMinutes, restored?.response?.usedMinutes)
        assertEquals(snapshot.response.route.map { stop -> stop.poiId }, restored?.response?.route?.map { stop -> stop.poiId })
    }

    @Test
    fun loadLastRouteRejectsWrongEnvelopeType() = runBlocking {
        val dao = FakeOfflineCacheDao().apply {
            lastRoute = CachedLastRouteEntity(
                cacheKey = "last_route",
                snapshotJson = """
                    {
                      "schema_version": 1,
                      "type": "${CacheEnvelopeType.ROUTE_HISTORY_ENTRY}",
                      "payload": {}
                    }
                """.trimIndent(),
                updatedAtEpochMs = 1
            )
        }
        val store = OfflineCacheStore(dao = dao, gson = Gson())

        assertNull(store.loadLastRoute())
    }

    @Test
    fun pendingSyncCountSumsRouteSessionPoiVisitAndFeedbackQueues() = runBlocking {
        val dao = FakeOfflineCacheDao()
        val store = OfflineCacheStore(dao = dao, gson = Gson())
        val snapshot = sampleSnapshot()

        store.enqueuePendingRouteSession(
            RouteSessionCreateRequest(
                id = "session-1",
                device_id = "device-1",
                city = "nitra",
                status = "in_progress",
                start_lat = snapshot.response.start.lat,
                start_lon = snapshot.response.start.lon,
                available_minutes = snapshot.response.availableMinutes,
                pace = snapshot.response.pace,
                return_to_start = snapshot.response.returnToStart,
                opening_hours_enabled = snapshot.response.respectOpeningHours,
                started_at = "2026-05-19T10:00:00",
                finished_at = null,
                used_minutes = snapshot.response.usedMinutes,
                total_walk_minutes = snapshot.response.totalWalkMinutes,
                total_visit_minutes = snapshot.response.totalVisitMinutes,
                route_snapshot_json = snapshot.response.toDto()
            )
        )
        store.enqueuePendingPoiVisit(
            sessionId = "session-1",
            poiId = 1,
            request = RouteSessionPoiVisitRequest(
                visited_at = "2026-05-19T10:15:00",
                skipped = false
            )
        )
        store.enqueuePendingFeedback(
            sessionId = "session-1",
            request = RouteFeedbackRequest(
                rating = 5,
                was_convenient = true,
                too_much_walking = false,
                pois_were_interesting = true
            )
        )

        assertEquals(3, store.getPendingSyncOperationCount())
    }

    @Test
    fun syncPendingOperationsDeletesSuccessfullySyncedOfflineQueueItems() = runBlocking {
        val dao = FakeOfflineCacheDao()
        val store = OfflineCacheStore(dao = dao, gson = Gson())
        val snapshot = sampleSnapshot()

        store.enqueuePendingRouteSession(
            RouteSessionCreateRequest(
                id = "session-1",
                device_id = "device-1",
                city = "nitra",
                status = "in_progress",
                start_lat = snapshot.response.start.lat,
                start_lon = snapshot.response.start.lon,
                available_minutes = snapshot.response.availableMinutes,
                pace = snapshot.response.pace,
                return_to_start = snapshot.response.returnToStart,
                opening_hours_enabled = snapshot.response.respectOpeningHours,
                started_at = "2026-05-19T10:00:00",
                finished_at = null,
                used_minutes = snapshot.response.usedMinutes,
                total_walk_minutes = snapshot.response.totalWalkMinutes,
                total_visit_minutes = snapshot.response.totalVisitMinutes,
                route_snapshot_json = snapshot.response.toDto()
            )
        )
        store.enqueuePendingPoiVisit(
            sessionId = "session-1",
            poiId = 1,
            request = RouteSessionPoiVisitRequest(
                visited_at = "2026-05-19T10:15:00",
                skipped = false
            )
        )
        store.enqueuePendingFeedback(
            sessionId = "session-1",
            request = RouteFeedbackRequest(
                rating = 5,
                was_convenient = true,
                too_much_walking = false,
                pois_were_interesting = true
            )
        )

        val summary = store.syncPendingOperations(SuccessfulOfflineSyncApi())

        assertFalse(summary.hasFailures)
        assertEquals(1, summary.syncedRouteSessions)
        assertEquals(1, summary.syncedPoiVisits)
        assertEquals(1, summary.syncedFeedback)
        assertEquals(0, store.getPendingSyncOperationCount())
    }

    private class FakeOfflineCacheDao : OfflineCacheDao {
        var lastRoute: CachedLastRouteEntity? = null
        private val routeSessions = linkedMapOf<String, PendingRouteSessionSyncEntity>()
        private val poiVisits = linkedMapOf<String, PendingPoiVisitSyncEntity>()
        private val feedback = linkedMapOf<String, PendingFeedbackEntity>()

        override suspend fun getCachedCities(): List<CachedCityEntity> = emptyList()

        override suspend fun insertCachedCities(cities: List<CachedCityEntity>) = Unit

        override suspend fun clearCachedCities() = Unit

        override suspend fun getCachedPois(citySlug: String): List<CachedPoiEntity> = emptyList()

        override suspend fun insertCachedPois(pois: List<CachedPoiEntity>) = Unit

        override suspend fun clearCachedPois(citySlug: String) = Unit

        override suspend fun getLastRoute(cacheKey: String): CachedLastRouteEntity? = lastRoute

        override suspend fun upsertLastRoute(route: CachedLastRouteEntity) {
            lastRoute = route
        }

        override suspend fun clearLastRoute(cacheKey: String) {
            lastRoute = null
        }

        override suspend fun getBookmarkedRoutes(): List<BookmarkedRouteEntity> = emptyList()

        override suspend fun getBookmarkedRoute(bookmarkId: String): BookmarkedRouteEntity? = null

        override suspend fun upsertBookmarkedRoute(route: BookmarkedRouteEntity) = Unit

        override suspend fun deleteBookmarkedRoute(bookmarkId: String) = Unit

        override suspend fun getRouteHistoryEntries(): List<RouteHistoryEntryEntity> = emptyList()

        override suspend fun upsertRouteHistoryEntry(entry: RouteHistoryEntryEntity) = Unit

        override suspend fun upsertRouteHistoryEntries(entries: List<RouteHistoryEntryEntity>) = Unit

        override suspend fun getActiveRouteSession(): CachedRouteSessionEntity? = null

        override suspend fun upsertRouteSession(routeSession: CachedRouteSessionEntity) = Unit

        override suspend fun clearActiveRouteSessionFlags() = Unit

        override suspend fun deleteActiveRouteSession() = Unit

        override suspend fun upsertPendingFeedback(feedback: PendingFeedbackEntity) {
            this.feedback[feedback.sessionId] = feedback
        }

        override suspend fun getPendingFeedback(): List<PendingFeedbackEntity> =
            feedback.values.toList()

        override suspend fun deletePendingFeedback(sessionId: String) {
            feedback.remove(sessionId)
        }

        override suspend fun getPendingFeedbackCount(): Int =
            feedback.size

        override suspend fun upsertPendingRouteSessionSync(routeSessionSync: PendingRouteSessionSyncEntity) {
            routeSessions[routeSessionSync.sessionId] = routeSessionSync
        }

        override suspend fun getPendingRouteSessionSyncs(): List<PendingRouteSessionSyncEntity> =
            routeSessions.values.toList()

        override suspend fun deletePendingRouteSessionSync(sessionId: String) {
            routeSessions.remove(sessionId)
        }

        override suspend fun getPendingRouteSessionSyncCount(): Int =
            routeSessions.size

        override suspend fun upsertPendingPoiVisitSync(poiVisitSync: PendingPoiVisitSyncEntity) {
            poiVisits[poiVisitSync.requestKey] = poiVisitSync
        }

        override suspend fun getPendingPoiVisitSyncs(): List<PendingPoiVisitSyncEntity> =
            poiVisits.values.toList()

        override suspend fun deletePendingPoiVisitSync(requestKey: String) {
            poiVisits.remove(requestKey)
        }

        override suspend fun getPendingPoiVisitSyncCount(): Int =
            poiVisits.size
    }

    private class SuccessfulOfflineSyncApi : PoiApi {
        override suspend fun getCities(): List<CityDto> =
            error("Unused in offline sync tests")

        override suspend fun getPois(city: String): List<PoiDto> =
            error("Unused in offline sync tests")

        override suspend fun getCuratedRoutes(slug: String): List<CuratedRouteSummaryDto> =
            error("Unused in offline sync tests")

        override suspend fun getCuratedRoute(routeId: Int, startDatetime: String?): CuratedRouteDetailDto =
            error("Unused in offline sync tests")

        override suspend fun generateRoute(request: RouteRequest): RouteResponse =
            error("Unused in offline sync tests")

        override suspend fun generateRouteLeg(request: RouteLegRequest): RouteLegDto =
            error("Unused in offline sync tests")

        override suspend fun createRouteSession(request: RouteSessionCreateRequest): RouteSessionDto =
            RouteSessionDto(
                id = request.id,
                device_id = request.device_id,
                city_id = 1,
                city_name = request.city,
                status = request.status,
                start_lat = request.start_lat,
                start_lon = request.start_lon,
                available_minutes = request.available_minutes,
                pace = request.pace,
                return_to_start = request.return_to_start,
                opening_hours_enabled = request.opening_hours_enabled,
                started_at = request.started_at ?: "2026-05-19T10:00:00",
                finished_at = request.finished_at,
                used_minutes = request.used_minutes,
                total_walk_minutes = request.total_walk_minutes,
                total_visit_minutes = request.total_visit_minutes,
                route_snapshot_json = null,
                pois = emptyList(),
                feedback = emptyList()
            )

        override suspend fun updateRouteSession(
            sessionId: String,
            request: RouteSessionUpdateRequest
        ): RouteSessionDto =
            error("Unused in offline sync tests")

        override suspend fun markRouteSessionPoiVisited(
            sessionId: String,
            poiId: Int,
            request: RouteSessionPoiVisitRequest
        ): RouteSessionPoiDto =
            RouteSessionPoiDto(
                id = 1,
                session_id = sessionId,
                poi_id = poiId,
                visit_order = 1,
                planned_arrival_min = null,
                planned_departure_min = null,
                visited = true,
                visited_at = request.visited_at,
                skipped = request.skipped
            )

        override suspend fun saveRouteFeedback(
            sessionId: String,
            request: RouteFeedbackRequest
        ): RouteFeedbackDto =
            RouteFeedbackDto(
                id = 1,
                session_id = sessionId,
                rating = request.rating,
                was_convenient = request.was_convenient,
                too_much_walking = request.too_much_walking,
                pois_were_interesting = request.pois_were_interesting,
                comment = request.comment,
                created_at = "2026-05-19T10:20:00"
            )

        override suspend fun getRouteSession(sessionId: String): RouteSessionDto =
            error("Unused in offline sync tests")

        override suspend fun getRouteSessions(deviceId: String): List<RouteSessionDto> =
            error("Unused in offline sync tests")
    }
}
