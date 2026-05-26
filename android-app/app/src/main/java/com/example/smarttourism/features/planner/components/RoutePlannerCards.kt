package com.example.smarttourism.features.planner

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.core.network.ApiModule
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.repository.OfflineCacheRepository
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteLegDto
import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteSegmentDto
import com.example.smarttourism.data.remote.dto.RouteSessionDto
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.remote.dto.RouteStartDto
import com.example.smarttourism.data.repository.RouteStorage
import com.example.smarttourism.data.remote.dto.RouteItemDto
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.sync.OfflineSyncScheduler
import com.example.smarttourism.R
import com.example.smarttourism.features.map.offline.OfflineCityRegion
import com.example.smarttourism.features.map.offline.OfflineMapManager
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun OfflineSupportCard(
    selectedCity: CityDto?,
    offlineStatusMessage: String?,
    pendingSyncOperationCount: Int,
    offlineRegionAvailable: Boolean,
    isOfflineMapBusy: Boolean,
    offlineMapProgress: OfflineDownloadProgress?,
    offlineMapMessage: String?,
    onDownloadOfflineMap: () -> Unit,
    onDeleteOfflineMap: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.offline_support_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.offline_support_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!offlineStatusMessage.isNullOrBlank()) {
                Text(
                    text = offlineStatusMessage,
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            if (pendingSyncOperationCount > 0) {
                Text(
                    text = stringResource(R.string.pending_sync_status, pendingSyncOperationCount),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            val city = selectedCity
            if (city == null) {
                Text(
                    text = stringResource(R.string.offline_map_city_unavailable),
                    style = MaterialTheme.typography.bodyMedium
                )
                return@Column
            }

            Text(
                text = if (offlineRegionAvailable) {
                    stringResource(R.string.offline_map_available, city.name)
                } else {
                    stringResource(R.string.offline_map_not_downloaded, city.name)
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium
            )

            if (city.bbox == null) {
                Text(
                    text = stringResource(R.string.offline_map_bbox_missing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (offlineRegionAvailable) {
                        OutlinedButton(
                            onClick = onDeleteOfflineMap,
                            enabled = !isOfflineMapBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_delete_offline_map))
                        }
                    } else {
                        Button(
                            onClick = onDownloadOfflineMap,
                            enabled = !isOfflineMapBusy,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_download_offline_map))
                        }
                    }
                }
            }

            if (offlineMapProgress != null) {
                LinearProgressIndicator(
                    progress = { (offlineMapProgress.percent / 100.0).toFloat().coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = stringResource(
                        R.string.offline_map_progress,
                        offlineMapProgress.percent.toInt(),
                        offlineMapProgress.completed,
                        offlineMapProgress.required
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!offlineMapMessage.isNullOrBlank()) {
                Text(
                    text = offlineMapMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun CitySelectorCard(
    cities: List<CityDto>,
    selectedCity: CityDto?,
    enabled: Boolean = true,
    onCitySelected: (CityDto) -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.city_selector_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.city_selector_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (cities.isEmpty()) {
                Text(
                    text = stringResource(R.string.city_selector_empty),
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                cities.forEach { city ->
                    SelectableRow(
                        enabled = enabled,
                        onClick = { onCitySelected(city) }
                    ) {
                        RadioButton(
                            selected = selectedCity?.id == city.id,
                            onClick = null,
                            enabled = enabled
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(city.name)
                            Text(
                                text = city.country,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun StartPointCard(
    startPoint: RouteStartDto,
    isSelectingStart: Boolean,
    isLocating: Boolean,
    enabled: Boolean,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onToggleMapSelection,
                    modifier = Modifier.weight(1f),
                    enabled = enabled
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
                    enabled = enabled && !isLocating
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
@OptIn(ExperimentalLayoutApi::class)
internal fun RequiredPlacesCard(
    selectedPois: List<PoiDto>,
    availablePoiCount: Int,
    isEditingEnabled: Boolean,
    onChooseOnMap: () -> Unit,
    onChooseFromList: () -> Unit,
    onMovePoi: (Int, Int) -> Unit,
    onRemovePoi: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_required_places_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_required_places_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedPois.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_required_places_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedPois.forEachIndexed { index, poi ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = poi.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = categoryLabel(poi.category),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    TextButton(
                                        onClick = { onMovePoi(poi.id, -1) },
                                        enabled = isEditingEnabled && index > 0
                                    ) {
                                        Text(stringResource(R.string.action_move_up))
                                    }
                                    TextButton(
                                        onClick = { onMovePoi(poi.id, 1) },
                                        enabled = isEditingEnabled && index < selectedPois.lastIndex
                                    ) {
                                        Text(stringResource(R.string.action_move_down))
                                    }
                                    TextButton(
                                        onClick = { onRemovePoi(poi.id) },
                                        enabled = isEditingEnabled
                                    ) {
                                        Text(stringResource(R.string.action_remove_stop))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onChooseOnMap,
                    enabled = isEditingEnabled && availablePoiCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_choose_required_places_on_map))
                }
                Button(
                    onClick = onChooseFromList,
                    enabled = isEditingEnabled && availablePoiCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_choose_required_places_from_list))
                }
            }

            if (selectedPois.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    enabled = isEditingEnabled,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_clear_required_places))
                }
            }
        }
    }
}

@Composable
internal fun RequiredPlacesPickerSheetContent(
    pois: List<PoiDto>,
    selectedPoiIds: List<Int>,
    isEditingEnabled: Boolean,
    onTogglePoi: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.route_required_places_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_required_places_picker_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (pois.isEmpty()) {
            Text(
                text = stringResource(R.string.route_required_places_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = pois.sortedWith(compareBy<PoiDto> { poi -> poi.category }.thenBy { poi -> poi.name }),
                    key = { poi -> poi.id }
                ) { poi ->
                    val selected = poi.id in selectedPoiIds
                    SelectableRow(
                        enabled = isEditingEnabled,
                        onClick = { onTogglePoi(poi.id) }
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            enabled = isEditingEnabled
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = poi.name,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = categoryLabel(poi.category),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun RouteParametersCard(
    availableMinutes: Int,
    maxAvailableMinutes: Int,
    onAvailableMinutesChange: (Int) -> Unit,
    availableInterests: List<String>,
    selectedInterests: List<String>,
    onInterestToggle: (String, Boolean) -> Unit,
    pace: String,
    onPaceChange: (String) -> Unit,
    returnToStart: Boolean,
    onReturnToStartChange: (Boolean) -> Unit,
    respectOpeningHours: Boolean,
    onRespectOpeningHoursChange: (Boolean) -> Unit,
    isPublicTransportAvailable: Boolean,
    allowPublicTransport: Boolean,
    onAllowPublicTransportChange: (Boolean) -> Unit,
    startDateTime: LocalDateTime,
    onStartDateTimeChange: (LocalDateTime) -> Unit,
    onUseCurrentTime: () -> Unit,
    isEditingEnabled: Boolean,
    isGenerating: Boolean,
    onGenerateRoute: () -> Unit
) {
    val context = LocalContext.current
    val datePickerDialog = remember(context, startDateTime, onStartDateTimeChange) {
        DatePickerDialog(
            context,
            { _, year, month, dayOfMonth ->
                onStartDateTimeChange(
                    startDateTime
                        .withYear(year)
                        .withMonth(month + 1)
                        .withDayOfMonth(dayOfMonth)
                )
            },
            startDateTime.year,
            startDateTime.monthValue - 1,
            startDateTime.dayOfMonth
        )
    }
    val timePickerDialog = remember(context, startDateTime, onStartDateTimeChange) {
        TimePickerDialog(
            context,
            { _, hourOfDay, minute ->
                onStartDateTimeChange(
                    startDateTime
                        .withHour(hourOfDay)
                        .withMinute(minute)
                )
            },
            startDateTime.hour,
            startDateTime.minute,
            DateFormat.is24HourFormat(context)
        )
    }

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = stringResource(R.string.route_parameters_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.route_start_time_label),
                        style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        text = startDateTime.format(RouteTimeFormatter),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { datePickerDialog.show() },
                        enabled = isEditingEnabled
                    ) {
                        Text(stringResource(R.string.action_pick_date))
                    }
                    OutlinedButton(
                        onClick = { timePickerDialog.show() },
                        enabled = isEditingEnabled
                    ) {
                        Text(stringResource(R.string.action_pick_time))
                    }
                    TextButton(
                        onClick = onUseCurrentTime,
                        enabled = isEditingEnabled
                    ) {
                        Text(stringResource(R.string.action_use_now))
                    }
                }
            }

            Text(
                text = stringResource(R.string.available_time_label),
                style = MaterialTheme.typography.labelLarge
            )
            val sliderMaximum = maxOf(MinimumAvailableMinutes, maxAvailableMinutes)
            val sliderSteps = ((sliderMaximum - MinimumAvailableMinutes) / AvailableMinutesStepMinutes - 1)
                .coerceAtLeast(0)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = formatAvailableMinutes(availableMinutes),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${formatAvailableMinutes(MinimumAvailableMinutes)} - ${formatAvailableMinutes(sliderMaximum)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Slider(
                    value = availableMinutes.toFloat(),
                    onValueChange = { rawValue ->
                        val steppedValue = rawValue.roundToInt()
                        onAvailableMinutesChange(steppedValue)
                    },
                    valueRange = MinimumAvailableMinutes.toFloat()..sliderMaximum.toFloat(),
                    steps = sliderSteps,
                    enabled = isEditingEnabled
                )
            }

            Text(
                text = stringResource(R.string.pace_label),
                style = MaterialTheme.typography.labelLarge
            )
            SingleChoiceChipRow(
                options = PaceOptions,
                selectedOption = pace,
                enabled = isEditingEnabled,
                label = { option -> Text(paceLabel(option)) },
                onOptionSelected = onPaceChange
            )

            Text(
                text = stringResource(R.string.interests_label),
                style = MaterialTheme.typography.labelLarge
            )
            if (availableInterests.isEmpty()) {
                Text(
                    text = stringResource(R.string.interests_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    availableInterests.forEach { interest ->
                        val checked = interest in selectedInterests
                        FilterChip(
                            selected = checked,
                            onClick = { onInterestToggle(interest, !checked) },
                            enabled = isEditingEnabled,
                            label = { Text(categoryLabel(interest)) }
                        )
                    }
                }
            }

            HorizontalDivider()

            CompactToggleRow(
                title = stringResource(R.string.respect_opening_hours_label),
                body = stringResource(R.string.respect_opening_hours_body),
                checked = respectOpeningHours,
                enabled = isEditingEnabled,
                onCheckedChange = onRespectOpeningHoursChange
            )

            CompactToggleRow(
                title = stringResource(R.string.return_to_start_label),
                body = stringResource(R.string.return_to_start_body),
                checked = returnToStart,
                enabled = isEditingEnabled,
                onCheckedChange = onReturnToStartChange
            )

            if (isPublicTransportAvailable) {
                CompactToggleRow(
                    title = stringResource(R.string.allow_public_transport_label),
                    body = stringResource(R.string.allow_public_transport_body),
                    checked = allowPublicTransport,
                    enabled = isEditingEnabled,
                    onCheckedChange = onAllowPublicTransportChange
                )
            }

            Button(
                onClick = onGenerateRoute,
                enabled = isEditingEnabled && !isGenerating,
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
@OptIn(ExperimentalLayoutApi::class)
private fun <T> SingleChoiceChipRow(
    options: List<T>,
    selectedOption: T,
    enabled: Boolean,
    label: @Composable (T) -> Unit,
    onOptionSelected: (T) -> Unit
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selectedOption == option,
                onClick = { onOptionSelected(option) },
                enabled = enabled,
                label = { label(option) }
            )
        }
    }
}

@Composable
private fun CompactToggleRow(
    title: String,
    body: String,
    checked: Boolean,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge
                )
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Switch(
                checked = checked,
                onCheckedChange = if (enabled) onCheckedChange else null,
                enabled = enabled
            )
        }
    }
}

private fun formatAvailableMinutes(totalMinutes: Int): String {
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> "${minutes} min"
        minutes == 0 -> "${hours} h"
        else -> "${hours} h ${minutes} min"
    }
}

@Composable
internal fun RouteTrackingCard(
    status: RouteSessionStatus,
    routeId: String?,
    startedAt: String?,
    metrics: RouteProgressMetrics,
    currentLocation: RouteStartDto?,
    isRerouting: Boolean,
    canStartCurrentRoute: Boolean,
    onStartRoute: () -> Unit,
    onPauseRoute: () -> Unit,
    onResumeRoute: () -> Unit,
    onFinishRoute: () -> Unit,
    onCancelRoute: () -> Unit
) {
    val progress = if (metrics.totalCount == 0) {
        0f
    } else {
        metrics.visitedCount.toFloat() / metrics.totalCount.toFloat()
    }
    val nextTargetName = metrics.nextTarget?.name ?: stringResource(R.string.route_tracking_no_next_target)
    val canPause = status == RouteSessionStatus.IN_PROGRESS
    val canResume = status == RouteSessionStatus.PAUSED
    val canStart = status == RouteSessionStatus.NOT_STARTED ||
        status == RouteSessionStatus.COMPLETED ||
        status == RouteSessionStatus.CANCELLED

    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = stringResource(R.string.route_tracking_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = routeSessionStatusLabel(status),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (!routeId.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.route_tracking_route_id, routeId.takeLast(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!startedAt.isNullOrBlank()) {
                Text(
                    text = stringResource(
                        R.string.route_tracking_started_at,
                        startedAt.toRouteDateTimeLabel(stringResource(R.string.common_unknown))
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                text = stringResource(
                    R.string.route_tracking_visited_count,
                    metrics.visitedCount,
                    metrics.totalCount
                )
            )
            Text(
                text = stringResource(R.string.route_tracking_next_target, nextTargetName),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(
                    R.string.route_tracking_distance_to_next,
                    metrics.distanceToNextTargetMeters?.let(::formatDistanceMeters)
                        ?: stringResource(R.string.common_unknown)
                )
            )
            Text(
                text = stringResource(
                    R.string.route_tracking_estimated_remaining,
                    metrics.estimatedRemainingMinutes
                )
            )
            if (status == RouteSessionStatus.IN_PROGRESS && currentLocation == null) {
                Text(
                    text = stringResource(R.string.route_tracking_waiting_for_gps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (currentLocation != null) {
                Text(
                    text = stringResource(
                        R.string.route_tracking_current_location,
                        formatCoordinate(currentLocation.lat),
                        formatCoordinate(currentLocation.lon)
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (metrics.isOffRoute || isRerouting) {
                Text(
                    text = stringResource(R.string.status_off_route_title),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (isRerouting) {
                        stringResource(R.string.status_off_route_rerouting_body)
                    } else {
                        stringResource(R.string.status_off_route_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isRerouting) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp
                        )
                        Text(
                            text = stringResource(R.string.action_recalculating_route),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            Text(
                text = if (metrics.totalCount == 0) {
                    stringResource(R.string.route_tracking_no_stops)
                } else {
                    stringResource(R.string.route_tracking_auto_visit_hint, PoiVisitedRadiusMeters.toInt())
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                when {
                    canStart -> Button(
                        onClick = onStartRoute,
                        modifier = Modifier.weight(1f),
                        enabled = metrics.totalCount > 0 && canStartCurrentRoute
                    ) {
                        Text(stringResource(R.string.action_start_route))
                    }

                    canPause -> OutlinedButton(
                        onClick = onPauseRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_pause_route))
                    }

                    canResume -> OutlinedButton(
                        onClick = onResumeRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_resume_route))
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onFinishRoute,
                    modifier = Modifier.weight(1f),
                    enabled = metrics.canComplete &&
                        (status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED)
                ) {
                    Text(stringResource(R.string.action_finish_route))
                }
                OutlinedButton(
                    onClick = onCancelRoute,
                    modifier = Modifier.weight(1f),
                    enabled = status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED
                ) {
                    Text(stringResource(R.string.action_cancel_route))
                }
            }
            if (!metrics.canComplete &&
                metrics.totalCount > 0 &&
                (status == RouteSessionStatus.IN_PROGRESS || status == RouteSessionStatus.PAUSED)
            ) {
                Text(
                    text = stringResource(
                        R.string.route_tracking_finish_requirement,
                        requiredCompletionCount(metrics.totalCount),
                        metrics.totalCount
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
internal fun RoutePreviewSummaryPanel(routeResponse: RouteResponse) {
    val transportLabel = routeTransportLabel(routeResponse)

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                text = stringResource(R.string.route_summary_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryMetric(
                    label = stringResource(R.string.route_summary_metric_stops),
                    value = routeResponse.poi_count.toString(),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.route_summary_metric_total_time),
                    value = "${routeResponse.used_minutes} min",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryMetric(
                    label = stringResource(R.string.route_summary_metric_walking),
                    value = "${routeResponse.total_walk_minutes} min",
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.route_summary_metric_mode),
                    value = transportLabel,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
internal fun RouteBookmarksSheetContent(
    bookmarks: List<RouteBookmark>,
    activeBookmarkId: String?,
    onOpenBookmark: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.route_bookmarks_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_bookmarks_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (bookmarks.isEmpty()) {
            Text(
                text = stringResource(R.string.route_bookmarks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookmarks.forEach { bookmark ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bookmark.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.route_bookmark_meta,
                                            bookmark.snapshot.response.city,
                                            bookmark.snapshot.response.poi_count
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (activeBookmarkId == bookmark.id) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.route_bookmark_current),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Text(
                                text = bookmark.snapshot.response.route.take(3).joinToString(" • ") { item -> item.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { onOpenBookmark(bookmark.id) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.action_open_bookmark))
                                }
                                OutlinedButton(
                                    onClick = { onDeleteBookmark(bookmark.id) },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.action_delete_bookmark))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteHistorySheetContent(
    historyEntries: List<RouteHistoryEntry>,
    currentRouteId: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onOpenEntry: (RouteHistoryEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.route_history_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.route_history_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(onClick = onRefresh, enabled = !isLoading) {
                    Text(stringResource(R.string.action_refresh_history))
                }
            }
        }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (isLoading && historyEntries.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (historyEntries.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    historyEntries.forEach { entry ->
                        val response = entry.snapshot.response
                        val ratingLabel = entry.feedback?.rating?.let { rating ->
                            stringResource(R.string.route_history_rating_value, rating)
                        } ?: stringResource(R.string.route_history_rating_missing)
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.cityName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = entry.startedAt.toRouteDateTimeLabel(stringResource(R.string.common_unknown)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = routeSessionStatusLabel(RouteSessionStatus.fromRawValue(entry.status)),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        if (currentRouteId == entry.routeId) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.route_history_current),
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = stringResource(
                                        R.string.route_history_meta,
                                        response.poi_count,
                                        entry.usedMinutes ?: response.used_minutes,
                                        ratingLabel
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    text = response.route.take(3).joinToString(" • ") { item -> item.name },
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                Button(
                                    onClick = { onOpenEntry(entry) },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Text(stringResource(R.string.action_view_history_details))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteHistoryDetailsDialog(
    entry: RouteHistoryEntry,
    onDismiss: () -> Unit
) {
    val routeResponse = entry.snapshot.response
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(28.dp),
            tonalElevation = 6.dp
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = entry.cityName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = entry.startedAt.toRouteDateTimeLabel(stringResource(R.string.common_unknown)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = routeSessionStatusLabel(RouteSessionStatus.fromRawValue(entry.status)),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                item {
                    RoutePreviewSummaryPanel(routeResponse = routeResponse)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SummaryMetric(
                            label = stringResource(R.string.route_history_metric_visited),
                            value = entry.visitedPoiIds.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetric(
                            label = stringResource(R.string.route_history_metric_skipped),
                            value = entry.skippedPoiIds.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    RouteFeedbackSummaryCard(feedback = entry.feedback)
                }

                item {
                    Text(
                        text = stringResource(R.string.route_history_stops_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                items(routeResponse.route.size) { index ->
                    val item = routeResponse.route[index]
                    RouteStopTimelineItem(
                        item = item,
                        incomingLeg = routeResponse.legs.orEmpty().firstOrNull { leg -> leg.to.poi_id == item.poi_id },
                        isVisited = item.poi_id in entry.visitedPoiIds,
                        isSkipped = item.poi_id in entry.skippedPoiIds,
                        isNext = false,
                        isLast = index == routeResponse.route.lastIndex,
                        isRouteActive = false,
                        canSkip = false,
                        canReplace = false,
                        isActionInProgress = false,
                        onMarkVisited = {},
                        onSkip = {},
                        onReplace = {}
                    )
                }

                item {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.action_close_history_details))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteFeedbackSummaryCard(feedback: RouteFeedback?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_history_feedback_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (feedback == null || feedback.rating <= 0) {
                Text(
                    text = stringResource(R.string.route_history_feedback_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.route_history_rating_value, feedback.rating),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FeedbackSummaryRow(
                    title = stringResource(R.string.route_feedback_comfortable),
                    value = feedback.route_was_comfortable
                )
                FeedbackSummaryRow(
                    title = stringResource(R.string.route_feedback_too_much_walking),
                    value = feedback.too_much_walking
                )
                FeedbackSummaryRow(
                    title = stringResource(R.string.route_feedback_interesting_pois),
                    value = feedback.pois_were_interesting
                )
            }
        }
    }
}

@Composable
private fun FeedbackSummaryRow(
    title: String,
    value: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(if (value) R.string.state_yes else R.string.state_no),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ReplaceRouteStopSheetContent(
    targetStopName: String,
    candidates: List<PoiDto>,
    isActionInProgress: Boolean,
    onUseBestSuggestion: () -> Unit,
    onChooseCandidate: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.route_replace_stop_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_replace_stop_body, targetStopName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = onUseBestSuggestion,
            enabled = !isActionInProgress,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_replace_with_best))
        }

        if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.route_replace_candidates_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = candidates,
                    key = { poi -> poi.id }
                ) { poi ->
                    Surface(
                        shape = RoundedCornerShape(18.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = poi.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                Text(
                                    text = categoryLabel(poi.category),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            OutlinedButton(
                                onClick = { onChooseCandidate(poi.id) },
                                enabled = !isActionInProgress
                            ) {
                                Text(stringResource(R.string.action_choose_stop))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
internal fun ActiveRouteBottomPanel(
    status: RouteSessionStatus,
    metrics: RouteProgressMetrics,
    currentLocation: RouteStartDto?,
    isRerouting: Boolean,
    canSkip: Boolean,
    isActionInProgress: Boolean,
    nextTarget: RouteItemDto?,
    nextTargetIncomingLeg: RouteLegDto?,
    onMarkVisited: () -> Unit,
    onSkip: () -> Unit,
    onPauseRoute: () -> Unit,
    onResumeRoute: () -> Unit,
    onFinishRoute: () -> Unit,
    onCancelRoute: () -> Unit,
    onShowAllStops: () -> Unit,
    modifier: Modifier = Modifier
) {
    val progress = if (metrics.totalCount == 0) 0f else metrics.visitedCount.toFloat() / metrics.totalCount.toFloat()
    val canPause = status == RouteSessionStatus.IN_PROGRESS
    val canResume = status == RouteSessionStatus.PAUSED

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.route_section_next_stop),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = nextTarget?.name ?: stringResource(R.string.route_tracking_no_next_target),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (nextTarget != null) {
                        Text(
                            text = categoryLabel(nextTarget.category),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant
                ) {
                    Text(
                        text = routeSessionStatusLabel(status),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth()
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryMetric(
                    label = stringResource(R.string.route_active_progress),
                    value = "${metrics.visitedCount}/${metrics.totalCount}",
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.route_active_distance),
                    value = metrics.distanceToNextTargetMeters?.let(::formatDistanceMeters)
                        ?: stringResource(R.string.common_unknown),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.route_active_remaining),
                    value = "${metrics.estimatedRemainingMinutes} min",
                    modifier = Modifier.weight(1f)
                )
            }

            if (currentLocation == null && status == RouteSessionStatus.IN_PROGRESS) {
                Text(
                    text = stringResource(R.string.route_tracking_waiting_for_gps),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (metrics.isOffRoute || isRerouting) {
                Text(
                    text = if (isRerouting) {
                        stringResource(R.string.status_off_route_rerouting_body)
                    } else {
                        stringResource(R.string.status_off_route_body)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (nextTarget != null) {
                Text(
                    text = routeStopCompactSummary(nextTarget, nextTargetIncomingLeg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onMarkVisited,
                    enabled = nextTarget != null && !isActionInProgress && status == RouteSessionStatus.IN_PROGRESS,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_mark_visited))
                }
                OutlinedButton(
                    onClick = onSkip,
                    enabled = nextTarget != null && canSkip && !isActionInProgress,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_skip_stop))
                }
                when {
                    canPause -> OutlinedButton(
                        onClick = onPauseRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_pause_route))
                    }

                    canResume -> OutlinedButton(
                        onClick = onResumeRoute,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_resume_route))
                    }

                    else -> OutlinedButton(
                        onClick = onShowAllStops,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.action_view_all_stops))
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onShowAllStops) {
                    Text(stringResource(R.string.action_view_all_stops))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onFinishRoute,
                        enabled = metrics.canComplete
                    ) {
                        Text(stringResource(R.string.action_finish_route))
                    }
                    TextButton(onClick = onCancelRoute) {
                        Text(stringResource(R.string.action_cancel_route))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteFeedbackCard(
    feedback: RouteFeedback?,
    onFeedbackChange: (RouteFeedback) -> Unit,
    framed: Boolean = true
) {
    val currentFeedback = feedback ?: RouteFeedback(
        rating = 0,
        route_was_comfortable = false,
        too_much_walking = false,
        pois_were_interesting = false
    )

    if (framed) {
        ElevatedCard {
            RouteFeedbackContent(
                currentFeedback = currentFeedback,
                onFeedbackChange = onFeedbackChange
            )
        }
    } else {
        RouteFeedbackContent(
            currentFeedback = currentFeedback,
            onFeedbackChange = onFeedbackChange
        )
    }
}

@Composable
private fun RouteFeedbackContent(
    currentFeedback: RouteFeedback,
    onFeedbackChange: (RouteFeedback) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.route_feedback_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold
        )
        Text(
            text = stringResource(R.string.route_feedback_rating_label),
            style = MaterialTheme.typography.labelLarge
        )
        Text(
            text = stringResource(R.string.route_feedback_rating_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            (1..5).forEach { rating ->
                val selected = currentFeedback.rating == rating
                if (selected) {
                    Button(
                        onClick = {
                            onFeedbackChange(currentFeedback.copy(rating = rating))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(rating.toString())
                    }
                } else {
                    OutlinedButton(
                        onClick = {
                            onFeedbackChange(currentFeedback.copy(rating = rating))
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(rating.toString())
                    }
                }
            }
        }
        if (currentFeedback.rating > 0) {
            Text(
                text = stringResource(
                    R.string.route_feedback_rating_selected,
                    currentFeedback.rating
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        FeedbackSwitchRow(
            title = stringResource(R.string.route_feedback_comfortable),
            checked = currentFeedback.route_was_comfortable,
            onCheckedChange = { checked ->
                onFeedbackChange(currentFeedback.copy(route_was_comfortable = checked))
            }
        )
        FeedbackSwitchRow(
            title = stringResource(R.string.route_feedback_too_much_walking),
            checked = currentFeedback.too_much_walking,
            onCheckedChange = { checked ->
                onFeedbackChange(currentFeedback.copy(too_much_walking = checked))
            }
        )
        FeedbackSwitchRow(
            title = stringResource(R.string.route_feedback_interesting_pois),
            checked = currentFeedback.pois_were_interesting,
            onCheckedChange = { checked ->
                onFeedbackChange(currentFeedback.copy(pois_were_interesting = checked))
            }
        )
    }
}

@Composable
internal fun FeedbackSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium
        )
        Spacer(modifier = Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
internal fun RouteSummaryCard(routeResponse: RouteResponse) {
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
internal fun RouteStopTimelineItem(
    item: RouteItemDto,
    incomingLeg: RouteLegDto?,
    isVisited: Boolean,
    isSkipped: Boolean,
    isNext: Boolean,
    isLast: Boolean,
    isRouteActive: Boolean,
    canSkip: Boolean,
    canReplace: Boolean,
    isActionInProgress: Boolean,
    onMarkVisited: () -> Unit,
    onSkip: () -> Unit,
    onReplace: () -> Unit
) {
    var expanded by remember(item.poi_id) { mutableStateOf(isNext) }
    val statusColor = when {
        isVisited -> MaterialTheme.colorScheme.primary
        isSkipped -> MaterialTheme.colorScheme.tertiary
        isNext -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    val statusLabel = when {
        isVisited -> stringResource(R.string.route_stop_visited)
        isSkipped -> stringResource(R.string.route_stop_skipped)
        isNext -> stringResource(R.string.route_stop_status_next)
        else -> stringResource(R.string.route_stop_status_pending)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.Top
    ) {
        TimelineMarker(
            color = statusColor,
            isLast = isLast
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .clickable { expanded = !expanded }
                .animateContentSize()
                .padding(start = 4.dp, bottom = 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Surface(
                    shape = CircleShape,
                    color = statusColor.copy(alpha = 0.14f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, statusColor)
                ) {
                    Text(
                        text = item.order.toString(),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = categoryLabel(item.category),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(999.dp),
                    color = statusColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = statusLabel,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelMedium,
                        color = statusColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = routeStopCompactSummary(item, incomingLeg),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = stringResource(
                        R.string.route_stop_arrival_departure,
                        item.arrival_after_min,
                        item.departure_after_min
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                incomingLeg?.segments
                    .orEmpty()
                    .filter { segment ->
                        val mode = segment.mode.orEmpty()
                        mode == "transit" || (segment.duration_minutes ?: 0) > 0
                    }
                    .forEach { segment ->
                        Text(
                            text = routeSegmentLabel(segment),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val fromStopName = segment.from_stop_name
                        val toStopName = segment.to_stop_name
                        if (segment.mode == "transit" && !fromStopName.isNullOrBlank() && !toStopName.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.route_stop_segment_stops, fromStopName, toStopName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                if (!item.opening_hours_raw.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.route_stop_opening_hours, item.opening_hours_raw),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isVisited && !isSkipped && (canSkip || canReplace || isRouteActive)) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (canSkip) {
                            OutlinedButton(
                                onClick = onSkip,
                                enabled = !isActionInProgress,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    stringResource(
                                        if (isRouteActive) {
                                            R.string.action_skip_stop
                                        } else {
                                            R.string.action_remove_stop
                                        }
                                    )
                                )
                            }
                        }
                        if (canReplace) {
                            OutlinedButton(
                                onClick = onReplace,
                                enabled = !isActionInProgress,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.action_replace_stop))
                            }
                        }
                        if (isRouteActive) {
                            Button(
                                onClick = onMarkVisited,
                                enabled = !isActionInProgress,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(stringResource(R.string.action_mark_visited))
                            }
                        }
                    }
                }
                TextButton(
                    onClick = { expanded = false }
                ) {
                    Text(stringResource(R.string.action_hide_stop_details))
                }
            } else {
                TextButton(
                    onClick = { expanded = true }
                ) {
                    Text(stringResource(R.string.action_view_stop_details))
                }
            }
        }
    }
}

@Composable
private fun TimelineMarker(
    color: Color,
    isLast: Boolean
) {
    Canvas(
        modifier = Modifier
            .width(28.dp)
            .fillMaxHeight()
    ) {
        val centerX = size.width / 2f
        val centerY = 18.dp.toPx()
        if (!isLast) {
            drawLine(
                color = color.copy(alpha = 0.32f),
                start = androidx.compose.ui.geometry.Offset(centerX, centerY),
                end = androidx.compose.ui.geometry.Offset(centerX, size.height),
                strokeWidth = 3.dp.toPx()
            )
        }
        drawCircle(
            color = color,
            radius = 6.dp.toPx(),
            center = androidx.compose.ui.geometry.Offset(centerX, centerY)
        )
    }
}

@Composable
private fun routeStopCompactSummary(
    item: RouteItemDto,
    incomingLeg: RouteLegDto?
): String {
    val transitUsed = incomingLeg?.segments.orEmpty().any { segment ->
        segment.mode == "transit"
    }
    val travelMinutes = incomingLeg?.duration_minutes ?: item.travel_minutes_from_previous
    val baseLabel = stringResource(
        R.string.route_stop_summary_compact,
        travelMinutes,
        item.visit_duration_min
    )
    return if (transitUsed) {
        "$baseLabel • ${stringResource(R.string.route_transport_walk_mhd)}"
    } else {
        baseLabel
    }
}

@Composable
private fun routeTransportLabel(routeResponse: RouteResponse): String {
    val transitUsed = routeResponse.legs.orEmpty().any { leg ->
        leg.segments.orEmpty().any { segment -> segment.mode == "transit" }
    }
    return if (transitUsed) {
        stringResource(R.string.route_transport_walk_mhd)
    } else {
        stringResource(R.string.route_transport_walk)
    }
}

@Composable
internal fun RouteStopCard(
    item: RouteItemDto,
    incomingLeg: RouteLegDto?,
    isVisited: Boolean,
    isSkipped: Boolean,
    isRouteActive: Boolean,
    canSkip: Boolean,
    isActionInProgress: Boolean,
    onMarkVisited: () -> Unit,
    onSkip: () -> Unit
) {
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = categoryLabel(item.category),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isVisited) {
                    Text(
                        text = stringResource(R.string.route_stop_visited),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else if (isSkipped) {
                    Text(
                        text = stringResource(R.string.route_stop_skipped),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.tertiary
                    )
                } else if (canSkip || isRouteActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canSkip) {
                            OutlinedButton(
                                onClick = onSkip,
                                enabled = !isActionInProgress
                            ) {
                                Text(stringResource(R.string.action_skip_stop))
                            }
                        }
                        if (isRouteActive) {
                            OutlinedButton(
                                onClick = onMarkVisited,
                                enabled = !isActionInProgress
                            ) {
                                Text(stringResource(R.string.action_mark_visited))
                            }
                        }
                    }
                }
            }
            Text(stringResource(R.string.route_stop_walk_from_previous, item.travel_minutes_from_previous))
            incomingLeg?.segments
                .orEmpty()
                .filter { segment ->
                    val mode = segment.mode.orEmpty()
                    mode == "transit" || (segment.duration_minutes ?: 0) > 0
                }
                .forEach { segment ->
                    Text(
                        text = routeSegmentLabel(segment),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val fromStopName = segment.from_stop_name
                    val toStopName = segment.to_stop_name
                    if (segment.mode == "transit" && !fromStopName.isNullOrBlank() && !toStopName.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.route_stop_segment_stops, fromStopName, toStopName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val departureLabel = segment.departure_time.toRouteTimeOfDayLabel()
                    val arrivalLabel = segment.arrival_time.toRouteTimeOfDayLabel()
                    if (segment.mode == "transit" && departureLabel != null && arrivalLabel != null) {
                        Text(
                            text = stringResource(R.string.route_stop_segment_schedule, departureLabel, arrivalLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val waitMinutes = segment.wait_minutes_before_departure ?: 0
                    val inVehicleMinutes = segment.in_vehicle_minutes ?: 0
                    if (segment.mode == "transit" && (waitMinutes > 0 || inVehicleMinutes > 0)) {
                        Text(
                            text = stringResource(
                                R.string.route_stop_segment_wait_ride,
                                waitMinutes,
                                inVehicleMinutes
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
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
internal fun StatusCard(
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
internal fun SelectableRow(
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content
    )
}
