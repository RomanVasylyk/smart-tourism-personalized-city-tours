package com.example.smarttourism.features.map

import android.content.Context
import android.graphics.Color
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteSegment
import com.example.smarttourism.features.planner.domain.model.RouteStop
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.util.Locale
import kotlin.math.cos

internal fun drawRoutePaths(
    map: MapLibreMap,
    renderedPaths: List<RenderedRoutePath>,
    isRouteActive: Boolean
) {
    val stagePriority = mapOf(
        RoutePathStage.COMPLETED to 0,
        RoutePathStage.UPCOMING to 1,
        RoutePathStage.ACTIVE to 2,
    )

    renderedPaths
        .withIndex()
        .sortedWith(compareBy({ stagePriority[it.value.stage] ?: 0 }, { it.index }))
        .forEach { indexedPath ->
            val path = indexedPath.value
            map.addPolyline(
                PolylineOptions()
                    .addAll(path.points)
                    .color(Color.parseColor(routeLineColor(path.mode, path.colorKey, path.stage, isRouteActive)))
                    .width(routeLineWidth(path.stage, isRouteActive))
                    .alpha(routeLineAlpha(path.stage, isRouteActive))
            )
        }
}

internal fun drawTransitLineLabels(
    context: Context,
    map: MapLibreMap,
    labels: List<RenderedRouteLabel>,
    isRouteActive: Boolean
) {
    val stagePriority = mapOf(
        RoutePathStage.COMPLETED to 0,
        RoutePathStage.UPCOMING to 1,
        RoutePathStage.ACTIVE to 2,
    )
    val iconFactory = IconFactory.getInstance(context)
    val iconCache = mutableMapOf<String, Icon>()

    labels
        .sortedWith(compareBy({ stagePriority[it.stage] ?: 0 }, { it.text }))
        .forEach { label ->
            val iconKey = "${label.text}:${label.colorKey}:${label.stage}:${isRouteActive}"
            val icon = iconCache.getOrPut(iconKey) {
                createTransitLineLabelIcon(
                    iconFactory = iconFactory,
                    context = context,
                    label = label.text,
                    colorKey = label.colorKey,
                    stage = label.stage
                )
            }
            map.addMarker(
                MarkerOptions()
                    .position(label.point)
                    .icon(icon)
            )
        }
}

internal fun buildRenderedRoutePaths(
    routeResponse: RoutePlan?,
    routeItems: List<RouteStop>,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>,
    isRouteActive: Boolean
): List<RenderedRoutePath> {
    val legs = routeResponse?.legs.orEmpty()
    if (legs.isEmpty()) {
        return emptyList()
    }

    val handledPoiIds = visitedPoiIds + skippedPoiIds
    val allPoisHandled = routeItems.isNotEmpty() && routeItems.all { item -> item.poiId in handledPoiIds }
    val activeLegOrder = if (isRouteActive) {
        legs.firstOrNull { leg -> leg.to.type == "poi" && leg.to.poiId !in handledPoiIds }?.order
            ?: if (allPoisHandled) {
                legs.firstOrNull { leg -> leg.to.type == "start" }?.order
            } else {
                null
            }
    } else {
        null
    }

    return legs.flatMap { leg ->
        val stage = when {
            !isRouteActive -> RoutePathStage.ACTIVE
            leg.to.type == "poi" && leg.to.poiId in handledPoiIds -> RoutePathStage.COMPLETED
            activeLegOrder != null && leg.order == activeLegOrder -> RoutePathStage.ACTIVE
            else -> RoutePathStage.UPCOMING
        }

        segmentDtosForLeg(leg).mapNotNull { segment ->
            val mode = segment.mode ?: leg.mode ?: "walk"
            val colorKey = transitColorKey(mode, leg.order, segment)
            val points = segment.geometry
                .orEmpty()
                .map { coordinate -> LatLng(coordinate.lat, coordinate.lon) }
                .withoutAdjacentDuplicates()
            if (points.size < 2) {
                null
            } else {
                RenderedRoutePath(
                    points = points,
                    mode = mode,
                    colorKey = colorKey,
                    stage = stage
                )
            }
        }
    }
}

