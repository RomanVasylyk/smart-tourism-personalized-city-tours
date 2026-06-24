package com.example.smarttourism.features.planner.components

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.state.AvailableMinutesStepMinutes
import com.example.smarttourism.features.planner.state.MinimumAvailableMinutes
import com.example.smarttourism.features.planner.state.OfflineDownloadProgress
import com.example.smarttourism.features.planner.state.PaceOptions
import com.example.smarttourism.features.planner.state.RouteTimeFormatter
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel
import com.example.smarttourism.features.planner.ui.formatters.formatCoordinate
import com.example.smarttourism.features.planner.ui.formatters.paceLabel
import java.time.LocalDateTime
import kotlin.math.roundToInt

@Composable
internal fun OfflineSupportCard(
    selectedCity: City?,
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
    cities: List<City>,
    selectedCity: City?,
    enabled: Boolean = true,
    onCitySelected: (City) -> Unit
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
    startPoint: RoutePoint,
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
