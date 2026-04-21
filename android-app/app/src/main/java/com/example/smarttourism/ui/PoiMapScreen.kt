package com.example.smarttourism.ui

import android.content.Context
import android.graphics.Color
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import java.util.Locale

private const val DefaultZoom = 13.0
private const val StreetStyleUrl = "https://tiles.openfreemap.org/styles/liberty"
private const val RouteLineColor = "#0F766E"

private data class MapTextResources(
    val startPointTitle: String,
    val routeStopTitleFormat: String,
    val routeStopSnippetFormat: String,
    val categoryLabels: Map<String, String>
)

@Composable
fun PoiMapScreen(
    pois: List<PoiDto>,
    routeResponse: RouteResponse?,
    startLat: Double,
    startLon: Double,
    isLoading: Boolean,
    isSelectingStart: Boolean,
    onStartPointSelected: (Double, Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val mapView = rememberMapViewWithLifecycle(context)
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember(mapView) { mutableStateOf(false) }
    val textResources = mapTextResources()

    Box(modifier = modifier) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { mapInstance ->
                        map = mapInstance
                        mapInstance.setStyle(StreetStyleUrl) {
                            isStyleLoaded = true
                            moveCamera(mapInstance, startLat, startLon)
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

        LaunchedEffect(map, isStyleLoaded, pois, routeResponse, startLat, startLon, textResources) {
            val mapInstance = map ?: return@LaunchedEffect
            if (!isStyleLoaded) return@LaunchedEffect

            renderMapContent(
                map = mapInstance,
                pois = pois,
                routeResponse = routeResponse,
                startLat = startLat,
                startLon = startLon,
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
    }
}

@Composable
private fun rememberMapViewWithLifecycle(context: Context): MapView {
    val mapView = remember {
        val options = MapLibreMapOptions.createFromAttributes(context)
            .textureMode(true)

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
        routeStopTitleFormat = stringResource(R.string.route_stop_title),
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
    textResources: MapTextResources
) {
    map.clear()

    val startPoint = LatLng(startLat, startLon)
    map.addMarker(
        MarkerOptions()
            .position(startPoint)
            .title(textResources.startPointTitle)
            .snippet("${formatCoordinate(startLat)}, ${formatCoordinate(startLon)}")
    )

    val routeItems = routeResponse?.route.orEmpty()
    when {
        routeItems.isNotEmpty() -> {
            val polylinePoints = buildRoutePolylinePoints(routeResponse, startPoint, routeItems)

            if (polylinePoints.size >= 2) {
                map.addPolyline(
                    PolylineOptions()
                        .addAll(polylinePoints)
                        .color(Color.parseColor(RouteLineColor))
                        .width(6f)
                        .alpha(0.9f)
                )
            }

            map.addMarkers(
                routeItems.map { item ->
                    MarkerOptions()
                        .position(LatLng(item.lat, item.lon))
                        .title(
                            String.format(
                                Locale.getDefault(),
                                textResources.routeStopTitleFormat,
                                item.order,
                                item.name
                            )
                        )
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

            val firstStop = routeItems.first()
            moveCamera(map, firstStop.lat, firstStop.lon)
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

            moveCamera(map, startLat, startLon)
        }

        else -> {
            moveCamera(map, startLat, startLon)
        }
    }
}

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
    lon: Double
) {
    map.moveCamera(
        CameraUpdateFactory.newCameraPosition(
            CameraPosition.Builder()
                .target(LatLng(lat, lon))
                .zoom(DefaultZoom)
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