internal fun buildTransitLineLabels(
    routeResponse: RoutePlan?,
    routeItems: List<RouteStop>,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>,
    isRouteActive: Boolean
): List<RenderedRouteLabel> {
    val legs = routeResponse?.legs.orEmpty()
    if (legs.isEmpty()) {
        return emptyList()
    }

    val handledPoiIds = visitedPoiIds + skippedPoiIds
    val allPoisHandled = routeItems.isNotEmpty() && routeItems.all { item -> item.poiId in handledPoiIds }
    val activeLegOrder = if (isRouteActive) {
        legs.firstOrNull { leg -> leg.to.type == "poi" && leg.to.poiId !in handledPoiIds }?.order
            ?: if (allPoisHandled) {
                legs.firstOrNull { leg -> leg.to.type == "start" }?.order
            } else {
                null
            }
    } else {
        null
    }

    return legs.flatMap { leg ->
        val stage = when {
            !isRouteActive -> RoutePathStage.ACTIVE
            leg.to.type == "poi" && leg.to.poiId in handledPoiIds -> RoutePathStage.COMPLETED
            activeLegOrder != null && leg.order == activeLegOrder -> RoutePathStage.ACTIVE
            else -> RoutePathStage.UPCOMING
        }

        segmentDtosForLeg(leg).mapNotNull { segment ->
            if (segment.mode != "transit") {
                return@mapNotNull null
            }

            val lineText = transitLineBadgeText(segment.lineName) ?: return@mapNotNull null
            val points = segment.geometry
                .orEmpty()
                .map { coordinate -> LatLng(coordinate.lat, coordinate.lon) }
                .withoutAdjacentDuplicates()
            val midpoint = points.polylineMidpoint() ?: return@mapNotNull null
            RenderedRouteLabel(
                point = midpoint,
                text = lineText,
                colorKey = transitColorKey("transit", leg.order, segment),
                stage = stage
            )
        }
    }
}

private fun segmentDtosForLeg(leg: RouteLeg): List<RouteSegment> =
    leg.segments.orEmpty().ifEmpty {
        listOf(
            RouteSegment(
                order = 1,
                mode = leg.mode,
                durationSeconds = leg.durationSeconds,
                durationMinutes = leg.durationMinutes,
                distanceMeters = leg.distanceMeters,
                geometry = leg.geometry,
                source = leg.routingSource,
                lineName = null,
                fromStopName = null,
                toStopName = null,
                departureTime = leg.departureTime,
                arrivalTime = leg.arrivalTime,
                waitMinutesBeforeDeparture = null,
                inVehicleMinutes = null
            )
        )
    }

private fun routeLineColor(
    mode: String,
    colorKey: String?,
    stage: RoutePathStage,
    isRouteActive: Boolean
): String {
    val normalizedMode = mode.lowercase(Locale.ROOT)
    if (normalizedMode == "transit") {
        return transitLineColor(colorKey, stage)
    }

    return when (stage) {
        RoutePathStage.COMPLETED -> RouteSecondaryLineColor
        RoutePathStage.ACTIVE -> RoutePrimaryLineColor
        RoutePathStage.UPCOMING -> if (isRouteActive) RouteSecondaryLineColor else RoutePrimaryLineColor
    }
}

private fun transitColorKey(
    mode: String,
    legOrder: Int,
    segment: RouteSegment
): String? {
    if (mode.lowercase(Locale.ROOT) != "transit") {
        return null
    }

    val lineName = segment.lineName?.trim()
    return if (lineName.isNullOrBlank()) {
        "transit:$legOrder:${segment.order}"
    } else {
        lineName
    }
}

internal fun transitLineColor(
    colorKey: String?,
    stage: RoutePathStage
): String {
    if (stage == RoutePathStage.COMPLETED) {
        return TransitCompletedLineColor
    }

    val key = colorKey?.takeIf { it.isNotBlank() } ?: "transit"
    val baseColor = TransitLineColors[stableColorIndex(key, TransitLineColors.size)]
    return when (stage) {
        RoutePathStage.COMPLETED -> TransitCompletedLineColor
        RoutePathStage.ACTIVE -> baseColor
        RoutePathStage.UPCOMING -> baseColor
    }
}

private fun stableColorIndex(value: String, size: Int): Int {
    var hash = 0
    value.forEach { character ->
        hash = (hash * 31) + character.code
    }
    return (hash and Int.MAX_VALUE) % size
}

private fun routeLineWidth(
    stage: RoutePathStage,
    isRouteActive: Boolean
): Float =
    when {
        !isRouteActive -> 6f
        stage == RoutePathStage.ACTIVE -> 7f
        stage == RoutePathStage.COMPLETED -> 5f
        else -> 6f
    }

private fun routeLineAlpha(
    stage: RoutePathStage,
    isRouteActive: Boolean
): Float =
    when {
        !isRouteActive -> 0.95f
        stage == RoutePathStage.ACTIVE -> 0.95f
        stage == RoutePathStage.COMPLETED -> 0.58f
        else -> 0.8f
    }

