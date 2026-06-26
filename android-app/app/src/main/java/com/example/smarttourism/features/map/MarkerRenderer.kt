@file:Suppress("DEPRECATION")

package com.example.smarttourism.features.map

import android.content.Context
import android.graphics.Color
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
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
    selectedPoiIcon: Icon,
    isSelectingRoutePois: Boolean,
    selectedRoutePoiIds: Set<Int>,
    textResources: MapTextResources
): Map<Long, Poi> {
    map.clear()
    val selectablePoiMarkers = mutableMapOf<Long, Poi>()

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

            map.addMarkers(
                routeItems.map { item ->
                    val isVisited = item.poiId in visitedPoiIds
                    val isSkipped = item.poiId in skippedPoiIds
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
                            if (isVisited || isSkipped) {
                                icon(visitedRouteStopIcon)
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
        }

        pois.isNotEmpty() -> {
            val poiMarkers = map.addMarkers(
                pois.map { poi ->
                    val isSelected = poi.id in selectedRoutePoiIds
                    MarkerOptions()
                        .position(LatLng(poi.lat, poi.lon))
                        .apply {
                            if (isSelected) {
                                icon(selectedPoiIcon)
                            }
                        }
                        .title(poi.name)
                        .snippet(
                            if (isSelected) {
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
            if (isSelectingRoutePois) {
                poiMarkers.forEachIndexed { index, marker ->
                    selectablePoiMarkers[marker.id] = pois[index]
                }
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

private fun String.toDisplayLabel(labels: Map<String, String>): String =
    labels[this] ?: split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

private fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)
