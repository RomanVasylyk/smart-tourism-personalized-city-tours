package com.example.smarttourism.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarttourism.R
import com.example.smarttourism.data.ApiModule
import com.example.smarttourism.data.PoiDto
import com.example.smarttourism.data.RouteRequest
import com.example.smarttourism.data.RouteResponse
import com.example.smarttourism.data.RouteStartDto
import com.example.smarttourism.data.RouteStorage
import com.example.smarttourism.data.RouteItemDto
import com.example.smarttourism.data.SavedRouteSnapshot
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

private const val DefaultCity = "nitra"
private val DefaultStartPoint = RouteStartDto(lat = 48.3076, lon = 18.0845)
private val AvailableMinutesOptions = listOf(120, 180, 240)
private val InterestOptions = listOf("museum", "historical_site", "viewpoint", "park")
private val PaceOptions = listOf("slow", "normal", "fast")
private val RouteTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.getDefault())

@Composable
fun RoutePlannerScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val locationPermissionDeniedMessage = stringResource(R.string.error_location_permission_denied)
    val poiPreviewFailedMessage = stringResource(R.string.error_poi_preview_failed)
    val routeGenerationFailedMessage = stringResource(R.string.error_route_generation_failed_default)

    var pois by remember { mutableStateOf<List<PoiDto>>(emptyList()) }
    var isPoiLoading by remember { mutableStateOf(true) }
    var poiError by remember { mutableStateOf<String?>(null) }

    var routeResponse by remember { mutableStateOf<RouteResponse?>(null) }
    var isRouteLoading by remember { mutableStateOf(false) }
    var routeError by remember { mutableStateOf<String?>(null) }

    var availableMinutes by remember { mutableIntStateOf(180) }
    var pace by remember { mutableStateOf("normal") }
    var returnToStart by remember { mutableStateOf(true) }
    var respectOpeningHours by remember { mutableStateOf(true) }
    var startPoint by remember { mutableStateOf(DefaultStartPoint) }
    var startDateTime by remember { mutableStateOf(defaultRouteStartDateTime()) }
    var isSelectingStart by remember { mutableStateOf(false) }
    var isLocating by remember { mutableStateOf(false) }
    var locationError by remember { mutableStateOf<String?>(null) }

    val selectedInterests = remember { mutableStateListOf(*InterestOptions.toTypedArray()) }

    val clearDisplayedRoute = {
        routeResponse = null
        routeError = null
    }

    val updateStartPoint = { lat: Double, lon: Double ->
        startPoint = RouteStartDto(lat = lat, lon = lon)
        isSelectingStart = false
        locationError = null
        clearDisplayedRoute()
    }

    fun requestCurrentDeviceLocation() {
        isLocating = true
        locationError = null

        fetchCurrentLocation(
            context = context,
            onSuccess = { lat, lon ->
                isLocating = false
                updateStartPoint(lat, lon)
            },
            onError = { message ->
                isLocating = false
                locationError = message
            }
        )
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            requestCurrentDeviceLocation()
        } else {
            isLocating = false
            locationError = locationPermissionDeniedMessage
        }
    }

    LaunchedEffect(Unit) {
        RouteStorage.load(context)?.let { snapshot ->
            applySavedSnapshot(
                snapshot = snapshot,
                onRouteRestored = { restoredRoute -> routeResponse = restoredRoute },
                onStartPointRestored = { restoredStart -> startPoint = restoredStart },
                onAvailableMinutesRestored = { restoredMinutes -> availableMinutes = restoredMinutes },
                onPaceRestored = { restoredPace -> pace = restoredPace },
                onReturnToStartRestored = { restoredReturnToStart -> returnToStart = restoredReturnToStart },
                onRespectOpeningHoursRestored = { restoredRespectOpeningHours ->
                    respectOpeningHours = restoredRespectOpeningHours
                },
                onStartDateTimeRestored = { restoredStartDateTime -> startDateTime = restoredStartDateTime },
                selectedInterests = selectedInterests
            )
        }

        try {
            pois = ApiModule.poiApi.getPois(DefaultCity)
            poiError = null
        } catch (e: Exception) {
            poiError = e.toUserMessage(poiPreviewFailedMessage)
        } finally {
            isPoiLoading = false
        }
    }

    val routeItems = routeResponse?.route.orEmpty()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.screen_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.screen_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            PoiMapScreen(
                pois = pois,
                routeResponse = routeResponse,
                startLat = startPoint.lat,
                startLon = startPoint.lon,
                isLoading = isPoiLoading,
                isSelectingStart = isSelectingStart,
                onStartPointSelected = updateStartPoint,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp)
                    .clip(MaterialTheme.shapes.extraLarge)
            )
        }

        item {
            StartPointCard(
                startPoint = startPoint,
                isSelectingStart = isSelectingStart,
                isLocating = isLocating,
                onToggleMapSelection = {
                    isSelectingStart = !isSelectingStart
                    locationError = null
                    clearDisplayedRoute()
                },
                onUseCurrentLocation = {
                    isSelectingStart = false
                    clearDisplayedRoute()

                    val hasFineLocationPermission = ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ) == PackageManager.PERMISSION_GRANTED

                    if (hasFineLocationPermission) {
                        requestCurrentDeviceLocation()
                    } else {
                        locationPermissionLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                    }
                }
            )
        }

        item {
            RouteParametersCard(
                availableMinutes = availableMinutes,
                onAvailableMinutesChange = {
                    availableMinutes = it
                    clearDisplayedRoute()
                },
                selectedInterests = selectedInterests,
                onInterestToggle = { interest, checked ->
                    if (checked) {
                        if (interest !in selectedInterests) {
                            selectedInterests.add(interest)
                        }
                    } else {
                        selectedInterests.remove(interest)
                    }
                    clearDisplayedRoute()
                },
                pace = pace,
                onPaceChange = {
                    pace = it
                    clearDisplayedRoute()
                },
                returnToStart = returnToStart,
                onReturnToStartChange = {
                    returnToStart = it
                    clearDisplayedRoute()
                },
                respectOpeningHours = respectOpeningHours,
                onRespectOpeningHoursChange = {
                    respectOpeningHours = it
                    clearDisplayedRoute()
                },
                startDateTime = startDateTime,
                onUseCurrentTime = {
                    startDateTime = defaultRouteStartDateTime()
                    clearDisplayedRoute()
                },
                isGenerating = isRouteLoading,
                onGenerateRoute = {
                    scope.launch {
                        isRouteLoading = true
                        routeError = null

                        val request = RouteRequest(
                            city = DefaultCity,
                            start_lat = startPoint.lat,
                            start_lon = startPoint.lon,
                            available_minutes = availableMinutes,
                            interests = selectedInterests.toList(),
                            pace = pace,
                            return_to_start = returnToStart,
                            start_datetime = startDateTime.truncatedTo(ChronoUnit.MINUTES).toString(),
                            respect_opening_hours = respectOpeningHours
                        )

                        try {
                            val generatedRoute = ApiModule.poiApi.generateRoute(request)
                            routeResponse = generatedRoute
                            RouteStorage.save(
                                context = context,
                                snapshot = SavedRouteSnapshot(
                                    request = request,
                                    response = generatedRoute
                                )
                            )
                        } catch (e: Exception) {
                            routeError = e.toUserMessage(routeGenerationFailedMessage)
                        } finally {
                            isRouteLoading = false
                        }
                    }
                }
            )
        }

        if (locationError != null) {
            item {
                StatusCard(
                    title = stringResource(R.string.status_location_unavailable),
                    body = locationError!!
                )
            }
        }

        if (poiError != null) {
            item {
                StatusCard(
                    title = stringResource(R.string.status_poi_preview_unavailable),
                    body = poiError!!
                )
            }
        }

        if (routeError != null) {
            item {
                StatusCard(
                    title = stringResource(R.string.status_route_generation_failed),
                    body = routeError!!
                )
            }
        }

        when {
            routeResponse != null -> {
                item {
                    RouteSummaryCard(routeResponse = routeResponse!!)
                }

                if (routeItems.isEmpty()) {
                    item {
                        StatusCard(
                            title = stringResource(R.string.status_no_stops_title),
                            body = stringResource(R.string.status_no_stops_body)
                        )
                    }
                } else {
                    items(
                        items = routeItems,
                        key = { item -> item.poi_id }
                    ) { item ->
                        RouteStopCard(item = item)
                    }
                }
            }

            routeError == null && !isRouteLoading -> {
                item {
                    StatusCard(
                        title = stringResource(R.string.status_no_route_title),
                        body = stringResource(R.string.status_no_route_body)
                    )
                }
            }
        }
    }
}