internal fun buildVisibleRoutePolylinePoints(
    polylinePoints: List<LatLng>,
    routeItems: List<RouteStop>,
    currentLocation: RoutePoint?,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>,
    shouldTrimPassedPath: Boolean
): List<LatLng> {
    if (!shouldTrimPassedPath || currentLocation == null || polylinePoints.size < 2) {
        return polylinePoints
    }

    val firstSearchSegmentIndex = firstRemainingSegmentIndex(
        polylinePoints = polylinePoints,
        routeItems = routeItems,
        visitedPoiIds = visitedPoiIds,
        skippedPoiIds = skippedPoiIds
    )
    val projection = closestProjectionOnRoute(
        polylinePoints = polylinePoints,
        currentLocation = currentLocation,
        firstSearchSegmentIndex = firstSearchSegmentIndex
    ) ?: return polylinePoints

    if (projection.distanceMeters > RouteTrimMaxSnapDistanceMeters) {
        return polylinePoints.drop(firstSearchSegmentIndex)
    }

    return buildList {
        add(projection.point)
        addAll(polylinePoints.drop(projection.segmentIndex + 1))
    }.withoutAdjacentDuplicates()
}

internal fun buildVisibleRouteSegments(
    polylinePoints: List<LatLng>,
    routeItems: List<RouteStop>,
    currentLocation: RoutePoint?,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>,
    shouldHighlightActiveSegment: Boolean
): VisibleRouteSegments {
    if (!shouldHighlightActiveSegment || currentLocation == null || polylinePoints.size < 2) {
        return VisibleRouteSegments(
            activeSegment = polylinePoints,
            remainingSegment = emptyList()
        )
    }

    val nextTarget = routeItems
        .filter { item -> item.poiId !in visitedPoiIds && item.poiId !in skippedPoiIds }
        .minByOrNull { item -> item.order }
        ?: return VisibleRouteSegments(emptyList(), emptyList())

    val firstSearchSegmentIndex = firstRemainingSegmentIndex(
        polylinePoints = polylinePoints,
        routeItems = routeItems,
        visitedPoiIds = visitedPoiIds,
        skippedPoiIds = skippedPoiIds
    )
    val projection = closestProjectionOnRoute(
        polylinePoints = polylinePoints,
        currentLocation = currentLocation,
        firstSearchSegmentIndex = firstSearchSegmentIndex
    ) ?: return VisibleRouteSegments(
        activeSegment = polylinePoints.drop(firstSearchSegmentIndex).withoutAdjacentDuplicates(),
        remainingSegment = emptyList()
    )

    if (projection.distanceMeters > RouteTrimMaxSnapDistanceMeters) {
        return VisibleRouteSegments(
            activeSegment = polylinePoints.drop(firstSearchSegmentIndex).withoutAdjacentDuplicates(),
            remainingSegment = emptyList()
        )
    }

    val nextTargetPointIndex = closestPolylinePointIndex(
        polylinePoints = polylinePoints,
        lat = nextTarget.lat,
        lon = nextTarget.lon
    )
    val activeEndIndex = nextTargetPointIndex
        .coerceAtLeast(projection.segmentIndex + 1)
        .coerceAtMost(polylinePoints.lastIndex)

    val activeSegment = buildList {
        add(projection.point)
        addAll(polylinePoints.subList(projection.segmentIndex + 1, activeEndIndex + 1))
    }.withoutAdjacentDuplicates()

    val remainingSegment = polylinePoints
        .drop(activeEndIndex)
        .withoutAdjacentDuplicates()

    return VisibleRouteSegments(
        activeSegment = activeSegment,
        remainingSegment = remainingSegment
    )
}

private fun firstRemainingSegmentIndex(
    polylinePoints: List<LatLng>,
    routeItems: List<RouteStop>,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>
): Int {
    val handledPoiIds = visitedPoiIds + skippedPoiIds
    val lastHandledItem = routeItems
        .filter { item -> item.poiId in handledPoiIds }
        .maxByOrNull { item -> item.order }
        ?: return 0

    val closestStopPointIndex = closestPolylinePointIndex(
        polylinePoints = polylinePoints,
        lat = lastHandledItem.lat,
        lon = lastHandledItem.lon
    )

    return closestStopPointIndex.coerceIn(0, polylinePoints.lastIndex - 1)
}

