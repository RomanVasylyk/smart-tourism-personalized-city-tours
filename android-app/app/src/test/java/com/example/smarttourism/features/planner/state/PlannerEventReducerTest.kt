package com.example.smarttourism.features.planner.state

import com.example.smarttourism.features.planner.samplePlannerPreferences
import com.example.smarttourism.features.planner.sampleRoutePlan
import com.example.smarttourism.features.planner.sampleSnapshot
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.RoutingLimits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PlannerEventReducerTest {
    @Test
    fun updateAvailableMinutesClampsToSelectedCityLimitAndInvalidatesPreview() {
        val state = RoutePlannerUiState(
            selectedCity = City(
                slug = "nitra",
                routingLimits = RoutingLimits(maxAvailableMinutes = 180)
            ),
            routeResponse = sampleRoutePlan(),
            hasPendingRouteChanges = false,
            hasNoGeneratedStops = true
        )

        val updated = PlannerStateReducer.updateAvailableMinutes(state, 244)

        assertEquals(180, updated.availableMinutes)
        assertTrue(updated.hasPendingRouteChanges)
        assertFalse(updated.hasNoGeneratedStops)
    }

    @Test
    fun toggleRequiredPoiIsIgnoredDuringActiveRoute() {
        val state = RoutePlannerUiState(
            routeSessionStatus = RouteSessionStatus.IN_PROGRESS,
            requiredPoiIds = listOf(1),
            currentRouteRequest = samplePlannerPreferences().copy(preferredPoiIds = listOf(1))
        )

        val updated = PlannerStateReducer.toggleRequiredPoi(state, poiId = 2)

        assertSame(state, updated)
        assertEquals(listOf(1), updated.requiredPoiIds)
        assertEquals(listOf(1), updated.currentRouteRequest?.preferredPoiIds)
    }

    @Test
    fun moveRequiredPoiUpdatesRequestOrderWithoutChangingRouteStops() {
        val request = samplePlannerPreferences().copy(preferredPoiIds = listOf(1, 2, 3))
        val route = sampleRoutePlan()
        val state = RoutePlannerUiState(
            routeSessionStatus = RouteSessionStatus.NOT_STARTED,
            routeResponse = route,
            currentRouteRequest = request,
            requiredPoiIds = listOf(1, 2, 3)
        )

        val updated = PlannerStateReducer.moveRequiredPoi(state, poiId = 3, direction = -1)

        assertEquals(listOf(1, 3, 2), updated.requiredPoiIds)
        assertEquals(listOf(1, 3, 2), updated.currentRouteRequest?.preferredPoiIds)
        assertEquals(listOf(1, 2, 3), updated.routeResponse?.route?.map { stop -> stop.poiId })
        assertTrue(updated.hasPendingRouteChanges)
    }

    @Test
    fun restoreSnapshotNormalizesRequestAndSelectedState() {
        val snapshot = sampleSnapshot(
            request = samplePlannerPreferences().copy(
                availableMinutes = 95,
                preferredPoiIds = listOf(3, 3, 2),
                transportMode = "walk_or_mhd"
            )
        )
        val parsedStart = LocalDateTime.parse("2026-05-19T10:00:00")

        val updated = PlannerStateReducer.restoreSnapshot(
            state = RoutePlannerUiState(hasPendingRouteChanges = true, hasNoGeneratedStops = true),
            snapshot = snapshot,
            parsedStartDateTime = parsedStart
        )

        assertFalse(updated.hasPendingRouteChanges)
        assertFalse(updated.hasNoGeneratedStops)
        assertEquals(90, updated.availableMinutes)
        assertEquals(listOf(3, 2), updated.requiredPoiIds)
        assertEquals("walk_or_mhd", updated.currentRouteRequest?.transportMode)
        assertTrue(updated.allowPublicTransport)
        assertEquals(parsedStart, updated.startDateTime)
    }
}
