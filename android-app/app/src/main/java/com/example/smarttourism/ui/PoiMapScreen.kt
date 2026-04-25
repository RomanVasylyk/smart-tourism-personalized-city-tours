package com.example.smarttourism.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smarttourism.R
import com.example.smarttourism.data.PoiDto
import com.example.smarttourism.data.RouteItemDto
import com.example.smarttourism.data.RouteResponse
import com.example.smarttourism.data.RouteStartDto
import org.maplibre.android.annotations.Icon
import org.maplibre.android.annotations.IconFactory
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import java.util.Locale
import kotlin.math.cos
import kotlin.math.roundToInt

private const val DefaultZoom = 13.0
private const val StreetStyleUrl = "https://tiles.openfreemap.org/styles/liberty"
private const val RoutePrimaryLineColor = "#2563EB"
private const val RouteSecondaryLineColor = "#94A3B8"
private const val RouteTrimMaxSnapDistanceMeters = 200.0
private const val CurrentLocationIconWidthDp = 42
private const val CurrentLocationIconHeightDp = 48
private const val CurrentLocationOuterColor = "#FFFFFF"
private const val CurrentLocationFillColor = "#2563EB"
private const val CurrentLocationCenterColor = "#DBEAFE"
private const val VisitedStopIconWidthDp = 38
private const val VisitedStopIconHeightDp = 44
private const val VisitedStopOuterColor = "#FFFFFF"
private const val VisitedStopFillColor = "#16A34A"
private const val VisitedStopCheckColor = "#FFFFFF"
private const val StartPointIconWidthDp = 60
private const val StartPointIconHeightDp = 66
private const val StartPointOuterColor = "#FFFFFF"
private const val StartPointFillColor = "#F97316"
private const val StartPointTextColor = "#FFFFFF"

private data class RouteProjection(
    val point: LatLng,
    val segmentIndex: Int,
    val distanceMeters: Double
)

private data class VisibleRouteSegments(
    val activeSegment: List<LatLng>,
    val remainingSegment: List<LatLng>
)

private data class MapTextResources(
    val startPointTitle: String,
    val startPointMarkerLabel: String,
    val currentLocationTitle: String,
    val routeStopTitleFormat: String,
    val visitedRouteStopTitleFormat: String,
    val routeStopSnippetFormat: String,
    val categoryLabels: Map<String, String>
)

@Composable
fun PoiMapScreen(
    pois: List<PoiDto>,
    routeResponse: RouteResponse?,
    startLat: Double,
    startLon: Double,
    defaultZoom: Double? = null,
    currentLocation: RouteStartDto?,
    visitedPoiIds: Set<Int>,
    isRouteActive: Boolean,
    isLoading: Boolean,
    isFullScreen: Boolean = false,
    isSelectingStart: Boolean,
    onStartPointSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle(context)
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember(mapView) { mutableStateOf(false) }
    val textResources = mapTextResources()
    val startPointIcon = remember(context, textResources.startPointMarkerLabel) {
        createStartPointIcon(context, textResources.startPointMarkerLabel)
    }
    val currentLocationIcon = remember(context) { createCurrentLocationIcon(context) }
    val visitedRouteStopIcon = remember(context) { createVisitedRouteStopIcon(context) }

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { mapInstance ->
                        map = mapInstance
                        mapInstance.setStyle(StreetStyleUrl) {
                            isStyleLoaded = true
                            moveCamera(mapInstance, startLat, startLon, defaultZoom)
                        }
                    }
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        DisposableEffect(map, isSelectingStart, onStartPointSelected) {
            val mapInstance = map
            if (mapInstance == null) {
                onDispose { }
            } else {
                val clickListener = MapLibreMap.OnMapClickListener { point ->
                    if (!isSelectingStart) {
                        return@OnMapClickListener false
                    }

                    onStartPointSelected(point.latitude, point.longitude)
                    true
                }

                mapInstance.addOnMapClickListener(clickListener)
                onDispose {
                    mapInstance.removeOnMapClickListener(clickListener)
                }
            }
        }

        LaunchedEffect(
            map,
            isStyleLoaded,
            pois,
            routeResponse,
            startLat,
            startLon,
            defaultZoom,
            currentLocation,
            visitedPoiIds,
            isRouteActive,
            textResources
        ) {
            val mapInstance = map ?: return@LaunchedEffect
            if (!isStyleLoaded) return@LaunchedEffect

            renderMapContent(
                map = mapInstance,
                pois = pois,
                routeResponse = routeResponse,
                startLat = startLat,
                startLon = startLon,
                defaultZoom = defaultZoom,
                startPointIcon = startPointIcon,
                currentLocation = currentLocation,
                visitedPoiIds = visitedPoiIds,
                isRouteActive = isRouteActive,
                currentLocationIcon = currentLocationIcon,
                visitedRouteStopIcon = visitedRouteStopIcon,
                textResources = textResources
            )
        }

        if (isLoading && pois.isEmpty() && routeResponse == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (isSelectingStart) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(12.dp)
            ) {
                Text(
                    text = stringResource(R.string.map_start_selection_hint),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                )
            }
        }

        MapLocationButton(
            enabled = currentLocation != null,
            onClick = {
                val mapInstance = map ?: return@MapLocationButton
                val location = currentLocation ?: return@MapLocationButton
                moveCamera(mapInstance, location.lat, location.lon, defaultZoom)
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.End)
                )
                .padding(
                    end = if (isFullScreen) 24.dp else 20.dp,
                    bottom = if (isFullScreen) 104.dp else 20.dp
                )
        )
    }
}

