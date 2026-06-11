package com.example.smarttourism.features.map

import org.maplibre.android.geometry.LatLng

internal const val DefaultZoom = 13.0
internal const val RoutePrimaryLineColor = "#2563EB"
internal const val RouteSecondaryLineColor = "#94A3B8"
internal const val TransitCompletedLineColor = "#CBD5E1"
internal val TransitLineColors = listOf(
    "#EA580C",
    "#0284C7",
    "#7C3AED",
    "#059669",
    "#DC2626",
    "#C026D3",
    "#0D9488",
    "#4F46E5",
    "#B45309"
)
internal const val RouteTrimMaxSnapDistanceMeters = 200.0
internal const val CurrentLocationIconWidthDp = 42
internal const val CurrentLocationIconHeightDp = 48
internal const val CurrentLocationOuterColor = "#FFFFFF"
internal const val CurrentLocationFillColor = "#2563EB"
internal const val CurrentLocationCenterColor = "#DBEAFE"
internal const val VisitedStopIconWidthDp = 38
internal const val VisitedStopIconHeightDp = 44
internal const val VisitedStopOuterColor = "#FFFFFF"
internal const val VisitedStopFillColor = "#16A34A"
internal const val VisitedStopCheckColor = "#FFFFFF"
internal const val SelectedPoiIconWidthDp = 38
internal const val SelectedPoiIconHeightDp = 44
internal const val SelectedPoiOuterColor = "#FFFFFF"
internal const val SelectedPoiFillColor = "#0F766E"
internal const val SelectedPoiCheckColor = "#FFFFFF"
internal const val StartPointIconWidthDp = 40
internal const val StartPointIconHeightDp = 46
internal const val StartPointOuterColor = "#FFFFFF"
internal const val StartPointFillColor = "#F97316"
internal const val StartPointTextColor = "#FFFFFF"

internal data class RouteProjection(
    val point: LatLng,
    val segmentIndex: Int,
    val distanceMeters: Double
)

internal data class VisibleRouteSegments(
    val activeSegment: List<LatLng>,
    val remainingSegment: List<LatLng>
)

internal enum class RoutePathStage {
    COMPLETED,
    ACTIVE,
    UPCOMING
}

internal data class RenderedRoutePath(
    val points: List<LatLng>,
    val mode: String,
    val colorKey: String?,
    val stage: RoutePathStage
)

internal data class RenderedRouteLabel(
    val point: LatLng,
    val text: String,
    val colorKey: String?,
    val stage: RoutePathStage
)

internal data class AutoCameraTarget(
    val key: String,
    val lat: Double,
    val lon: Double
)

internal data class MapTextResources(
    val startPointTitle: String,
    val startPointMarkerLabel: String,
    val currentLocationTitle: String,
    val routeStopTitleFormat: String,
    val visitedRouteStopTitleFormat: String,
    val routeStopSnippetFormat: String,
    val selectedPoiSnippetFormat: String,
    val categoryLabels: Map<String, String>
)
