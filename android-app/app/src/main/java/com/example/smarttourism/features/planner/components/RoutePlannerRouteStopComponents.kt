package com.example.smarttourism.features.planner.components

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
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.repository.OfflineCacheRepository
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSegment
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.data.repository.RouteStorage
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.sync.OfflineSyncScheduler
import com.example.smarttourism.R
import com.example.smarttourism.features.map.offline.OfflineCityRegion
import com.example.smarttourism.features.map.offline.OfflineMapManager
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import com.example.smarttourism.features.planner.state.AvailableMinutesStepMinutes
import com.example.smarttourism.features.planner.state.MinimumAvailableMinutes
import com.example.smarttourism.features.planner.state.OfflineDownloadProgress
import com.example.smarttourism.features.planner.state.PaceOptions
import com.example.smarttourism.features.planner.state.PoiVisitedRadiusMeters
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.RouteTimeFormatter
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel
import com.example.smarttourism.features.planner.ui.formatters.formatCoordinate
import com.example.smarttourism.features.planner.ui.formatters.formatDistanceMeters
import com.example.smarttourism.features.planner.ui.formatters.paceLabel
import com.example.smarttourism.features.planner.domain.route.requiredCompletionCount
import com.example.smarttourism.features.planner.ui.formatters.routeSegmentLabel
import com.example.smarttourism.features.planner.ui.formatters.routeSessionStatusLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteDateTimeLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteTimeOfDayLabel
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
internal fun RouteSummaryCard(routeResponse: RoutePlan) {
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
                    routeResponse.startDateTime.toRouteDateTimeLabel(stringResource(R.string.common_unknown))
                )
            )
            Text(stringResource(R.string.route_summary_pace, paceLabel(routeResponse.pace)))
            Text(stringResource(R.string.route_summary_stops, routeResponse.poiCount))
            Text(
                stringResource(
                    R.string.route_summary_used_time,
                    routeResponse.usedMinutes,
                    routeResponse.availableMinutes
                )
            )
            Text(stringResource(R.string.route_summary_walking, routeResponse.totalWalkMinutes))
            Text(stringResource(R.string.route_summary_visits, routeResponse.totalVisitMinutes))
            Text(stringResource(R.string.route_summary_remaining, routeResponse.remainingMinutes))
            Text(stringResource(R.string.route_summary_return_to_start, routeResponse.returnToStartMinutes))
            Text(
                stringResource(
                    R.string.route_summary_opening_hours_filter,
                    if (routeResponse.respectOpeningHours) {
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
    item: RouteStop,
    incomingLeg: RouteLeg?,
    isVisited: Boolean,
    isSkipped: Boolean,
    isNext: Boolean,
    isLast: Boolean,
    isRouteActive: Boolean,
    canSkip: Boolean,
    canReplace: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isActionInProgress: Boolean,
    onMarkVisited: () -> Unit,
    onSkip: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onReplace: () -> Unit
) {
    var expanded by remember(item.poiId) { mutableStateOf(isNext) }
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
                        item.arrivalAfterMin,
                        item.departureAfterMin
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                incomingLeg?.segments
                    .orEmpty()
                    .filter { segment ->
                        val mode = segment.mode.orEmpty()
                        mode == "transit" || (segment.durationMinutes ?: 0) > 0
                    }
                    .forEach { segment ->
                        Text(
                            text = routeSegmentLabel(segment),
                            style = MaterialTheme.typography.bodySmall
                        )
                        val fromStopName = segment.fromStopName
                        val toStopName = segment.toStopName
                        if (segment.mode == "transit" && !fromStopName.isNullOrBlank() && !toStopName.isNullOrBlank()) {
                            Text(
                                text = stringResource(R.string.route_stop_segment_stops, fromStopName, toStopName),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                if (!item.openingHoursRaw.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.route_stop_opening_hours, item.openingHoursRaw),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!isVisited && !isSkipped && (canMoveUp || canMoveDown)) {
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = onMoveUp,
                            enabled = canMoveUp && !isActionInProgress,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_move_up))
                        }
                        OutlinedButton(
                            onClick = onMoveDown,
                            enabled = canMoveDown && !isActionInProgress,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(stringResource(R.string.action_move_down))
                        }
                    }
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
internal fun routeStopCompactSummary(
    item: RouteStop,
    incomingLeg: RouteLeg?
): String {
    val transitUsed = incomingLeg?.segments.orEmpty().any { segment ->
        segment.mode == "transit"
    }
    val travelMinutes = incomingLeg?.durationMinutes ?: item.travelMinutesFromPrevious
    val baseLabel = stringResource(
        R.string.route_stop_summary_compact,
        travelMinutes,
        item.visitDurationMin
    )
    return if (transitUsed) {
        "$baseLabel • ${stringResource(R.string.route_transport_walk_mhd)}"
    } else {
        baseLabel
    }
}

@Composable
internal fun routeTransportLabel(routeResponse: RoutePlan): String {
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
    item: RouteStop,
    incomingLeg: RouteLeg?,
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
            Text(stringResource(R.string.route_stop_walk_from_previous, item.travelMinutesFromPrevious))
            incomingLeg?.segments
                .orEmpty()
                .filter { segment ->
                    val mode = segment.mode.orEmpty()
                    mode == "transit" || (segment.durationMinutes ?: 0) > 0
                }
                .forEach { segment ->
                    Text(
                        text = routeSegmentLabel(segment),
                        style = MaterialTheme.typography.bodyMedium
                    )
                    val fromStopName = segment.fromStopName
                    val toStopName = segment.toStopName
                    if (segment.mode == "transit" && !fromStopName.isNullOrBlank() && !toStopName.isNullOrBlank()) {
                        Text(
                            text = stringResource(R.string.route_stop_segment_stops, fromStopName, toStopName),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val departureLabel = segment.departureTime.toRouteTimeOfDayLabel()
                    val arrivalLabel = segment.arrivalTime.toRouteTimeOfDayLabel()
                    if (segment.mode == "transit" && departureLabel != null && arrivalLabel != null) {
                        Text(
                            text = stringResource(R.string.route_stop_segment_schedule, departureLabel, arrivalLabel),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    val waitMinutes = segment.waitMinutesBeforeDeparture ?: 0
                    val inVehicleMinutes = segment.inVehicleMinutes ?: 0
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
            Text(stringResource(R.string.route_stop_visit_duration, item.visitDurationMin))
            Text(stringResource(R.string.route_stop_arrival_after_start, item.arrivalAfterMin))
            Text(stringResource(R.string.route_stop_departure_after_start, item.departureAfterMin))
            if (!item.openingHoursRaw.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.route_stop_opening_hours, item.openingHoursRaw),
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
