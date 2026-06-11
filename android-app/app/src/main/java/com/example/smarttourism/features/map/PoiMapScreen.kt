package com.example.smarttourism.features.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import org.maplibre.android.maps.MapLibreMap

@Composable
fun PoiMapScreen(
    pois: List<Poi>,
    routeResponse: RoutePlan?,
    startLat: Double,
    startLon: Double,
    defaultZoom: Double? = null,
    currentLocation: RoutePoint?,
    visitedPoiIds: Set<Int>,
    skippedPoiIds: Set<Int>,
    isRouteActive: Boolean,
    isLoading: Boolean,
    isFullScreen: Boolean = false,
    isSelectingStart: Boolean,
    isSelectingRoutePois: Boolean = false,
    selectedRoutePoiIds: Set<Int> = emptySet(),
    preferCurrentLocationCamera: Boolean = false,
    locationButtonBottomPadding: Dp? = null,
    showLocationButton: Boolean = true,
    recenterLocationRequestKey: Int = 0,
    currentLocationCameraYOffset: Dp = 0.dp,
    onStartPointSelected: (Double, Double) -> Unit,
    onRoutePoiSelected: (Poi) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val currentLocationCameraYOffsetPx = with(density) { currentLocationCameraYOffset.toPx() }
    val mapView = rememberMapViewWithLifecycle(context)
    var map by remember(mapView) { mutableStateOf<MapLibreMap?>(null) }
    var isStyleLoaded by remember(mapView) { mutableStateOf(false) }
    var lastAutoCameraKey by remember(mapView) { mutableStateOf<String?>(null) }
    val textResources = mapTextResources()
    val startPointIcon = remember(context, textResources.startPointMarkerLabel) {
        createStartPointIcon(context, textResources.startPointMarkerLabel)
    }
    val currentLocationIcon = remember(context) { createCurrentLocationIcon(context) }
    val visitedRouteStopIcon = remember(context) { createVisitedRouteStopIcon(context) }
    val selectedPoiIcon = remember(context) { createSelectedPoiIcon(context) }
    var selectablePoiMarkers by remember(mapView) { mutableStateOf<Map<Long, Poi>>(emptyMap()) }

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

        DisposableEffect(map, isSelectingStart, isSelectingRoutePois, selectablePoiMarkers, onStartPointSelected, onRoutePoiSelected) {
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

                val markerClickListener = MapLibreMap.OnMarkerClickListener { marker ->
                    if (!isSelectingRoutePois) {
                        return@OnMarkerClickListener false
                    }
                    val poi = selectablePoiMarkers[marker.id] ?: return@OnMarkerClickListener false
                    onRoutePoiSelected(poi)
                    true
                }

                mapInstance.addOnMapClickListener(clickListener)
                mapInstance.setOnMarkerClickListener(markerClickListener)
                onDispose {
                    mapInstance.removeOnMapClickListener(clickListener)
                    mapInstance.setOnMarkerClickListener(null)
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
            skippedPoiIds,
            isRouteActive,
            isSelectingRoutePois,
            selectedRoutePoiIds,
            textResources
        ) {
            val mapInstance = map ?: return@LaunchedEffect
            if (!isStyleLoaded) return@LaunchedEffect

            selectablePoiMarkers = renderMapContent(
                context = context,
                map = mapInstance,
                pois = pois,
                routeResponse = if (isSelectingRoutePois) null else routeResponse,
                startLat = startLat,
                startLon = startLon,
                startPointIcon = startPointIcon,
                currentLocation = currentLocation,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = skippedPoiIds,
                isRouteActive = isRouteActive,
                currentLocationIcon = currentLocationIcon,
                visitedRouteStopIcon = visitedRouteStopIcon,
                selectedPoiIcon = selectedPoiIcon,
                isSelectingRoutePois = isSelectingRoutePois,
                selectedRoutePoiIds = selectedRoutePoiIds,
                textResources = textResources
            )
        }

        LaunchedEffect(
            map,
            isStyleLoaded,
            routeResponse,
            startLat,
            startLon,
            defaultZoom,
            pois,
            isSelectingStart,
            isSelectingRoutePois,
            visitedPoiIds,
            skippedPoiIds,
            isRouteActive,
            preferCurrentLocationCamera,
            currentLocationCameraYOffsetPx
        ) {
            val mapInstance = map ?: return@LaunchedEffect
            if (!isStyleLoaded) return@LaunchedEffect

            val cameraTarget = buildAutoCameraTarget(
                routeResponse = routeResponse,
                startLat = startLat,
                startLon = startLon,
                defaultZoom = defaultZoom,
                pois = pois,
                isSelectingStart = isSelectingStart,
                isSelectingRoutePois = isSelectingRoutePois,
                visitedPoiIds = visitedPoiIds,
                skippedPoiIds = skippedPoiIds,
                isRouteActive = isRouteActive,
                preferCurrentLocationCamera = preferCurrentLocationCamera,
                currentLocation = currentLocation,
                currentLocationCameraYOffsetPx = currentLocationCameraYOffsetPx
            )

            if (cameraTarget.key != lastAutoCameraKey) {
                val cameraYOffsetPx = if (cameraTarget.key.startsWith("current-location:")) {
                    currentLocationCameraYOffsetPx
                } else {
                    0f
                }
                moveCamera(
                    map = mapInstance,
                    lat = cameraTarget.lat,
                    lon = cameraTarget.lon,
                    zoom = defaultZoom,
                    verticalOffsetPx = cameraYOffsetPx
                )
                lastAutoCameraKey = cameraTarget.key
            }
        }

        LaunchedEffect(map, isStyleLoaded, recenterLocationRequestKey) {
            val mapInstance = map ?: return@LaunchedEffect
            if (!isStyleLoaded || recenterLocationRequestKey <= 0) return@LaunchedEffect

            val location = currentLocation ?: return@LaunchedEffect
            moveCamera(
                map = mapInstance,
                lat = location.lat,
                lon = location.lon,
                zoom = defaultZoom,
                verticalOffsetPx = currentLocationCameraYOffsetPx
            )
        }

        if (isLoading && pois.isEmpty() && routeResponse == null) {
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        }

        if (isSelectingStart) {
            MapSelectionHint(
                text = stringResource(R.string.map_start_selection_hint),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        if (isSelectingRoutePois) {
            MapSelectionHint(
                text = stringResource(R.string.map_poi_selection_hint, selectedRoutePoiIds.size),
                modifier = Modifier.align(Alignment.TopCenter)
            )
        }

        if (showLocationButton) {
            MapLocationButton(
                enabled = currentLocation != null,
                onClick = {
                    val mapInstance = map ?: return@MapLocationButton
                    val location = currentLocation ?: return@MapLocationButton
                    moveCamera(
                        map = mapInstance,
                        lat = location.lat,
                        lon = location.lon,
                        zoom = defaultZoom,
                        verticalOffsetPx = currentLocationCameraYOffsetPx
                    )
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.End)
                    )
                    .padding(
                        end = if (isFullScreen) 24.dp else 20.dp,
                        bottom = locationButtonBottomPadding ?: if (isFullScreen) 104.dp else 20.dp
                    )
            )
        }
    }
}