@Composable
private fun StartPointCard(
    startPoint: RouteStartDto,
    isSelectingStart: Boolean,
    isLocating: Boolean,
    onToggleMapSelection: () -> Unit,
    onUseCurrentLocation: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.start_point_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.start_point_coordinates,
                    formatCoordinate(startPoint.lat),
                    formatCoordinate(startPoint.lon)
                ),
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (isSelectingStart) {
                    stringResource(R.string.start_point_selecting_body)
                } else {
                    stringResource(R.string.start_point_default_body)
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onToggleMapSelection,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        if (isSelectingStart) {
                            stringResource(R.string.action_cancel_map_pick)
                        } else {
                            stringResource(R.string.action_pick_on_map)
                        }
                    )
                }
                Button(
                    onClick = onUseCurrentLocation,
                    modifier = Modifier.weight(1f),
                    enabled = !isLocating
                ) {
                    if (isLocating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                    Text(
                        if (isLocating) {
                            stringResource(R.string.action_locating)
                        } else {
                            stringResource(R.string.action_use_my_location)
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun RouteParametersCard(
    availableMinutes: Int,
    onAvailableMinutesChange: (Int) -> Unit,
    selectedInterests: List<String>,
    onInterestToggle: (String, Boolean) -> Unit,
    pace: String,
    onPaceChange: (String) -> Unit,
    returnToStart: Boolean,
    onReturnToStartChange: (Boolean) -> Unit,
    respectOpeningHours: Boolean,
    onRespectOpeningHoursChange: (Boolean) -> Unit,
    startDateTime: LocalDateTime,
    onUseCurrentTime: () -> Unit,
    isGenerating: Boolean,
    onGenerateRoute: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_parameters_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.route_start_time_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = startDateTime.format(RouteTimeFormatter),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onUseCurrentTime) {
                    Text(stringResource(R.string.action_use_now))
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.respect_opening_hours_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = stringResource(R.string.respect_opening_hours_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = respectOpeningHours,
                    onCheckedChange = onRespectOpeningHoursChange
                )
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.available_time_label),
                style = MaterialTheme.typography.labelLarge
            )
            AvailableMinutesOptions.forEach { option ->
                SelectableRow(
                    onClick = { onAvailableMinutesChange(option) }
                ) {
                    RadioButton(
                        selected = availableMinutes == option,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.available_minutes_option, option))
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.interests_label),
                style = MaterialTheme.typography.labelLarge
            )
            InterestOptions.forEach { interest ->
                val checked = interest in selectedInterests
                SelectableRow(
                    onClick = { onInterestToggle(interest, !checked) }
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(categoryLabel(interest))
                }
            }

            HorizontalDivider()

            Text(
                text = stringResource(R.string.pace_label),
                style = MaterialTheme.typography.labelLarge
            )
            PaceOptions.forEach { option ->
                SelectableRow(
                    onClick = { onPaceChange(option) }
                ) {
                    RadioButton(
                        selected = pace == option,
                        onClick = null
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(paceLabel(option))
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.return_to_start_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = stringResource(R.string.return_to_start_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = returnToStart,
                    onCheckedChange = onReturnToStartChange
                )
            }

            Button(
                onClick = onGenerateRoute,
                enabled = !isGenerating,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isGenerating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(stringResource(R.string.action_generating_route))
                } else {
                    Text(stringResource(R.string.action_generate_route))
                }
            }
        }
    }
}

