package com.example.smarttourism.features.planner.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.smarttourism.R
import com.example.smarttourism.core.i18n.AppLanguage
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteItemDto
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteStartDto
import com.example.smarttourism.features.map.MapLocationButton
import com.example.smarttourism.features.map.PoiMapScreen
import com.example.smarttourism.features.planner.components.ActiveRouteBottomPanel
import com.example.smarttourism.features.planner.components.CitySelectorCard
import com.example.smarttourism.features.planner.components.OfflineSupportCard
import com.example.smarttourism.features.planner.components.ReplaceRouteStopSheetContent
import com.example.smarttourism.features.planner.components.RequiredPlacesCard
import com.example.smarttourism.features.planner.components.RequiredPlacesPickerSheetContent
import com.example.smarttourism.features.planner.components.RouteBookmarksSheetContent
import com.example.smarttourism.features.planner.components.RouteFeedbackCard
import com.example.smarttourism.features.planner.components.RouteHistoryDetailsDialog
import com.example.smarttourism.features.planner.components.RouteHistorySheetContent
import com.example.smarttourism.features.planner.components.RouteParametersCard
import com.example.smarttourism.features.planner.components.RoutePreviewSummaryPanel
import com.example.smarttourism.features.planner.components.RouteStopTimelineItem
import com.example.smarttourism.features.planner.components.RouteSummaryCard
import com.example.smarttourism.features.planner.components.RouteTrackingCard
import com.example.smarttourism.features.planner.components.StartPointCard
import com.example.smarttourism.features.planner.components.StatusCard
import com.example.smarttourism.features.planner.state.PlannerMode
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.TrackingPermissionAction
import com.example.smarttourism.features.planner.viewmodel.RoutePlannerViewModel
import com.example.smarttourism.features.planner.viewmodel.fetchCurrentLocation
import com.example.smarttourism.features.planner.viewmodel.startRouteLocationTracking

internal enum class PlannerDestination {
    PLANNER,
    SAVED_ROUTES,
    HISTORY,
    OFFLINE
}

