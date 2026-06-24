package com.example.smarttourism.features.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.route.requiredCompletionCount
import com.example.smarttourism.features.planner.state.PoiVisitedRadiusMeters
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.ui.formatters.formatCoordinate
import com.example.smarttourism.features.planner.ui.formatters.formatDistanceMeters
import com.example.smarttourism.features.planner.ui.formatters.routeSessionStatusLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteDateTimeLabel

@Composable
internal fun RouteTrackingCard(
    status: RouteSessionStatus,
    routeId: String?,
    startedAt: String?,
    metrics: RouteProgressMetrics,
    currentLocation: RoutePoint?,
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
