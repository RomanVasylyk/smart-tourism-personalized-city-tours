package com.example.smarttourism.features.planner.application

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegEndpoint
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.sampleRouteLeg
import com.example.smarttourism.features.planner.sampleRoutePlan
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RoutePreviewMutationUseCaseTest {
    @Test
    fun removeLastStopRegeneratesReturnLegAndTotals() = runBlocking {
        val useCase = RoutePreviewMutationUseCase(
            routePlanningRepository = FakeRoutePlanningRepository(
                durationByEndPoiId = mapOf(-1 to 4)
            )
        )

        val updated = useCase.removeStop(
            previousResponse = sampleRoutePlan(),
            poiId = 3,
            context = mutationContext()
        )

        assertEquals(listOf(1, 2), updated.route.map { stop -> stop.poiId })
        assertEquals(listOf(1, 2, null), updated.legs.orEmpty().map { leg -> leg.to.poiId })
        assertEquals(4, updated.returnToStartMinutes)
        assertEquals(36, updated.usedMinutes)
        assertEquals(20, updated.totalVisitMinutes)
        assertEquals(16, updated.totalWalkMinutes)
        assertFalse(updated.route.any { stop -> stop.poiId == 3 })
    }

    @Test
    fun replaceLastStopRegeneratesIncomingAndReturnLegOnly() = runBlocking {
        val replacement = Poi(
            id = 9,
            name = "Chosen replacement",
            category = "gallery",
            lat = 49.0,
            lon = 19.0,
            visitDurationMin = 15,
            baseScore = 9.0
        )
        val useCase = RoutePreviewMutationUseCase(
            routePlanningRepository = FakeRoutePlanningRepository(
                durationByEndPoiId = mapOf(9 to 6, -1 to 8)
            )
        )

        val updated = useCase.replaceStop(
            previousResponse = sampleRoutePlan(),
            targetPoiId = 3,
            replacementPoi = replacement,
            context = mutationContext()
        )

        assertEquals(listOf(1, 2, 9), updated.route.map { stop -> stop.poiId })
        assertEquals(3, updated.poiCount)
        assertEquals("Chosen replacement", updated.route[2].name)
        assertEquals(6, updated.route[2].travelMinutesFromPrevious)
        assertEquals(38, updated.route[2].arrivalAfterMin)
        assertEquals(53, updated.route[2].departureAfterMin)
        assertEquals(8, updated.returnToStartMinutes)
        assertEquals(61, updated.usedMinutes)
        assertEquals(listOf(1, 2, 9, null), updated.legs.orEmpty().map { leg -> leg.to.poiId })
    }

    private fun mutationContext(): RoutePreviewMutationContext =
        RoutePreviewMutationContext(
            city = "nitra",
            pace = "normal",
            startDateTime = "2026-05-19T10:00:00",
            transportMode = "walk"
        )

    private inner class FakeRoutePlanningRepository(
        private val durationByEndPoiId: Map<Int, Int>
    ) : RoutePlanningRepository {
        override suspend fun generateRoute(request: PlannerPreferences): RoutePlan =
            error("Not used by this test")

        override suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg {
            val toEndpoint = RouteLegEndpoint(
                type = if (request.endPoiId == -1) "start" else "poi",
                poiId = request.endPoiId.takeIf { id -> id != -1 },
                name = request.endName,
                lat = request.endLat,
                lon = request.endLon
            )
            return sampleRouteLeg(
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

        override suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) = Unit

        override suspend fun loadSnapshot(): SavedRouteSnapshot? = null
    }
}