@Composable
internal fun PlannerTopBar(
    currentDestination: PlannerDestination,
    selectedLanguage: AppLanguage,
    cities: List<CityDto>,
    selectedCity: CityDto?,
    routeBookmarkCount: Int,
    isCitySelectionEnabled: Boolean,
    onDestinationSelected: (PlannerDestination) -> Unit,
    onCitySelected: (CityDto) -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    var isAppMenuOpen by remember { mutableStateOf(false) }
    var isCityMenuOpen by remember { mutableStateOf(false) }
    var isLanguageMenuOpen by remember { mutableStateOf(false) }

    Surface(tonalElevation = 3.dp) {
        val cityLabel = selectedCity?.name ?: stringResource(
            if (cities.isEmpty()) {
                R.string.city_selector_empty
            } else {
                R.string.app_city_select
            }
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = destinationLabel(currentDestination, routeBookmarkCount),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Box {
                TextButton(
                    onClick = { isCityMenuOpen = true },
                    enabled = isCitySelectionEnabled && cities.isNotEmpty()
                ) {
                    Text(cityLabel)
                }
                DropdownMenu(
                    expanded = isCityMenuOpen,
                    onDismissRequest = { isCityMenuOpen = false }
                ) {
                    cities.forEach { city ->
                        DropdownMenuItem(
                            text = { Text(city.name) },
                            enabled = selectedCity?.slug != city.slug,
                            onClick = {
                                isCityMenuOpen = false
                                onCitySelected(city)
                            }
                        )
                    }
                }
            }

            Box {
                TextButton(onClick = { isLanguageMenuOpen = true }) {
                    Text(languageShortLabel(selectedLanguage))
                }
                DropdownMenu(
                    expanded = isLanguageMenuOpen,
                    onDismissRequest = { isLanguageMenuOpen = false }
                ) {
                    AppLanguage.entries.forEach { language ->
                        DropdownMenuItem(
                            text = { Text(languageLabel(language)) },
                            enabled = selectedLanguage != language,
                            onClick = {
                                isLanguageMenuOpen = false
                                onLanguageSelected(language)
                            }
                        )
                    }
                }
            }

            Box {
                TextButton(onClick = { isAppMenuOpen = true }) {
                    Text(stringResource(R.string.app_menu_label))
                }
                DropdownMenu(
                    expanded = isAppMenuOpen,
                    onDismissRequest = { isAppMenuOpen = false }
                ) {
                    DropdownMenuItem(
                        text = { Text(destinationLabel(PlannerDestination.PLANNER, routeBookmarkCount)) },
                        enabled = currentDestination != PlannerDestination.PLANNER,
                        onClick = {
                            isAppMenuOpen = false
                            onDestinationSelected(PlannerDestination.PLANNER)
                        }
                    )
                    HorizontalDivider()
                    listOf(
                        PlannerDestination.SAVED_ROUTES,
                        PlannerDestination.HISTORY,
                        PlannerDestination.OFFLINE
                    ).forEach { destination ->
                        DropdownMenuItem(
                            text = { Text(destinationLabel(destination, routeBookmarkCount)) },
                            enabled = currentDestination != destination,
                            onClick = {
                                isAppMenuOpen = false
                                onDestinationSelected(destination)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun destinationLabel(destination: PlannerDestination, routeBookmarkCount: Int): String =
    when (destination) {
        PlannerDestination.PLANNER -> stringResource(R.string.app_destination_planner)
        PlannerDestination.SAVED_ROUTES -> if (routeBookmarkCount > 0) {
            stringResource(R.string.action_open_route_bookmarks_with_count, routeBookmarkCount)
        } else {
            stringResource(R.string.action_open_route_bookmarks)
        }
        PlannerDestination.HISTORY -> stringResource(R.string.action_open_route_history)
        PlannerDestination.OFFLINE -> stringResource(R.string.offline_support_title)
    }

private fun languageShortLabel(language: AppLanguage): String =
    when (language) {
        AppLanguage.ENGLISH -> "EN"
        AppLanguage.SLOVAK -> "SK"
        AppLanguage.UKRAINIAN -> "UK"
        AppLanguage.CZECH -> "CS"
        AppLanguage.GERMAN -> "DE"
        AppLanguage.POLISH -> "PL"
        AppLanguage.HUNGARIAN -> "HU"
    }

@Composable
internal fun PlannerModeHeader(
    mode: PlannerMode
) {
    val title = when (mode) {
        PlannerMode.PLANNING -> stringResource(R.string.planner_mode_planning_title)
        PlannerMode.PREVIEW -> stringResource(R.string.planner_mode_preview_title)
        PlannerMode.ACTIVE -> stringResource(R.string.planner_mode_active_title)
        PlannerMode.COMPLETED -> stringResource(R.string.planner_mode_completed_title)
    }
    val body = when (mode) {
        PlannerMode.PLANNING -> stringResource(R.string.planner_mode_planning_body)
        PlannerMode.PREVIEW -> stringResource(R.string.planner_mode_preview_body)
        PlannerMode.ACTIVE -> stringResource(R.string.planner_mode_active_body)
        PlannerMode.COMPLETED -> stringResource(R.string.planner_mode_completed_body)
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = stringResource(R.string.screen_title),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun languageLabel(language: AppLanguage): String =
    when (language) {
        AppLanguage.ENGLISH -> stringResource(R.string.language_english)
        AppLanguage.SLOVAK -> stringResource(R.string.language_slovak)
        AppLanguage.UKRAINIAN -> stringResource(R.string.language_ukrainian)
        AppLanguage.CZECH -> stringResource(R.string.language_czech)
        AppLanguage.GERMAN -> stringResource(R.string.language_german)
        AppLanguage.POLISH -> stringResource(R.string.language_polish)
        AppLanguage.HUNGARIAN -> stringResource(R.string.language_hungarian)
    }

@Composable
internal fun PlannerMapPanel(
    pois: List<com.example.smarttourism.data.remote.dto.PoiDto>,
    routeResponse: RouteResponse?,
    startPoint: RouteStartDto,
    defaultZoom: Double?,
    currentRouteLocation: RouteStartDto?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    isRouteActive: Boolean,
    isPoiLoading: Boolean,
    isSelectingStart: Boolean,
    isSelectingRoutePois: Boolean,
    selectedRoutePoiIds: List<Int>,
    onStartPointSelected: (Double, Double) -> Unit,
    onRoutePoiSelected: (PoiDto) -> Unit,
    onOpenFullScreenMap: () -> Unit,
    preferCurrentLocationCamera: Boolean = false,
    locationButtonBottomPadding: Dp? = null,
    showLocationButton: Boolean = true,
    recenterLocationRequestKey: Int = 0,
    currentLocationCameraYOffset: Dp = 0.dp,
    modifier: Modifier = Modifier,
    fixedHeight: Dp? = null
) {
    val containerModifier = if (fixedHeight != null) {
        modifier
            .fillMaxWidth()
            .height(fixedHeight)
    } else {
        modifier.fillMaxWidth()
    }

    Box(
        modifier = containerModifier.clip(MaterialTheme.shapes.extraLarge)
    ) {
        PoiMapScreen(
            pois = pois,
            routeResponse = routeResponse,
            startLat = startPoint.lat,
            startLon = startPoint.lon,
            defaultZoom = defaultZoom,
            currentLocation = currentRouteLocation,
            visitedPoiIds = visitedPoiIds.toSet(),
            skippedPoiIds = skippedPoiIds.toSet(),
            isRouteActive = isRouteActive,
            isLoading = isPoiLoading,
            isFullScreen = false,
            isSelectingStart = isSelectingStart,
            isSelectingRoutePois = isSelectingRoutePois,
            selectedRoutePoiIds = selectedRoutePoiIds.toSet(),
            preferCurrentLocationCamera = preferCurrentLocationCamera,
            locationButtonBottomPadding = locationButtonBottomPadding,
            showLocationButton = showLocationButton,
            recenterLocationRequestKey = recenterLocationRequestKey,
            currentLocationCameraYOffset = currentLocationCameraYOffset,
            onStartPointSelected = onStartPointSelected,
            onRoutePoiSelected = onRoutePoiSelected,
            modifier = Modifier.fillMaxSize()
        )
        OutlinedButton(
            onClick = onOpenFullScreenMap,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp)
        ) {
            Text(stringResource(R.string.action_open_full_screen_map))
        }
    }
}

@Composable
internal fun PreviewActionRow(
    canStart: Boolean,
    onStartRoute: () -> Unit,
    onEditParameters: () -> Unit,
    onPlanAnotherRoute: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = onStartRoute,
            enabled = canStart,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_start_route))
        }
        OutlinedButton(
            onClick = onEditParameters,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_edit_parameters))
        }
        Text(
            text = stringResource(R.string.route_preview_edit_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(
            onClick = onPlanAnotherRoute,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_plan_another_route))
        }
    }
}

internal data class PlannerAlerts(
    val locationError: String?,
    val poiError: String?,
    val routeError: String?,
    val trackingError: String?,
    val hasNoGeneratedStops: Boolean,
    val hasPendingRouteChanges: Boolean
)

internal fun PlannerAlerts.hasVisibleAlerts(): Boolean =
    locationError != null ||
        poiError != null ||
        routeError != null ||
        trackingError != null ||
        hasNoGeneratedStops ||
        hasPendingRouteChanges

@Composable
internal fun PlannerAlertColumn(alerts: PlannerAlerts) {
    alerts.locationError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_location_unavailable),
            body = message
        )
    }

    alerts.poiError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_poi_preview_unavailable),
            body = message
        )
    }

    alerts.routeError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_route_generation_failed),
            body = message
        )
    }

    if (alerts.hasNoGeneratedStops) {
        StatusCard(
            title = stringResource(R.string.status_no_stops_title),
            body = stringResource(R.string.status_no_stops_body)
        )
    }

    if (alerts.hasPendingRouteChanges) {
        StatusCard(
            title = stringResource(R.string.route_preview_outdated_title),
            body = stringResource(R.string.route_preview_outdated_body)
        )
    }

    alerts.trackingError?.let { message ->
        StatusCard(
            title = stringResource(R.string.status_gps_tracking_unavailable),
            body = message
        )
    }
}

