@file:Suppress("DEPRECATION")

package com.example.smarttourism.features.map

import android.content.Context
import android.graphics.Color
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import java.util.Locale

internal fun renderMapContent(
    context: Context,
    map: MapLibreMap,
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    startLat: Double,
    startLon: Double,
    startPointIcon: Icon,
    currentLocation: RoutePoint?,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>,
    isRouteActive: Boolean,
    currentLocationIcon: Icon,
    visitedRouteStopIcon: Icon,
    skippedRouteStopIcon: Icon,
    normalPoiIcon: Icon,
    compactPoiIcon: Icon,
    focusedPoiIcon: Icon,
    selectedPoiIcon: Icon,
    selectedRoutePoiIds: Set<Int>,
    focusedPoiId: Int?,
    highlightedPoiId: Int?,
    textResources: MapTextResources
): Map<Long, Poi> {
    map.clear()
    val selectablePoiMarkers = mutableMapOf<Long, Poi>()
    val catalogPoisById = pois.associateBy { poi -> poi.id }
    val shouldUseCompactPoiMarkers = routeResponse == null && pois.size > CompactPoiMarkerThreshold

    val startPoint = LatLng(startLat, startLon)
    map.addMarker(
        MarkerOptions()
            .position(startPoint)
            .icon(startPointIcon)
            .title(textResources.startPointTitle)
            .snippet("${formatCoordinate(startLat)}, ${formatCoordinate(startLon)}")
    )

    val routeItems = routeResponse?.route.orEmpty()
    when {
        routeItems.isNotEmpty() -> {
            val renderedPaths = buildRenderedRoutePaths(
                routeResponse = routeResponse,
                routeItems = routeItems,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = skippedPoiIds,
                isRouteActive = isRouteActive
            )

            if (renderedPaths.isNotEmpty()) {
                drawRoutePaths(map, renderedPaths, isRouteActive)
                drawTransitLineLabels(
                    context = context,
                    map = map,
                    labels = buildTransitLineLabels(
                        routeResponse = routeResponse,
                        routeItems = routeItems,
                        visitedPoiIds = visitedPoiIds,
                        skippedPoiIds = skippedPoiIds,
                        isRouteActive = isRouteActive
                    ),
                    isRouteActive = isRouteActive
                )
            } else {
                val polylinePoints = buildRoutePolylinePoints(routeResponse, startPoint, routeItems)
                val visibleRouteSegments = buildVisibleRouteSegments(
                    polylinePoints = polylinePoints,
                    routeItems = routeItems,
                    currentLocation = currentLocation,
                    visitedPoiIds = visitedPoiIds,
                    skippedPoiIds = skippedPoiIds,
                    shouldHighlightActiveSegment = isRouteActive
                )

                if (isRouteActive && visibleRouteSegments.remainingSegment.size >= 2) {
                    map.addPolyline(
                        PolylineOptions()
                            .addAll(visibleRouteSegments.remainingSegment)
                            .color(Color.parseColor(RouteSecondaryLineColor))
                            .width(6f)
                            .alpha(0.75f)
                    )
                }

                if (visibleRouteSegments.activeSegment.size >= 2) {
                    map.addPolyline(
                        PolylineOptions()
                            .addAll(visibleRouteSegments.activeSegment)
                            .color(Color.parseColor(RoutePrimaryLineColor))
                            .width(if (isRouteActive) 7f else 6f)
                            .alpha(0.95f)
                    )
                }
            }

            val routeMarkerPois = routeItems.map { item -> item.toPoi(catalogPoisById[item.poiId]) }
            val routeMarkers = map.addMarkers(
                routeItems.map { item ->
                    val isVisited = item.poiId in visitedPoiIds
                    val isSkipped = item.poiId in skippedPoiIds
                    val isMustSee = item.poiId in selectedRoutePoiIds
                    val isFocused = item.poiId == focusedPoiId || item.poiId == highlightedPoiId
                    val routeStopTitle = String.format(
                        Locale.getDefault(),
                        textResources.routeStopTitleFormat,
                        item.order,
                        item.name
                    )
                    val markerTitle = if (isVisited || isSkipped) {
                        String.format(
                            Locale.getDefault(),
                            textResources.visitedRouteStopTitleFormat,
                            routeStopTitle
                        )
                    } else {
                        routeStopTitle
                    }

                    MarkerOptions()
                        .position(LatLng(item.lat, item.lon))
                        .apply {
                            when {
                                isFocused -> icon(focusedPoiIcon)
                                isVisited -> icon(visitedRouteStopIcon)
                                isSkipped -> icon(skippedRouteStopIcon)
                                isMustSee -> icon(selectedPoiIcon)
                                else -> icon(normalPoiIcon)
                            }
                        }
                        .title(markerTitle)
                        .snippet(
                            String.format(
                                Locale.getDefault(),
                                textResources.routeStopSnippetFormat,
                                item.category.toDisplayLabel(textResources.categoryLabels),
                                item.travelMinutesFromPrevious,
                                item.visitDurationMin
                            )
                        )
                }
            )
            routeMarkers.forEachIndexed { index, marker ->
                selectablePoiMarkers[marker.id] = routeMarkerPois[index]
            }
        }

        pois.isNotEmpty() -> {
            val poiMarkers = map.addMarkers(
                pois.map { poi ->
                    val isMustSee = poi.id in selectedRoutePoiIds
                    val isFocused = poi.id == focusedPoiId || poi.id == highlightedPoiId
                    MarkerOptions()
                        .position(LatLng(poi.lat, poi.lon))
                        .apply {
                            when {
                                isFocused -> icon(focusedPoiIcon)
                                isMustSee -> icon(selectedPoiIcon)
                                shouldUseCompactPoiMarkers -> icon(compactPoiIcon)
                                else -> icon(normalPoiIcon)
                            }
                        }
                        .title(poi.name)
                        .snippet(
                            if (isMustSee) {
                                String.format(
                                    Locale.getDefault(),
                                    textResources.selectedPoiSnippetFormat,
                                    poi.category.toDisplayLabel(textResources.categoryLabels)
                                )
                            } else {
                                poi.category.toDisplayLabel(textResources.categoryLabels)
                            }
                        )
                }
            )
            poiMarkers.forEachIndexed { index, marker ->
                selectablePoiMarkers[marker.id] = pois[index]
            }
        }
    }

    if (currentLocation != null) {
        map.addMarker(
            MarkerOptions()
                .position(LatLng(currentLocation.lat, currentLocation.lon))
                .icon(currentLocationIcon)
                .title(textResources.currentLocationTitle)
                .snippet("${formatCoordinate(currentLocation.lat)}, ${formatCoordinate(currentLocation.lon)}")
        )
    }

    return selectablePoiMarkers
}

private const val CompactPoiMarkerThreshold = 90

private fun RouteStop.toPoi(catalogPoi: Poi?): Poi =
    Poi(
        id = poiId,
        name = name,
        category = category,
        lat = lat,
        lon = lon,
        visitDurationMin = visitDurationMin.takeIf { minutes -> minutes > 0 } ?: catalogPoi?.visitDurationMin,
        baseScore = baseScore ?: catalogPoi?.baseScore,
        shortDescription = shortDescription.ifNotBlank() ?: catalogPoi?.shortDescription.ifNotBlank(),
        wikipediaUrl = wikipediaUrl.ifNotBlank() ?: catalogPoi?.wikipediaUrl.ifNotBlank(),
        openingHoursRaw = openingHoursRaw.ifNotBlank() ?: catalogPoi?.openingHoursRaw.ifNotBlank()
    )

private fun String?.ifNotBlank(): String? =
    this?.takeIf { value -> value.isNotBlank() }

private fun String.toDisplayLabel(labels: Map<String, String>): String =
    labels[this] ?: split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

private fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)
