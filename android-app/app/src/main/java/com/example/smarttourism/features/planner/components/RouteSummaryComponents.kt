package com.example.smarttourism.features.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel
import com.example.smarttourism.features.planner.ui.formatters.formatDistanceMeters
import com.example.smarttourism.features.planner.ui.formatters.routeSessionStatusLabel

@Composable
internal fun RoutePreviewSummaryPanel(routeResponse: RoutePlan) {
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
                    value = routeResponse.poiCount.toString(),
                    modifier = Modifier.weight(1f)
                )
                SummaryMetric(
                    label = stringResource(R.string.route_summary_metric_total_time),
                    value = "${routeResponse.usedMinutes} min",
                    modifier = Modifier.weight(1f)
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SummaryMetric(
                    label = stringResource(R.string.route_summary_metric_walking),
                    value = "${routeResponse.totalWalkMinutes} min",
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
internal fun SummaryMetric(
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
    currentLocation: RoutePoint?,
    isRerouting: Boolean,
    canSkip: Boolean,
    isActionInProgress: Boolean,
    nextTarget: RouteStop?,
    nextTargetIncomingLeg: RouteLeg?,
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
