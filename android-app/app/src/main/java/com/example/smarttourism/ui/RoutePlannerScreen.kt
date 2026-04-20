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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
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
            locationError = "Location permission denied."
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
            poiError = e.toUserMessage("Failed to load POI preview.")
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
                    text = "Personal route planner",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Pick a custom start point, use your current location, generate a route, and restore the last saved result.",
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
                            routeError = e.toUserMessage("Failed to generate route.")
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
                    title = "Location unavailable",
                    body = locationError!!
                )
            }
        }

        if (poiError != null) {
            item {
                StatusCard(
                    title = "POI preview unavailable",
                    body = poiError!!
                )
            }
        }

        if (routeError != null) {
            item {
                StatusCard(
                    title = "Route generation failed",
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
                            title = "No stops generated",
                            body = "No route stops match the selected time budget, interests, and opening-hours constraints."
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
                        title = "No route yet",
                        body = "Choose a start point and parameters, then generate a route. The last successful route is restored automatically on app launch."
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
                text = "Start point",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "Lat ${formatCoordinate(startPoint.lat)}, Lon ${formatCoordinate(startPoint.lon)}",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = if (isSelectingStart) {
                    "Selection mode is active. Tap the map to move the start point."
                } else {
                    "Use the map or device location to change where the route begins."
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(
                    onClick = onToggleMapSelection,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (isSelectingStart) "Cancel map pick" else "Pick on map")
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
                    Text(if (isLocating) "Locating..." else "Use my location")
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
                text = "Route parameters",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Route start time",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = startDateTime.format(RouteTimeFormatter),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onUseCurrentTime) {
                    Text("Use now")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Respect opening hours",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "Skip POIs that are closed for the planned arrival and visit window.",
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
                text = "Available time",
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
                    Text("$option minutes")
                }
            }

            HorizontalDivider()

            Text(
                text = "Interests",
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
                    Text(interest.toDisplayLabel())
                }
            }

            HorizontalDivider()

            Text(
                text = "Pace",
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
                    Text(option.replaceFirstChar { it.uppercase() })
                }
            }

            HorizontalDivider()

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Return to start",
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = "Include the walk back to the selected start point.",
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
                    Text("Generating route...")
                } else {
                    Text("Generate route")
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
                text = "Route summary",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text("City: ${routeResponse.city}")
            Text(
                "Start: ${formatCoordinate(routeResponse.start.lat)}, ${formatCoordinate(routeResponse.start.lon)}"
            )
            Text("Start time: ${routeResponse.start_datetime.toRouteDateTimeLabel()}")
            Text("Pace: ${routeResponse.pace.replaceFirstChar { it.uppercase() }}")
            Text("Stops: ${routeResponse.poi_count}")
            Text("Used time: ${routeResponse.used_minutes} / ${routeResponse.available_minutes} min")
            Text("Walking: ${routeResponse.total_walk_minutes} min")
            Text("Visits: ${routeResponse.total_visit_minutes} min")
            Text("Remaining: ${routeResponse.remaining_minutes} min")
            Text("Return to start: ${routeResponse.return_to_start_minutes} min")
            Text(
                "Opening-hours filter: ${if (routeResponse.respect_opening_hours) "on" else "off"}"
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
                text = "${item.order}. ${item.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = item.category.toDisplayLabel(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text("Walk from previous: ${item.travel_minutes_from_previous} min")
            Text("Visit duration: ${item.visit_duration_min} min")
            Text("Arrival after start: ${item.arrival_after_min} min")
            Text("Departure after start: ${item.departure_after_min} min")
            if (!item.opening_hours_raw.isNullOrBlank()) {
                Text(
                    text = "Opening hours: ${item.opening_hours_raw}",
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

private fun fetchCurrentLocation(
    context: Context,
    onSuccess: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError("Location service is unavailable.")
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
            onError("Location permission is missing.")
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
                onError("No location provider is enabled.")
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                when {
                    location != null -> onSuccess(location.latitude, location.longitude)
                    lastKnownLocation != null -> onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    else -> onError("Current location is unavailable.")
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
                        onError("No location provider is enabled.")
                    }
                    locationManager.removeUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    } catch (securityException: SecurityException) {
        onError("Location permission is missing.")
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

private fun String?.toRouteDateTimeLabel(): String =
    runCatching {
        this?.let(LocalDateTime::parse)?.format(RouteTimeFormatter)
    }.getOrNull() ?: "Unknown"

private fun Throwable.toUserMessage(defaultMessage: String): String {
    val rawMessage = message?.substringBefore('\n')?.trim()
    return if (rawMessage.isNullOrEmpty()) defaultMessage else rawMessage
}

private fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)