private fun closestProjectionOnRoute(
    polylinePoints: List<LatLng>,
    currentLocation: RoutePoint,
    firstSearchSegmentIndex: Int
): RouteProjection? {
    var closestProjection: RouteProjection? = null
    val startIndex = firstSearchSegmentIndex.coerceIn(0, polylinePoints.lastIndex - 1)

    for (index in startIndex until polylinePoints.lastIndex) {
        val projection = projectPointToSegment(
            pointLat = currentLocation.lat,
            pointLon = currentLocation.lon,
            segmentStart = polylinePoints[index],
            segmentEnd = polylinePoints[index + 1],
            segmentIndex = index
        )

        if (closestProjection == null || projection.distanceMeters < closestProjection.distanceMeters) {
            closestProjection = projection
        }
    }

    return closestProjection
}

private fun projectPointToSegment(
    pointLat: Double,
    pointLon: Double,
    segmentStart: LatLng,
    segmentEnd: LatLng,
    segmentIndex: Int
): RouteProjection {
    val referenceLatRadians = Math.toRadians(pointLat)
    val pointX = 0.0
    val pointY = 0.0
    val startX = longitudeToMeters(segmentStart.longitude - pointLon, referenceLatRadians)
    val startY = latitudeToMeters(segmentStart.latitude - pointLat)
    val endX = longitudeToMeters(segmentEnd.longitude - pointLon, referenceLatRadians)
    val endY = latitudeToMeters(segmentEnd.latitude - pointLat)
    val segmentX = endX - startX
    val segmentY = endY - startY
    val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY

    val projectionRatio = if (segmentLengthSquared == 0.0) {
        0.0
    } else {
        (((pointX - startX) * segmentX + (pointY - startY) * segmentY) / segmentLengthSquared)
            .coerceIn(0.0, 1.0)
    }
    val projectedX = startX + segmentX * projectionRatio
    val projectedY = startY + segmentY * projectionRatio
    val projectedLat = segmentStart.latitude + (segmentEnd.latitude - segmentStart.latitude) * projectionRatio
    val projectedLon = segmentStart.longitude + (segmentEnd.longitude - segmentStart.longitude) * projectionRatio
    val distanceMeters = kotlin.math.sqrt(projectedX * projectedX + projectedY * projectedY)

    return RouteProjection(
        point = LatLng(projectedLat, projectedLon),
        segmentIndex = segmentIndex,
        distanceMeters = distanceMeters
    )
}

private fun closestPolylinePointIndex(
    polylinePoints: List<LatLng>,
    lat: Double,
    lon: Double
): Int {
    val referenceLatRadians = Math.toRadians(lat)
    return polylinePoints.indices.minByOrNull { index ->
        val point = polylinePoints[index]
        val x = longitudeToMeters(point.longitude - lon, referenceLatRadians)
        val y = latitudeToMeters(point.latitude - lat)
        x * x + y * y
    } ?: 0
}

internal fun List<LatLng>.withoutAdjacentDuplicates(): List<LatLng> =
    fold(mutableListOf()) { result, point ->
        val previous = result.lastOrNull()
        if (previous == null || previous.latitude != point.latitude || previous.longitude != point.longitude) {
            result.add(point)
        }
        result
    }

private fun List<LatLng>.polylineMidpoint(): LatLng? {
    if (size < 2) {
        return firstOrNull()
    }

    val targetSegmentIndex = (size - 1) / 2
    val start = this[targetSegmentIndex]
    val end = this[targetSegmentIndex + 1]
    return LatLng(
        (start.latitude + end.latitude) / 2.0,
        (start.longitude + end.longitude) / 2.0
    )
}

private fun transitLineBadgeText(lineName: String?): String? {
    val value = lineName?.trim().orEmpty()
    if (value.isEmpty()) {
        return null
    }

    val digits = value.filter(Char::isDigit)
    return if (digits.isNotEmpty()) digits else value
}

private fun latitudeToMeters(latitudeDelta: Double): Double =
    latitudeDelta * 111_320.0

private fun longitudeToMeters(
    longitudeDelta: Double,
    referenceLatRadians: Double
): Double =
    longitudeDelta * 111_320.0 * cos(referenceLatRadians)

internal fun buildRoutePolylinePoints(
    routeResponse: RoutePlan?,
    startPoint: LatLng,
    routeItems: List<RouteStop>
): List<LatLng> {
    val routedGeometry = routeResponse
        ?.fullGeometry
        .orEmpty()
        .map { coordinate -> LatLng(coordinate.lat, coordinate.lon) }

    if (routedGeometry.size >= 2) {
        return routedGeometry
    }

    return buildList {
        add(startPoint)
        routeItems.forEach { item ->
            add(LatLng(item.lat, item.lon))
        }
        if (routeResponse?.returnToStart == true) {
            add(startPoint)
        }
    }
}