@Composable
private fun RouteSummaryCard(routeResponse: RouteResponse) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.route_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                stringResource(R.string.route_summary_city, routeResponse.city)
            )
            Text(
                stringResource(
                    R.string.route_summary_start,
                    formatCoordinate(routeResponse.start.lat),
                    formatCoordinate(routeResponse.start.lon)
                )
            )
            Text(
                stringResource(
                    R.string.route_summary_start_time,
                    routeResponse.start_datetime.toRouteDateTimeLabel(stringResource(R.string.common_unknown))
                )
            )
            Text(stringResource(R.string.route_summary_pace, paceLabel(routeResponse.pace)))
            Text(stringResource(R.string.route_summary_stops, routeResponse.poi_count))
            Text(
                stringResource(
                    R.string.route_summary_used_time,
                    routeResponse.used_minutes,
                    routeResponse.available_minutes
                )
            )
            Text(stringResource(R.string.route_summary_walking, routeResponse.total_walk_minutes))
            Text(stringResource(R.string.route_summary_visits, routeResponse.total_visit_minutes))
            Text(stringResource(R.string.route_summary_remaining, routeResponse.remaining_minutes))
            Text(stringResource(R.string.route_summary_return_to_start, routeResponse.return_to_start_minutes))
            Text(
                stringResource(
                    R.string.route_summary_opening_hours_filter,
                    if (routeResponse.respect_opening_hours) {
                        stringResource(R.string.state_on)
                    } else {
                        stringResource(R.string.state_off)
                    }
                )
            )
        }
    }
}

@Composable
private fun RouteStopCard(item: RouteItemDto) {
    Card {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = stringResource(R.string.route_stop_title, item.order, item.name),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = categoryLabel(item.category),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(stringResource(R.string.route_stop_walk_from_previous, item.travel_minutes_from_previous))
            Text(stringResource(R.string.route_stop_visit_duration, item.visit_duration_min))
            Text(stringResource(R.string.route_stop_arrival_after_start, item.arrival_after_min))
            Text(stringResource(R.string.route_stop_departure_after_start, item.departure_after_min))
            if (!item.opening_hours_raw.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.route_stop_opening_hours, item.opening_hours_raw),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}

@Composable
private fun SelectableRow(
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content
    )
}

