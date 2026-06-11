package com.example.smarttourism.features.map

import android.graphics.PointF
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap

internal fun buildAutoCameraTarget(
    routeResponse: RoutePlan?,
    startLat: Double,
    startLon: Double,
    defaultZoom: Double?,
    pois: List<Poi>,
    isSelectingStart: Boolean,
    isSelectingRoutePois: Boolean,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>,
    isRouteActive: Boolean,
    preferCurrentLocationCamera: Boolean,
    currentLocation: RoutePoint?,
    currentLocationCameraYOffsetPx: Float
): AutoCameraTarget =
    when {
        isSelectingStart || isSelectingRoutePois -> AutoCameraTarget(
            key = "select:$startLat:$startLon:${defaultZoom ?: DefaultZoom}",
            lat = startLat,
            lon = startLon,
        )

        preferCurrentLocationCamera && currentLocation != null -> AutoCameraTarget(
            key = buildString {
                append("current-location:")
                append(routeResponse?.startDateTime.orEmpty())
                append(':')
                append(isRouteActive)
                append(':')
                append(visitedPoiIds.sorted().joinToString(","))
                append(':')
                append(skippedPoiIds.sorted().joinToString(","))
                append(":offset:")
                append(currentLocationCameraYOffsetPx)
            },
            lat = currentLocation.lat,
            lon = currentLocation.lon
        )

        routeResponse?.route.orEmpty().isNotEmpty() -> {
            val activeRoute = routeResponse!!
            val firstStop = if (isRouteActive) {
                activeRoute.route.firstOrNull { stop ->
                    stop.poiId !in visitedPoiIds && stop.poiId !in skippedPoiIds
                } ?: activeRoute.route.first()
            } else {
                activeRoute.route.first()
            }
            AutoCameraTarget(
                key = buildString {
                    append("route:")
                    append(activeRoute.start.lat)
                    append(':')
                    append(activeRoute.start.lon)
                    append(':')
                    append(activeRoute.startDateTime.orEmpty())
                    append(':')
                    append(activeRoute.poiCount)
                    append(':')
                    append(activeRoute.usedMinutes)
                    append(':')
                    append(isRouteActive)
                    append(':')
                    append(visitedPoiIds.sorted().joinToString(","))
                    append(':')
                    append(skippedPoiIds.sorted().joinToString(","))
                    append(':')
                    append(firstStop.poiId)
                },
                lat = firstStop.lat,
                lon = firstStop.lon,
            )
        }

        else -> AutoCameraTarget(
            key = "base:$startLat:$startLon:${defaultZoom ?: DefaultZoom}:${pois.size}",
            lat = startLat,
            lon = startLon,
        )
    }

internal fun moveCamera(
    map: MapLibreMap,
    lat: Double,
    lon: Double,
    zoom: Double? = null,
    verticalOffsetPx: Float = 0f
) {
    val target = LatLng(lat, lon)
    map.moveCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(target)
                .zoom(zoom ?: DefaultZoom)
                .build()
        )
    )

    if (verticalOffsetPx <= 0f) return

    val targetScreenPoint = map.projection.toScreenLocation(target)
    val shiftedCameraTarget = map.projection.fromScreenLocation(
        PointF(targetScreenPoint.x, targetScreenPoint.y + verticalOffsetPx)
    )
    map.moveCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(shiftedCameraTarget)
                .zoom(zoom ?: DefaultZoom)
                .build()
        )
    )
}