@Composable
private fun rememberMapViewWithLifecycle(context: Context): MapView {
    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)
            .logoEnabled(false)
            .attributionEnabled(true)

        MapView(context, options).apply {
            onCreate(null)
        }
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, mapView) {
        val observer = object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) = mapView.onStart()
            override fun onResume(owner: LifecycleOwner) = mapView.onResume()
            override fun onPause(owner: LifecycleOwner) = mapView.onPause()
            override fun onStop(owner: LifecycleOwner) = mapView.onStop()
            override fun onDestroy(owner: LifecycleOwner) = mapView.onDestroy()
        }

        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
        }
    }

    return mapView
}

@Composable
private fun mapTextResources(): MapTextResources =
    MapTextResources(
        startPointTitle = stringResource(R.string.start_point_title),
        startPointMarkerLabel = stringResource(R.string.map_start_marker_label),
        currentLocationTitle = stringResource(R.string.map_current_location_title),
        routeStopTitleFormat = stringResource(R.string.route_stop_title),
        visitedRouteStopTitleFormat = stringResource(R.string.map_visited_route_stop_title),
        routeStopSnippetFormat = stringResource(R.string.map_route_stop_snippet),
        categoryLabels = mapOf(
            "attraction" to stringResource(R.string.category_attraction),
            "museum" to stringResource(R.string.category_museum),
            "gallery" to stringResource(R.string.category_gallery),
            "viewpoint" to stringResource(R.string.category_viewpoint),
            "monument" to stringResource(R.string.category_monument),
            "historical_site" to stringResource(R.string.category_historical_site),
            "park" to stringResource(R.string.category_park),
            "religious_site" to stringResource(R.string.category_religious_site)
        )
    )