private fun applySavedSnapshot(
    snapshot: SavedRouteSnapshot,
    onRouteRestored: (RouteResponse) -> Unit,
    onStartPointRestored: (RouteStartDto) -> Unit,
    onAvailableMinutesRestored: (Int) -> Unit,
    onPaceRestored: (String) -> Unit,
    onReturnToStartRestored: (Boolean) -> Unit,
    onRespectOpeningHoursRestored: (Boolean) -> Unit,
    onStartDateTimeRestored: (LocalDateTime) -> Unit,
    selectedInterests: MutableList<String>
) {
    onRouteRestored(snapshot.response)
    onStartPointRestored(RouteStartDto(snapshot.request.start_lat, snapshot.request.start_lon))
    onAvailableMinutesRestored(snapshot.request.available_minutes)
    onPaceRestored(snapshot.request.pace)
    onReturnToStartRestored(snapshot.request.return_to_start)
    onRespectOpeningHoursRestored(snapshot.request.respect_opening_hours)
    onStartDateTimeRestored(parseRouteStartDateTime(snapshot.request.start_datetime))

    selectedInterests.clear()
    selectedInterests.addAll(snapshot.request.interests)
}

@Composable
private fun categoryLabel(category: String): String =
    when (category) {
        "attraction" -> stringResource(R.string.category_attraction)
        "museum" -> stringResource(R.string.category_museum)
        "gallery" -> stringResource(R.string.category_gallery)
        "viewpoint" -> stringResource(R.string.category_viewpoint)
        "monument" -> stringResource(R.string.category_monument)
        "historical_site" -> stringResource(R.string.category_historical_site)
        "park" -> stringResource(R.string.category_park)
        "religious_site" -> stringResource(R.string.category_religious_site)
        else -> category.toDisplayLabel()
    }


@Composable
private fun paceLabel(pace: String): String =
    when (pace) {
        "slow" -> stringResource(R.string.pace_slow)
        "normal" -> stringResource(R.string.pace_normal)
        "fast" -> stringResource(R.string.pace_fast)
        else -> pace.toDisplayLabel()
    }

private fun fetchCurrentLocation(
    context: Context,
    onSuccess: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError(context.getString(R.string.error_location_service_unavailable))
            return
        }

        val hasFinePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val hasCoarsePermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasFinePermission && !hasCoarsePermission) {
            onError(context.getString(R.string.error_location_permission_missing))
            return
        }

        val enabledProviders = buildList {
            if (hasFinePermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }.distinct()

        val fallbackProviders = buildList {
            addAll(enabledProviders)
            if (hasFinePermission) {
                add(LocationManager.GPS_PROVIDER)
            }
            add(LocationManager.NETWORK_PROVIDER)
        }.distinct()

        val lastKnownLocation = fallbackProviders
            .mapNotNull { provider ->
                runCatching {
                    locationManager.getLastKnownLocation(provider)
                }.getOrNull()
            }
            .maxByOrNull { it.time }

        val provider = enabledProviders.firstOrNull()
        if (provider == null) {
            if (lastKnownLocation != null) {
                onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
            } else {
                onError(context.getString(R.string.error_no_location_provider_enabled))
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                when {
                    location != null -> onSuccess(location.latitude, location.longitude)
                    lastKnownLocation != null -> onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    else -> onError(context.getString(R.string.error_current_location_unavailable))
                }
            }
            return
        }

        @Suppress("DEPRECATION")
        locationManager.requestSingleUpdate(
            provider,
            object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    onSuccess(location.latitude, location.longitude)
                    locationManager.removeUpdates(this)
                }

                override fun onProviderDisabled(provider: String) {
                    if (lastKnownLocation != null) {
                        onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    } else {
                        onError(context.getString(R.string.error_no_location_provider_enabled))
                    }
                    locationManager.removeUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    } catch (securityException: SecurityException) {
        onError(context.getString(R.string.error_location_permission_missing))
    }
}

private fun defaultRouteStartDateTime(): LocalDateTime =
    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)

private fun parseRouteStartDateTime(rawValue: String?): LocalDateTime =
    runCatching {
        rawValue?.let(LocalDateTime::parse)?.truncatedTo(ChronoUnit.MINUTES) ?: defaultRouteStartDateTime()
    }.getOrElse {
        defaultRouteStartDateTime()
    }

private fun String.toDisplayLabel(): String =
    split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

private fun String?.toRouteDateTimeLabel(unknownLabel: String): String =
    runCatching {
        this?.let(LocalDateTime::parse)?.format(RouteTimeFormatter)
    }.getOrNull() ?: unknownLabel

private fun Throwable.toUserMessage(defaultMessage: String): String {
    val rawMessage = message?.substringBefore('\n')?.trim()
    return if (rawMessage.isNullOrEmpty()) defaultMessage else rawMessage
}

private fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)