internal fun LazyListScope.plannerAlertItems(alerts: PlannerAlerts) {
    if (!alerts.hasVisibleAlerts()) {
        return
    }

    item {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PlannerAlertColumn(alerts = alerts)
        }
    }
}

internal fun LazyListScope.routeStopItems(
    titleRes: Int,
    routeStops: List<RouteItemDto>,
    routeResponse: RouteResponse?,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    isRouteActive: Boolean,
    canSkip: Boolean,
    canReplace: Boolean,
    canMove: Boolean,
    isActionInProgress: Boolean,
    highlightedPoiId: Int?,
    onMarkVisited: (Int) -> Unit,
    onSkip: (Int) -> Unit,
    onMove: (Int, Int) -> Unit,
    onReplace: (Int) -> Unit
) {
    if (routeStops.isEmpty()) {
        item {
            StatusCard(
                title = stringResource(R.string.status_no_stops_title),
                body = stringResource(R.string.status_no_stops_body)
            )
        }
        return
    }

    item {
        Text(
            text = stringResource(titleRes),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
    }

    itemsIndexed(
        items = routeStops,
        key = { _, item -> item.poi_id }
    ) { index, item ->
        RouteStopTimelineItem(
            item = item,
            incomingLeg = routeResponse?.legs.orEmpty().firstOrNull { leg -> leg.to.poi_id == item.poi_id },
            isVisited = item.poi_id in visitedPoiIds,
            isSkipped = item.poi_id in skippedPoiIds,
            isNext = highlightedPoiId == item.poi_id,
            isLast = index == routeStops.lastIndex,
            isRouteActive = isRouteActive,
            canSkip = canSkip,
            canReplace = canReplace,
            canMoveUp = canMove && index > 0,
            canMoveDown = canMove && index < routeStops.lastIndex,
            isActionInProgress = isActionInProgress,
            onMarkVisited = { onMarkVisited(item.poi_id) },
            onSkip = { onSkip(item.poi_id) },
            onMoveUp = { onMove(item.poi_id, -1) },
            onMoveDown = { onMove(item.poi_id, 1) },
            onReplace = { onReplace(item.poi_id) }
        )
    }
}

internal fun plannerModeFor(
    routeResponse: RouteResponse?,
    status: RouteSessionStatus
): PlannerMode =
    when {
        status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED -> PlannerMode.ACTIVE
        routeResponse != null && status == RouteSessionStatus.COMPLETED -> PlannerMode.COMPLETED
        routeResponse != null -> PlannerMode.PREVIEW
        else -> PlannerMode.PLANNING
    }
