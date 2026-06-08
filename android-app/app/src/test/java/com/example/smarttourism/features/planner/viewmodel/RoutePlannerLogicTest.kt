package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.CacheEnvelopeType
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.PlannerRepository
import com.example.smarttourism.features.planner.data.bookmark.RouteBookmarkRepository
import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteCoordinate
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegEndpoint
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutePlannerLogicTest {
    @Test
    fun mergeRouteHistoryEntriesPreservesLocalProgressWhenRemoteHasNoProgress() {
        val localFeedback = RouteFeedback(
            rating = 4,
            route_was_comfortable = true,
            too_much_walking = false,
            pois_were_interesting = true
        )
        val local = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.COMPLETED,
            visitedPoiIds = listOf(1, 2),
            skippedPoiIds = listOf(3),
            feedback = localFeedback,
            updatedAtEpochMs = 100
        )
        val remote = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.COMPLETED,
            visitedPoiIds = emptyList(),
            skippedPoiIds = emptyList(),
            feedback = null,
            updatedAtEpochMs = 200
        )

        val merged = mergeRouteHistoryEntries(
            cachedEntries = listOf(local),
            remoteEntries = listOf(remote),
            currentEntry = null
        )

        assertEquals(1, merged.size)
        assertEquals(remote.updatedAtEpochMs, merged.first().updatedAtEpochMs)
        assertEquals(listOf(1, 2), merged.first().visitedPoiIds)
        assertEquals(listOf(3), merged.first().skippedPoiIds)
        assertEquals(localFeedback, merged.first().feedback)
    }

    @Test
    fun upsertRouteHistoryEntryKeepsTerminalEntryOverNewerActiveEntry() {
        val completed = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.COMPLETED,
            updatedAtEpochMs = 100
        )
        val newerActive = historyEntry(
            routeId = "route-1",
            status = RouteSessionStatus.IN_PROGRESS,
            updatedAtEpochMs = 500
        )

        val updated = upsertRouteHistoryEntry(listOf(completed), newerActive)

        assertEquals(1, updated.size)
        assertSame(completed, updated.first())
    }

    @Test
    fun rebuildPreviewRouteAfterRemovingPoiRecalculatesLegsAndTotals() {
        val plan = sampleRoutePlan()
        val firstStop = plan.route[0]
        val thirdStop = plan.route[2]
        val replacementLegToNext = routeLeg(
            order = 0,
            from = firstStop.toEndpoint(),
            to = thirdStop.toEndpoint(),
            minutes = 12
        )

        val updated = rebuildPreviewRouteAfterRemovingPoi(
            previousResponse = plan,
            poiId = 2,
            replacementLegToNext = replacementLegToNext,
            replacementReturnLeg = null
        )

        assertEquals(listOf(1, 3), updated.route.map { item -> item.poiId })
        assertEquals(listOf(1, 2), updated.route.map { item -> item.order })
        assertEquals(12, updated.route[1].travelMinutesFromPrevious)
        assertEquals(27, updated.route[1].arrivalAfterMin)
        assertEquals(37, updated.route[1].departureAfterMin)
        assertEquals(43, updated.usedMinutes)
        assertEquals(20, updated.totalVisitMinutes)
        assertEquals(23, updated.totalWalkMinutes)
        assertEquals(listOf(1, 3, null), updated.legs.orEmpty().map { leg -> leg.to.poiId })
        assertEquals(listOf(1, 2, 3), updated.legs.orEmpty().map { leg -> leg.order })
    }

    @Test
    fun rebuildPreviewRouteAfterReplacingPoiDoesNotAppendExtraStops() {
        val plan = sampleRoutePlan()
        val firstStop = plan.route[0]
        val thirdStop = plan.route[2]
        val replacementPoi = Poi(
            id = 20,
            name = "Replacement",
            category = "museum",
            lat = 20.0,
            lon = 20.0,
            visitDurationMin = 15,
            baseScore = 9.0
        )

        val updated = rebuildPreviewRouteAfterReplacingPoi(
            previousResponse = plan,
            targetPoiId = 2,
            replacementPoi = replacementPoi,
            replacementLegToReplacement = routeLeg(
                order = 0,
                from = firstStop.toEndpoint(),
                to = replacementPoi.toEndpoint(),
                minutes = 8
            ),
            replacementLegToNext = routeLeg(
                order = 0,
                from = replacementPoi.toEndpoint(),
                to = thirdStop.toEndpoint(),
                minutes = 11
            ),
            replacementReturnLeg = null
        )

        assertEquals(listOf(1, 20, 3), updated.route.map { item -> item.poiId })
        assertEquals(3, updated.poiCount)
        assertFalse(updated.route.any { item -> item.poiId == 2 })
        assertEquals("Replacement", updated.route[1].name)
        assertEquals(15, updated.route[1].visitDurationMin)
        assertEquals(23, updated.route[1].arrivalAfterMin)
        assertEquals(38, updated.route[1].departureAfterMin)
        assertEquals(65, updated.usedMinutes)
        assertEquals(35, updated.totalVisitMinutes)
        assertEquals(listOf(1, 20, 3, null), updated.legs.orEmpty().map { leg -> leg.to.poiId })
    }

    @Test
    fun moveStopRegeneratesLegOrderForNewSequence() = runBlocking {
        val plan = sampleRoutePlan()
        val useCase = RoutePreviewMutationUseCase(
            repository = PlannerRepository(
                remoteDataSource = FakePlannerRemoteDataSource(
                    durationByEndPoiId = mapOf(
                        1 to 5,
                        2 to 6,
                        3 to 4,
                        -1 to 7
                    )
                ),
                localDataSource = FakePlannerLocalDataSource(),
                routeSessionRepository = FakeRouteSessionRepository(),
                routeBookmarkRepository = FakeRouteBookmarkRepository(),
                offlineSyncRepository = FakeOfflineSyncRepository()
            )
        )

        val updated = useCase.moveStop(
            previousResponse = plan,
            poiId = 3,
            direction = -1,
            context = RoutePreviewMutationContext(
                city = "nitra",
                pace = "normal",
                startDateTime = "2026-05-19T10:00:00",
                transportMode = "walk"
            )
        )

        assertEquals(listOf(1, 3, 2), updated.route.map { item -> item.poiId })
        assertEquals(listOf(1, 2, 3), updated.route.map { item -> item.order })
        assertEquals(listOf(1, 3, 2, null), updated.legs.orEmpty().map { leg -> leg.to.poiId })
        assertEquals(listOf(1, 2, 3, 4), updated.legs.orEmpty().map { leg -> leg.order })
        assertEquals(52, updated.usedMinutes)
        assertEquals(30, updated.totalVisitMinutes)
        assertEquals(22, updated.totalWalkMinutes)
    }

    @Test
    fun mergeReroutedRoutePlanKeepsVisitedStopsAndAppendsReroutedRemainingStops() {
        val previous = sampleRoutePlan()
        val rerouted = routePlan(
            route = listOf(
                routeStop(id = 4, order = 1, travelMinutes = 3, arrivalAfterMin = 3, departureAfterMin = 13),
                routeStop(id = 5, order = 2, travelMinutes = 4, arrivalAfterMin = 17, departureAfterMin = 27)
            ),
            legs = listOf(
                routeLeg(1, startEndpoint(), routeStop(id = 4, order = 1).toEndpoint(), 3),
                routeLeg(2, routeStop(id = 4, order = 1).toEndpoint(), routeStop(id = 5, order = 2).toEndpoint(), 4)
            ),
            usedMinutes = 27,
            totalVisitMinutes = 20,
            totalWalkMinutes = 7,
            returnToStartMinutes = 0,
            poiCount = 2
        )

        val merged = mergeReroutedRoutePlan(
            previousResponse = previous,
            reroutedResponse = rerouted,
            visitedPoiIds = listOf(1)
        )

        assertEquals(listOf(1, 4, 5), merged.route.map { item -> item.poiId })
        assertEquals(listOf(1, 2, 3), merged.route.map { item -> item.order })
        assertEquals(18, merged.route[1].arrivalAfterMin)
        assertEquals(42, merged.usedMinutes)
        assertEquals(30, merged.totalVisitMinutes)
        assertEquals(12, merged.totalWalkMinutes)
    }

    @Test
    fun finalizedHandledRoutePlanKeepsOnlyVisitedAndSkippedStops() {
        val plan = sampleRoutePlan()

        val finalized = finalizedHandledRoutePlan(
            previousResponse = plan,
            visitedPoiIds = listOf(1),
            skippedPoiIds = listOf(3)
        )

        assertEquals(listOf(1, 3), finalized.route.map { item -> item.poiId })
        assertEquals(listOf(1, 2), finalized.route.map { item -> item.order })
        assertNull(finalized.legs?.lastOrNull { leg -> leg.to.type == "start" })
        assertEquals(51, finalized.usedMinutes)
        assertEquals(20, finalized.totalVisitMinutes)
    }

    @Test
    fun cacheEnvelopeTypesStayUnique() {
        val types = listOf(
            CacheEnvelopeType.ACTIVE_ROUTE_SESSION,
            CacheEnvelopeType.ROUTE_FEEDBACK_REQUEST,
            CacheEnvelopeType.ROUTE_HISTORY_ENTRY,
            CacheEnvelopeType.ROUTE_SESSION_CREATE_REQUEST,
            CacheEnvelopeType.ROUTE_SESSION_POI_VISIT_REQUEST,
            CacheEnvelopeType.SAVED_ROUTE_SNAPSHOT
        )

        assertEquals(types.size, types.distinct().size)
    }

    private fun sampleRoutePlan(): RoutePlan =
        routePlan(
            route = listOf(
                routeStop(id = 1, order = 1, travelMinutes = 5, arrivalAfterMin = 5, departureAfterMin = 15),
                routeStop(id = 2, order = 2, travelMinutes = 7, arrivalAfterMin = 22, departureAfterMin = 32),
                routeStop(id = 3, order = 3, travelMinutes = 9, arrivalAfterMin = 41, departureAfterMin = 51)
            ),
            legs = listOf(
                routeLeg(
                    order = 1,
                    from = startEndpoint(),
                    to = routeStop(id = 1, order = 1).toEndpoint(),
                    minutes = 5
                ),
                routeLeg(
                    order = 2,
                    from = routeStop(id = 1, order = 1).toEndpoint(),
                    to = routeStop(id = 2, order = 2).toEndpoint(),
                    minutes = 7
                ),
                routeLeg(
                    order = 3,
                    from = routeStop(id = 2, order = 2).toEndpoint(),
                    to = routeStop(id = 3, order = 3).toEndpoint(),
                    minutes = 9
                ),
                routeLeg(
                    order = 4,
                    from = routeStop(id = 3, order = 3).toEndpoint(),
                    to = startEndpoint(),
                    minutes = 6
                )
            ),
            usedMinutes = 57,
            totalVisitMinutes = 30,
            totalWalkMinutes = 27,
            returnToStartMinutes = 6,
            poiCount = 3
        )

    private fun routePlan(
        route: List<RouteStop>,
        legs: List<RouteLeg>?,
        usedMinutes: Int,
        totalVisitMinutes: Int,
        totalWalkMinutes: Int,
        returnToStartMinutes: Int,
        poiCount: Int
    ): RoutePlan =
        RoutePlan(
            city = "nitra",
            start = RoutePoint(lat = 0.0, lon = 0.0),
            startDateTime = "2026-05-19T10:00:00",
            pace = "normal",
            interests = listOf("museum"),
            transportMode = "walk",
            returnToStart = true,
            respectOpeningHours = true,
            availableMinutes = 120,
            usedMinutes = usedMinutes,
            remainingMinutes = 120 - usedMinutes,
            totalVisitMinutes = totalVisitMinutes,
            totalWalkMinutes = totalWalkMinutes,
            returnToStartMinutes = returnToStartMinutes,
            poiCount = poiCount,
            route = route,
            legs = legs,
            fullGeometry = mergeLegGeometries(legs.orEmpty())
        )

    private fun routeStop(
        id: Int,
        order: Int,
        travelMinutes: Int = 0,
        arrivalAfterMin: Int = 0,
        departureAfterMin: Int = arrivalAfterMin + 10
    ): RouteStop =
        RouteStop(
            order = order,
            poiId = id,
            name = "Stop $id",
            category = "museum",
            lat = id.toDouble(),
            lon = id.toDouble(),
            travelMinutesFromPrevious = travelMinutes,
            visitDurationMin = 10,
            arrivalAfterMin = arrivalAfterMin,
            departureAfterMin = departureAfterMin,
            baseScore = id.toDouble()
        )

    private fun routeLeg(
        order: Int,
        from: RouteLegEndpoint,
        to: RouteLegEndpoint,
        minutes: Int
    ): RouteLeg =
        RouteLeg(
            order = order,
            mode = "walk",
            from = from,
            to = to,
            durationSeconds = minutes * 60.0,
            durationMinutes = minutes,
            distanceMeters = minutes * 100.0,
            geometry = listOf(
                RouteCoordinate(lat = from.lat, lon = from.lon),
                RouteCoordinate(lat = to.lat, lon = to.lon)
            ),
            routingSource = "test"
        )

    private fun startEndpoint(): RouteLegEndpoint =
        RouteLegEndpoint(
            type = "start",
            poiId = null,
            name = null,
            lat = 0.0,
            lon = 0.0
        )

    private fun RouteStop.toEndpoint(): RouteLegEndpoint =
        RouteLegEndpoint(
            type = "poi",
            poiId = poiId,
            name = name,
            lat = lat,
            lon = lon
        )

    private fun Poi.toEndpoint(): RouteLegEndpoint =
        RouteLegEndpoint(
            type = "poi",
            poiId = id,
            name = name,
            lat = lat,
            lon = lon
        )

    private fun historyEntry(
        routeId: String,
        status: RouteSessionStatus,
        visitedPoiIds: List<Int> = emptyList(),
        skippedPoiIds: List<Int> = emptyList(),
        feedback: RouteFeedback? = null,
        updatedAtEpochMs: Long
    ): RouteHistoryEntry =
        RouteHistoryEntry(
            routeId = routeId,
            cityName = "Nitra",
            status = status.rawValue,
            startedAt = "2026-05-19T10:00:00",
            finishedAt = if (status.isTerminal()) "2026-05-19T11:00:00" else null,
            availableMinutes = 120,
            usedMinutes = 60,
            totalWalkMinutes = 20,
            totalVisitMinutes = 40,
            snapshot = SavedRouteSnapshot(
                request = PlannerPreferences(
                    city = "nitra",
                    startLat = 0.0,
                    startLon = 0.0,
                    availableMinutes = 120,
                    interests = listOf("museum"),
                    pace = "normal"
                ),
                response = sampleRoutePlan()
            ),
            visitedPoiIds = visitedPoiIds,
            skippedPoiIds = skippedPoiIds,
            feedback = feedback,
            updatedAtEpochMs = updatedAtEpochMs
        )

    private inner class FakePlannerRemoteDataSource(
        private val durationByEndPoiId: Map<Int, Int>
    ) : PlannerRemoteDataSource {
        override suspend fun fetchCities(): List<City> = unsupported()

        override suspend fun fetchPois(citySlug: String): List<Poi> = unsupported()

        override suspend fun generateRoute(request: PlannerPreferences): RoutePlan = unsupported()

        override suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg {
            val toEndpoint = RouteLegEndpoint(
                type = if (request.endPoiId == -1) "start" else "poi",
                poiId = request.endPoiId.takeIf { id -> id != -1 },
                name = request.endName,
                lat = request.endLat,
                lon = request.endLon
            )
            return routeLeg(
                order = 0,
                from = RouteLegEndpoint(
                    type = "unknown",
                    poiId = null,
                    name = null,
                    lat = request.startLat,
                    lon = request.startLon
                ),
                to = toEndpoint,
                minutes = durationByEndPoiId.getValue(request.endPoiId)
            )
        }

        override suspend fun getRouteSession(routeId: String): RouteSession = unsupported()

        override suspend fun getRouteSessions(deviceId: String): List<RouteSession> = unsupported()
    }

    private inner class FakePlannerLocalDataSource : PlannerLocalDataSource {
        override fun getOrCreateDeviceId(): String = "test-device"

        override suspend fun cacheCities(cities: List<City>) = Unit

        override suspend fun getCachedCities(): List<City> = emptyList()

        override suspend fun cachePois(citySlug: String, pois: List<Poi>) = Unit

        override suspend fun getCachedPois(citySlug: String): List<Poi> = emptyList()

        override suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) = Unit

        override suspend fun loadSnapshot(): SavedRouteSnapshot? = null

        override suspend fun saveActiveSession(session: ActiveRouteSession) = Unit

        override suspend fun loadActiveSession(): ActiveRouteSession? = null

        override suspend fun clearActiveSession() = Unit

        override suspend fun saveRouteBookmark(bookmark: RouteBookmark) = Unit

        override suspend fun loadRouteBookmarks(): List<RouteBookmark> = emptyList()

        override suspend fun loadRouteBookmark(bookmarkId: String): RouteBookmark? = null

        override suspend fun deleteRouteBookmark(bookmarkId: String) = Unit

        override suspend fun saveRouteHistoryEntry(entry: RouteHistoryEntry) = Unit

        override suspend fun saveRouteHistoryEntries(entries: List<RouteHistoryEntry>) = Unit

        override suspend fun loadRouteHistoryEntries(): List<RouteHistoryEntry> = emptyList()
    }

    private inner class FakeRouteSessionRepository : RouteSessionRepository {
        override suspend fun getRouteSession(routeId: String): RouteSession = unsupported()

        override suspend fun getRouteSessions(deviceId: String): List<RouteSession> = unsupported()

        override suspend fun saveActiveSession(session: ActiveRouteSession) = Unit

        override suspend fun loadActiveSession(): ActiveRouteSession? = null

        override suspend fun clearActiveSession() = Unit

        override suspend fun saveHistoryEntry(entry: RouteHistoryEntry) = Unit

        override suspend fun saveHistoryEntries(entries: List<RouteHistoryEntry>) = Unit

        override suspend fun loadHistoryEntries(): List<RouteHistoryEntry> = emptyList()
    }

    private inner class FakeRouteBookmarkRepository : RouteBookmarkRepository {
        override suspend fun saveBookmark(bookmark: RouteBookmark) = Unit

        override suspend fun loadBookmarks(): List<RouteBookmark> = emptyList()

        override suspend fun loadBookmark(bookmarkId: String): RouteBookmark? = null

        override suspend fun deleteBookmark(bookmarkId: String) = Unit
    }

    private inner class FakeOfflineSyncRepository : OfflineSyncRepository {
        override fun isNetworkAvailable(): Boolean = true

        override suspend fun getPendingSyncOperationCount(): Int = 0

        override suspend fun enqueuePendingRouteSession(
            sessionRouteId: String,
            deviceId: String,
            status: String,
            startedAt: String,
            snapshot: SavedRouteSnapshot,
            finishedAt: String?
        ) = Unit

        override suspend fun enqueuePendingPoiVisit(
            sessionId: String,
            poiId: Int,
            visitedAt: String,
            skipped: Boolean
        ) = Unit

        override suspend fun enqueuePendingFeedback(
            sessionId: String,
            feedback: RouteFeedback
        ) = Unit

        override fun scheduleImmediateSync() = Unit
    }

    private fun <T> unsupported(): T =
        error("Not used by this test")
}
