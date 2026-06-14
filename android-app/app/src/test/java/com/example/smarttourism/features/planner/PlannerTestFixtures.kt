package com.example.smarttourism.features.planner

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.domain.history.isTerminal
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RouteCoordinate
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegEndpoint
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.route.mergeLegGeometries
import com.example.smarttourism.features.planner.state.RouteSessionStatus

internal fun sampleSnapshot(
    request: PlannerPreferences = samplePlannerPreferences(),
    response: RoutePlan = sampleRoutePlan()
): SavedRouteSnapshot =
    SavedRouteSnapshot(
        request = request,
        response = response
    )

internal fun samplePlannerPreferences(): PlannerPreferences =
    PlannerPreferences(
        city = "nitra",
        startLat = 48.309,
        startLon = 18.086,
        availableMinutes = 120,
        interests = listOf("museum", "park"),
        pace = "normal",
        returnToStart = true,
        startDateTime = "2026-05-19T10:00:00",
        respectOpeningHours = true,
        preferredPoiIds = listOf(1, 2),
        transportMode = "walk"
    )

internal fun sampleRoutePlan(
    route: List<RouteStop> = listOf(
        sampleRouteStop(id = 1, order = 1, travelMinutes = 5, arrivalAfterMin = 5, departureAfterMin = 15),
        sampleRouteStop(id = 2, order = 2, travelMinutes = 7, arrivalAfterMin = 22, departureAfterMin = 32),
        sampleRouteStop(id = 3, order = 3, travelMinutes = 9, arrivalAfterMin = 41, departureAfterMin = 51)
    ),
    legs: List<RouteLeg>? = null,
    usedMinutes: Int = 57,
    totalVisitMinutes: Int = 30,
    totalWalkMinutes: Int = 27,
    returnToStartMinutes: Int = 6,
    poiCount: Int = route.size
): RoutePlan {
    val effectiveLegs = legs ?: buildDefaultLegs(route)
    return RoutePlan(
        city = "nitra",
        start = RoutePoint(lat = 48.309, lon = 18.086),
        startDateTime = "2026-05-19T10:00:00",
        pace = "normal",
        interests = listOf("museum", "park"),
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
        legs = effectiveLegs,
        fullGeometry = mergeLegGeometries(effectiveLegs.orEmpty())
    )
}

internal fun sampleRouteStop(
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
        lat = 48.0 + id,
        lon = 18.0 + id,
        travelMinutesFromPrevious = travelMinutes,
        visitDurationMin = 10,
        arrivalAfterMin = arrivalAfterMin,
        departureAfterMin = departureAfterMin,
        baseScore = id.toDouble()
    )

internal fun sampleRouteLeg(
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

internal fun startEndpoint(): RouteLegEndpoint =
    RouteLegEndpoint(
        type = "start",
        poiId = null,
        name = null,
        lat = 48.309,
        lon = 18.086
    )

internal fun RouteStop.toEndpoint(): RouteLegEndpoint =
    RouteLegEndpoint(
        type = "poi",
        poiId = poiId,
        name = name,
        lat = lat,
        lon = lon
    )

internal fun historyEntry(
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
        usedMinutes = 57,
        totalWalkMinutes = 27,
        totalVisitMinutes = 30,
        snapshot = sampleSnapshot(),
        visitedPoiIds = visitedPoiIds,
        skippedPoiIds = skippedPoiIds,
        feedback = feedback,
        updatedAtEpochMs = updatedAtEpochMs
    )

private fun buildDefaultLegs(route: List<RouteStop>): List<RouteLeg> {
    if (route.isEmpty()) {
        return emptyList()
    }

    val legs = mutableListOf<RouteLeg>()
    var from = startEndpoint()
    route.forEachIndexed { index, stop ->
        val to = stop.toEndpoint()
        legs += sampleRouteLeg(
            order = index + 1,
            from = from,
            to = to,
            minutes = stop.travelMinutesFromPrevious
        )
        from = to
    }
    legs += sampleRouteLeg(
        order = legs.size + 1,
        from = from,
        to = startEndpoint(),
        minutes = 6
    )
    return legs
}
