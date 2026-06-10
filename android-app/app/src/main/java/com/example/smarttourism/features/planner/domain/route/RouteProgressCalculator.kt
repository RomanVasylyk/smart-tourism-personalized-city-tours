package com.example.smarttourism.features.planner.domain.route

import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.OffRouteDistanceMeters
import com.example.smarttourism.features.planner.state.PoiVisitedRadiusMeters
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal fun routeProgressMetrics(
    routeResponse: RoutePlan?,
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    currentLocation: RoutePoint?,
    isTracking: Boolean
): RouteProgressMetrics {
    val visitedIds = visitedPoiIds.distinct()
    val skippedIds = skippedPoiIds.distinct()
    val nextTarget = nextPendingPoi(routeItems, visitedIds, skippedIds)
    val distanceToNextTargetMeters = if (currentLocation != null && nextTarget != null) {
        distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
    } else {
        null
    }
    val totalCount = progressTotalCount(routeItems, skippedIds)
    val visitedCount = visitedIds.size.coerceAtMost(totalCount)
    val isOffRoute = isTracking &&
        currentLocation != null &&
        nextTarget != null &&
        distanceToNextRouteSegmentMeters(routeResponse, nextTarget.poiId, currentLocation) > OffRouteDistanceMeters

    return RouteProgressMetrics(
        visitedCount = visitedCount,
        totalCount = totalCount,
        nextTarget = nextTarget,
        distanceToNextTargetMeters = distanceToNextTargetMeters,
        estimatedRemainingMinutes = estimateRemainingMinutes(
            routeResponse = routeResponse,
            routeItems = routeItems,
            visitedPoiIds = visitedIds,
            skippedPoiIds = skippedIds,
            currentLocation = currentLocation,
            nextTarget = nextTarget
        ),
        isOffRoute = isOffRoute,
        canComplete = totalCount > 0 && visitedCount >= requiredCompletionCount(totalCount)
    )
}

internal fun progressTotalCount(
    routeItems: List<RouteStop>,
    skippedPoiIds: List<Int>
): Int =
    routeItems.count { item -> item.poiId !in skippedPoiIds }

internal fun requiredCompletionCount(totalCount: Int): Int =
    ceil(totalCount / 2.0).toInt().coerceAtLeast(1)

internal fun nextPendingPoi(
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>
): RouteStop? =
    routeItems.firstOrNull { item -> item.poiId !in visitedPoiIds && item.poiId !in skippedPoiIds }

internal fun estimateRemainingMinutes(
    routeResponse: RoutePlan?,
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    currentLocation: RoutePoint?,
    nextTarget: RouteStop?
): Int {
    val remainingItems = routeItems.filter { item ->
        item.poiId !in visitedPoiIds && item.poiId !in skippedPoiIds
    }
    if (remainingItems.isEmpty()) {
        return 0
    }

    val firstWalkMinutes = if (currentLocation != null && nextTarget != null) {
        val distanceMeters = distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
        estimateWalkingMinutes(distanceMeters, routeResponse?.pace)
    } else {
        remainingItems.first().travelMinutesFromPrevious
    }
    val remainingVisits = remainingItems.sumOf { item -> item.visitDurationMin }
    val remainingWalksAfterTarget = remainingItems.drop(1).sumOf { item ->
        item.travelMinutesFromPrevious
    }
    val returnToStartMinutes = if (routeResponse?.returnToStart == true) {
        routeResponse.returnToStartMinutes
    } else {
        0
    }

    return firstWalkMinutes + remainingVisits + remainingWalksAfterTarget + returnToStartMinutes
}

internal fun rerouteStartPoint(
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    currentLocation: RoutePoint?,
    fallbackStart: RoutePoint
): RoutePoint {
    currentLocation?.let { return it }

    val lastVisitedStop = routeItems
        .filter { item -> item.poiId in visitedPoiIds }
        .maxByOrNull { item -> item.order }

    return lastVisitedStop?.let { stop ->
        RoutePoint(lat = stop.lat, lon = stop.lon)
    } ?: fallbackStart
}

