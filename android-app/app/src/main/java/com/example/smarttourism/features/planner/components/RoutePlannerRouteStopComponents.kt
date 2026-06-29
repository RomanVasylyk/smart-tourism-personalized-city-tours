package com.example.smarttourism.features.planner.components

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSegment
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.R
import com.example.smarttourism.features.map.TransitLineColors
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel
import com.example.smarttourism.features.planner.ui.formatters.formatCoordinate
import com.example.smarttourism.features.planner.ui.formatters.paceLabel
import com.example.smarttourism.features.planner.ui.formatters.routeSegmentLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteDateTimeLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteTimeOfDayLabel

@Composable
internal fun RouteSummaryCard(routeResponse: RoutePlan) {
    ElevatedCard(shape = RoundedCornerShape(PlannerUiTokens.CardRadius)) {
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
                .clip(RoundedCornerShape(PlannerUiTokens.CardRadius))
                .clickable(
                    role = Role.Button,
                    onClick = { expanded = !expanded }
                )
                .semantics {
                    contentDescription = "$statusLabel, ${item.name}"
                    role = Role.Button
                }
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
                    border = BorderStroke(1.dp, statusColor)
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
            RouteStopChipRow(
                item = item,
                incomingLeg = incomingLeg
            )

            if (expanded) {
                Spacer(modifier = Modifier.height(10.dp))
                RouteTimelineChip(
                    label = stringResource(
                        R.string.route_stop_time_window_badge,
                        item.arrivalAfterMin,
                        item.departureAfterMin
                    ),
                    color = MaterialTheme.colorScheme.secondary
                )
                incomingLeg?.segments
                    .orEmpty()
                    .filter { segment ->
                        val mode = segment.mode.orEmpty()
                        mode == "transit" || (segment.durationMinutes ?: 0) > 0
                    }
                    .forEach { segment ->
                        RouteSegmentDetail(
                            segment = segment,
                            fallbackLabel = routeSegmentLabel(segment)
                        )
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
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = PlannerUiTokens.ButtonHeight)
                        ) {
                            Text(stringResource(R.string.action_move_up))
                        }
                        OutlinedButton(
                            onClick = onMoveDown,
                            enabled = canMoveDown && !isActionInProgress,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = PlannerUiTokens.ButtonHeight)
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
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = PlannerUiTokens.ButtonHeight)
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
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = PlannerUiTokens.ButtonHeight)
                            ) {
                                Text(stringResource(R.string.action_replace_stop))
                            }
                        }
                        if (isRouteActive) {
                            Button(
                                onClick = onMarkVisited,
                                enabled = !isActionInProgress,
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = PlannerUiTokens.ButtonHeight)
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
private fun RouteStopChipRow(
    item: RouteStop,
    incomingLeg: RouteLeg?
) {
    val transitSegment = incomingLeg?.segments.orEmpty().firstOrNull { segment -> segment.mode == "transit" }
    val travelMinutes = incomingLeg?.durationMinutes ?: item.travelMinutesFromPrevious

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RouteTimelineChip(
                label = stringResource(R.string.route_stop_walk_badge, travelMinutes),
                color = MaterialTheme.colorScheme.primary
            )
            RouteTimelineChip(
                label = stringResource(R.string.route_stop_visit_badge, item.visitDurationMin),
                color = MaterialTheme.colorScheme.tertiary
            )
        }
        if (transitSegment != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RouteTimelineChip(
                    label = transitSegment.lineName
                        ?.takeIf { it.isNotBlank() }
                        ?.let { stringResource(R.string.route_stop_bus_line_badge, it, transitSegment.durationMinutes ?: 0) }
                        ?: stringResource(R.string.route_stop_bus_badge, transitSegment.durationMinutes ?: 0),
                    color = transitLineComposeColor(transitSegment.lineName)
                )
            }
        }
    }
}

@Composable
private fun RouteSegmentDetail(
    segment: RouteSegment,
    fallbackLabel: String
) {
    val accent = if (segment.mode == "transit") {
        transitLineComposeColor(segment.lineName)
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        RouteTimelineChip(
            label = if (segment.mode == "transit") {
                segment.lineName
                    ?.takeIf { it.isNotBlank() }
                    ?.let { stringResource(R.string.route_stop_bus_line_badge, it, segment.durationMinutes ?: 0) }
                    ?: stringResource(R.string.route_stop_bus_badge, segment.durationMinutes ?: 0)
            } else {
                fallbackLabel
            },
            color = accent
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
                text = stringResource(R.string.route_stop_segment_wait_ride, waitMinutes, inVehicleMinutes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun RouteTimelineChip(
    label: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.defaultMinSize(minHeight = 32.dp),
        shape = RoundedCornerShape(999.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.5f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
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
            .defaultMinSize(minHeight = 48.dp)
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

private fun transitLineComposeColor(lineName: String?): Color {
    val key = lineName?.takeIf { it.isNotBlank() } ?: "transit"
    var hash = 0
    key.forEach { character ->
        hash = (hash * 31) + character.code
    }
    val colorValue = TransitLineColors[(hash and Int.MAX_VALUE) % TransitLineColors.size]
    return Color(android.graphics.Color.parseColor(colorValue))
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
    Card(shape = RoundedCornerShape(PlannerUiTokens.CardRadius)) {
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
                                enabled = !isActionInProgress,
                                modifier = Modifier.heightIn(min = PlannerUiTokens.ButtonHeight)
                            ) {
                                Text(stringResource(R.string.action_skip_stop))
                            }
                        }
                        if (isRouteActive) {
                            OutlinedButton(
                                onClick = onMarkVisited,
                                enabled = !isActionInProgress,
                                modifier = Modifier.heightIn(min = PlannerUiTokens.ButtonHeight)
                            ) {
                                Text(stringResource(R.string.action_mark_visited))
                            }
                        }
                    }
                }
            }
            RouteStopChipRow(
                item = item,
                incomingLeg = incomingLeg
            )
            incomingLeg?.segments
                .orEmpty()
                .filter { segment ->
                    val mode = segment.mode.orEmpty()
                    mode == "transit" || (segment.durationMinutes ?: 0) > 0
                }
                .forEach { segment ->
                    RouteSegmentDetail(
                        segment = segment,
                        fallbackLabel = routeSegmentLabel(segment)
                    )
                }
            RouteTimelineChip(
                label = stringResource(R.string.route_stop_time_window_badge, item.arrivalAfterMin, item.departureAfterMin),
                color = MaterialTheme.colorScheme.secondary
            )
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
    body: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null
) {
    ElevatedCard(shape = RoundedCornerShape(PlannerUiTokens.CardRadius)) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
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
            if ((actionLabel != null && onAction != null) || (secondaryActionLabel != null && onSecondaryAction != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    if (secondaryActionLabel != null && onSecondaryAction != null) {
                        OutlinedButton(
                            onClick = onSecondaryAction,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = PlannerUiTokens.ButtonHeight)
                        ) {
                            Text(secondaryActionLabel)
                        }
                    }
                    if (actionLabel != null && onAction != null) {
                        Button(
                            onClick = onAction,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = PlannerUiTokens.ButtonHeight)
                        ) {
                            Text(actionLabel)
                        }
                    }
                }
            }
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
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .defaultMinSize(minHeight = PlannerUiTokens.ButtonHeight)
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
        content = content
    )
}