private fun renderMapContent(
    map: MapLibreMap,
    pois: List<PoiDto>,
    routeResponse: RouteResponse?,
    startLat: Double,
    startLon: Double,
    defaultZoom: Double?,
    startPointIcon: Icon,
    currentLocation: RouteStartDto?,
    visitedPoiIds: Set<Int>,
    isRouteActive: Boolean,
    currentLocationIcon: Icon,
    visitedRouteStopIcon: Icon,
    textResources: MapTextResources
) {
    map.clear()

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
            val polylinePoints = buildRoutePolylinePoints(routeResponse, startPoint, routeItems)
            val visibleRouteSegments = buildVisibleRouteSegments(
                polylinePoints = polylinePoints,
                routeItems = routeItems,
                currentLocation = currentLocation,
                visitedPoiIds = visitedPoiIds,
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

            map.addMarkers(
                routeItems.map { item ->
                    val isVisited = item.poi_id in visitedPoiIds
                    val routeStopTitle = String.format(
                        Locale.getDefault(),
                        textResources.routeStopTitleFormat,
                        item.order,
                        item.name
                    )
                    val markerTitle = if (isVisited) {
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
                            if (isVisited) {
                                icon(visitedRouteStopIcon)
                            }
                        }
                        .title(markerTitle)
                        .snippet(
                            String.format(
                                Locale.getDefault(),
                                textResources.routeStopSnippetFormat,
                                item.category.toDisplayLabel(textResources.categoryLabels),
                                item.travel_minutes_from_previous,
                                item.visit_duration_min
                            )
                        )
                }
            )

            if (currentLocation != null) {
                moveCamera(map, currentLocation.lat, currentLocation.lon, defaultZoom)
            } else {
                val firstStop = routeItems.first()
                moveCamera(map, firstStop.lat, firstStop.lon, defaultZoom)
            }
        }

        pois.isNotEmpty() -> {
            map.addMarkers(
                pois.map { poi ->
                    MarkerOptions()
                        .position(LatLng(poi.lat, poi.lon))
                        .title(poi.name)
                        .snippet(poi.category.toDisplayLabel(textResources.categoryLabels))
                }
            )

            moveCamera(map, startLat, startLon, defaultZoom)
        }

        else -> {
            moveCamera(map, startLat, startLon, defaultZoom)
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
}

@Composable
private fun MapLocationButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val strokeWidth = size.minDimension * 0.1f
            val outerRadius = size.minDimension * 0.38f
            val innerRadius = size.minDimension * 0.08f
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val crosshairGap = size.minDimension * 0.2f

            drawCircle(
                color = contentColor,
                radius = outerRadius,
                style = Stroke(width = strokeWidth)
            )
            drawCircle(
                color = contentColor,
                radius = innerRadius
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(centerX, 0f),
                end = androidx.compose.ui.geometry.Offset(centerX, centerY - crosshairGap),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(centerX, centerY + crosshairGap),
                end = androidx.compose.ui.geometry.Offset(centerX, size.height),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(0f, centerY),
                end = androidx.compose.ui.geometry.Offset(centerX - crosshairGap, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(centerX + crosshairGap, centerY),
                end = androidx.compose.ui.geometry.Offset(size.width, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

private fun createStartPointIcon(context: Context, label: String): Icon {
    val density = context.resources.displayMetrics.density
    val width = (StartPointIconWidthDp * density).roundToInt()
    val height = (StartPointIconHeightDp * density).roundToInt()
    val centerX = width / 2f
    val circleY = 20f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    paint.color = Color.parseColor(StartPointOuterColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 21f * density,
            tipY = height - 2f * density,
            shoulderY = 37f * density,
            shoulderHalfWidth = 10f * density
        ),
        paint
    )

    paint.color = Color.parseColor(StartPointFillColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 17f * density,
            tipY = height - 8f * density,
            shoulderY = 33f * density,
            shoulderHalfWidth = 6.8f * density
        ),
        paint
    )

    paint.apply {
        color = Color.parseColor(StartPointTextColor)
        style = Paint.Style.FILL
        textAlign = Paint.Align.CENTER
        textSize = 10f * density
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    }
    val baseline = circleY - ((paint.descent() + paint.ascent()) / 2f)
    canvas.drawText(label, centerX, baseline, paint)

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun createCurrentLocationIcon(context: Context): Icon {
    val density = context.resources.displayMetrics.density
    val width = (CurrentLocationIconWidthDp * density).roundToInt()
    val height = (CurrentLocationIconHeightDp * density).roundToInt()
    val centerX = width / 2f
    val circleY = 17f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    paint.color = Color.parseColor(CurrentLocationOuterColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 18f * density,
            tipY = height - 2f * density,
            shoulderY = 31f * density,
            shoulderHalfWidth = 9f * density
        ),
        paint
    )

    paint.color = Color.parseColor(CurrentLocationFillColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 13f * density,
            tipY = height - 7f * density,
            shoulderY = 28f * density,
            shoulderHalfWidth = 6.5f * density
        ),
        paint
    )

    paint.color = Color.parseColor(CurrentLocationCenterColor)
    canvas.drawCircle(centerX, circleY, 5f * density, paint)

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun createVisitedRouteStopIcon(context: Context): Icon {
    val density = context.resources.displayMetrics.density
    val width = (VisitedStopIconWidthDp * density).roundToInt()
    val height = (VisitedStopIconHeightDp * density).roundToInt()
    val centerX = width / 2f
    val circleY = 15f * density

    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    paint.color = Color.parseColor(VisitedStopOuterColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 16f * density,
            tipY = height - 2f * density,
            shoulderY = 28f * density,
            shoulderHalfWidth = 8f * density
        ),
        paint
    )

    paint.color = Color.parseColor(VisitedStopFillColor)
    canvas.drawPath(
        locationPinPath(
            centerX = centerX,
            circleY = circleY,
            circleRadius = 12f * density,
            tipY = height - 7f * density,
            shoulderY = 25f * density,
            shoulderHalfWidth = 5.8f * density
        ),
        paint
    )

    paint.apply {
        color = Color.parseColor(VisitedStopCheckColor)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 3f * density
    }
    canvas.drawPath(
        Path().apply {
            moveTo(centerX - 5f * density, circleY)
            lineTo(centerX - 1f * density, circleY + 4f * density)
            lineTo(centerX + 6f * density, circleY - 5f * density)
        },
        paint
    )

    return IconFactory.getInstance(context).fromBitmap(bitmap)
}

private fun locationPinPath(
    centerX: Float,
    circleY: Float,
    circleRadius: Float,
    tipY: Float,
    shoulderY: Float,
    shoulderHalfWidth: Float
): Path =
    Path().apply {
        addCircle(centerX, circleY, circleRadius, Path.Direction.CW)
        moveTo(centerX - shoulderHalfWidth, shoulderY)
        lineTo(centerX + shoulderHalfWidth, shoulderY)
        lineTo(centerX, tipY)
        close()
    }

private fun buildVisibleRoutePolylinePoints(
    polylinePoints: List<LatLng>,
    routeItems: List<RouteItemDto>,
    currentLocation: RouteStartDto?,
    visitedPoiIds: Set<Int>,
    shouldTrimPassedPath: Boolean
): List<LatLng> {
    if (!shouldTrimPassedPath || currentLocation == null || polylinePoints.size < 2) {
        return polylinePoints
    }

    val firstSearchSegmentIndex = firstRemainingSegmentIndex(
        polylinePoints = polylinePoints,
        routeItems = routeItems,
        visitedPoiIds = visitedPoiIds
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

private fun buildVisibleRouteSegments(
    polylinePoints: List<LatLng>,
    routeItems: List<RouteItemDto>,
    currentLocation: RouteStartDto?,
    visitedPoiIds: Set<Int>,
    shouldHighlightActiveSegment: Boolean
): VisibleRouteSegments {
    if (!shouldHighlightActiveSegment || currentLocation == null || polylinePoints.size < 2) {
        return VisibleRouteSegments(
            activeSegment = polylinePoints,
            remainingSegment = emptyList()
        )
    }

    val nextTarget = routeItems
        .filter { item -> item.poi_id !in visitedPoiIds }
        .minByOrNull { item -> item.order }
        ?: return VisibleRouteSegments(emptyList(), emptyList())

    val firstSearchSegmentIndex = firstRemainingSegmentIndex(
        polylinePoints = polylinePoints,
        routeItems = routeItems,
        visitedPoiIds = visitedPoiIds
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
    routeItems: List<RouteItemDto>,
    visitedPoiIds: Set<Int>
): Int {
    val lastVisitedItem = routeItems
        .filter { item -> item.poi_id in visitedPoiIds }
        .maxByOrNull { item -> item.order }
        ?: return 0

    val closestStopPointIndex = closestPolylinePointIndex(
        polylinePoints = polylinePoints,
        lat = lastVisitedItem.lat,
        lon = lastVisitedItem.lon
    )

    return closestStopPointIndex.coerceIn(0, polylinePoints.lastIndex - 1)
}

private fun closestProjectionOnRoute(
    polylinePoints: List<LatLng>,
    currentLocation: RouteStartDto,
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

private fun List<LatLng>.withoutAdjacentDuplicates(): List<LatLng> =
    fold(mutableListOf()) { result, point ->
        val previous = result.lastOrNull()
        if (previous == null || previous.latitude != point.latitude || previous.longitude != point.longitude) {
            result.add(point)
        }
        result
    }

private fun latitudeToMeters(latitudeDelta: Double): Double =
    latitudeDelta * 111_320.0

private fun longitudeToMeters(
    longitudeDelta: Double,
    referenceLatRadians: Double
): Double =
    longitudeDelta * 111_320.0 * cos(referenceLatRadians)

private fun buildRoutePolylinePoints(
    routeResponse: RouteResponse?,
    startPoint: LatLng,
    routeItems: List<RouteItemDto>
): List<LatLng> {
    val routedGeometry = routeResponse
        ?.full_geometry
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
        if (routeResponse?.return_to_start == true) {
            add(startPoint)
        }
    }
}

private fun moveCamera(
    map: MapLibreMap,
    lat: Double,
    lon: Double,
    zoom: Double? = null
) {
    map.moveCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(zoom ?: DefaultZoom)
                .build()
        )
    )
}

private fun String.toDisplayLabel(labels: Map<String, String>): String =
    labels[this] ?: split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

private fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)