internal fun estimateWalkingMinutes(
    distanceMeters: Float,
    pace: String?
): Int {
    val speedMetersPerMinute = when (pace) {
        "slow" -> 4_000.0 / 60.0
        "fast" -> 5_600.0 / 60.0
        else -> 4_800.0 / 60.0
    }
    return maxOf(1, ceil(distanceMeters / speedMetersPerMinute).toInt())
}

internal fun distanceToNextRouteSegmentMeters(
    routeResponse: RoutePlan?,
    nextTargetPoiId: Int,
    currentLocation: RoutePoint
): Float {
    val legGeometry = routeResponse
        ?.legs
        .orEmpty()
        .firstOrNull { leg -> leg.to.poiId == nextTargetPoiId }
        ?.geometry
        .orEmpty()

    if (legGeometry.size < 2) {
        val nextTarget = routeResponse
            ?.route
            .orEmpty()
            .firstOrNull { item -> item.poiId == nextTargetPoiId }
            ?: return 0f

        return distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
    }

    return legGeometry
        .zipWithNext()
        .minOf { (start, end) ->
            distanceToSegmentMeters(
                point = currentLocation,
                startLat = start.lat,
                startLon = start.lon,
                endLat = end.lat,
                endLon = end.lon
            )
        }
}

internal fun distanceToSegmentMeters(
    point: RoutePoint,
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double
): Float {
    val segmentDistance = distanceMeters(startLat, startLon, endLat, endLon)
    if (segmentDistance == 0f) {
        return distanceMeters(point.lat, point.lon, startLat, startLon)
    }

    val referenceLatRadians = Math.toRadians(point.lat)
    val startX = longitudeToMeters(startLon - point.lon, referenceLatRadians)
    val startY = latitudeToMeters(startLat - point.lat)
    val endX = longitudeToMeters(endLon - point.lon, referenceLatRadians)
    val endY = latitudeToMeters(endLat - point.lat)
    val segmentX = endX - startX
    val segmentY = endY - startY
    val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY
    val projectionRatio = if (segmentLengthSquared == 0.0) {
        0.0
    } else {
        ((-startX * segmentX + -startY * segmentY) / segmentLengthSquared).coerceIn(0.0, 1.0)
    }
    val projectedX = startX + segmentX * projectionRatio
    val projectedY = startY + segmentY * projectionRatio

    return sqrt(projectedX * projectedX + projectedY * projectedY).toFloat()
}

internal fun latitudeToMeters(latitudeDelta: Double): Double =
    latitudeDelta * 111_320.0

internal fun longitudeToMeters(
    longitudeDelta: Double,
    referenceLatRadians: Double
): Double =
    longitudeDelta * 111_320.0 * cos(referenceLatRadians)

internal fun markNearbyPoisVisited(
    routeItems: List<RouteStop>,
    currentLocation: RoutePoint,
    visitedPoiIds: MutableList<Int>,
    skippedPoiIds: List<Int>
): List<Int> {
    val newlyVisitedIds = routeItems
        .filter { item -> item.poiId !in visitedPoiIds && item.poiId !in skippedPoiIds }
        .filter { item ->
            distanceMeters(
                startLat = currentLocation.lat,
                startLon = currentLocation.lon,
                endLat = item.lat,
                endLon = item.lon
            ) <= PoiVisitedRadiusMeters
        }
        .map { item -> item.poiId }

    visitedPoiIds.addAll(newlyVisitedIds)
    return newlyVisitedIds
}

internal fun distanceMeters(
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double
): Float {
    val earthRadiusMeters = 6_371_000.0
    val startLatRadians = Math.toRadians(startLat)
    val endLatRadians = Math.toRadians(endLat)
    val latDelta = Math.toRadians(endLat - startLat)
    val lonDelta = Math.toRadians(endLon - startLon)
    val haversine = sin(latDelta / 2) * sin(latDelta / 2) +
        cos(startLatRadians) * cos(endLatRadians) *
        sin(lonDelta / 2) * sin(lonDelta / 2)
    val angularDistance = 2 * atan2(sqrt(haversine), sqrt(1 - haversine))
    return (earthRadiusMeters * angularDistance).toFloat()
}
