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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.smarttourism.data.PoiDto
import com.example.smarttourism.data.RouteResponse
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.annotations.PolylineOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView

private const val DefaultZoom = 13.0
private const val StreetStyleUrl = "https://tiles.openfreemap.org/styles/liberty"
private const val RouteLineColor = "#0F766E"

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

        LaunchedEffect(map, isStyleLoaded, pois, routeResponse, startLat, startLon) {
            val mapInstance = map ?: return@LaunchedEffect
            if (!isStyleLoaded) return@LaunchedEffect

            renderMapContent(
                map = mapInstance,
                pois = pois,
                routeResponse = routeResponse,
                startLat = startLat,
                startLon = startLon
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
                    text = "Tap the map to choose the route start point",
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

private fun renderMapContent(
    map: MapLibreMap,
    pois: List<PoiDto>,
    routeResponse: RouteResponse?,
    startLat: Double,
    startLon: Double
) {
    map.clear()

    val startPoint = LatLng(startLat, startLon)
    map.addMarker(
        MarkerOptions()
            .position(startPoint)
            .title("Start point")
            .snippet("${formatCoordinate(startLat)}, ${formatCoordinate(startLon)}")
    )

    val routeItems = routeResponse?.route.orEmpty()
    when {
        routeItems.isNotEmpty() -> {
            val polylinePoints = buildList {
                add(startPoint)
                routeItems.forEach { item ->
                    add(LatLng(item.lat, item.lon))
                }
                if (routeResponse?.return_to_start == true) {
                    add(startPoint)
                }
            }

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
                        .title("${item.order}. ${item.name}")
                        .snippet(
                            "${item.category} • walk ${item.travel_minutes_from_previous} min • visit ${item.visit_duration_min} min"
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
                        .snippet(poi.category)
                }
            )

            moveCamera(map, startLat, startLon)
        }

        else -> {
            moveCamera(map, startLat, startLon)
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

private fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)
