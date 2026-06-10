package com.example.smarttourism.features.planner.ui.formatters

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.RouteSegment
import com.example.smarttourism.features.planner.state.RouteClockFormatter
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.RouteTimeFormatter
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.Locale

@Composable
internal fun routeSegmentLabel(segment: RouteSegment): String {
    val durationMinutes = segment.durationMinutes ?: 0
    return when (segment.mode) {
        "transit" -> {
            val lineName = segment.lineName
            if (!lineName.isNullOrBlank()) {
                stringResource(R.string.route_stop_segment_transit_line, lineName, durationMinutes)
            } else {
                stringResource(R.string.route_stop_segment_transit, durationMinutes)
            }
        }

        else -> stringResource(R.string.route_stop_segment_walk, durationMinutes)
    }
}

internal fun String?.toRouteTimeOfDayLabel(): String? =
    runCatching {
        this?.toDisplayDateTime()?.format(RouteClockFormatter)
    }.getOrNull()

@Composable
internal fun categoryLabel(category: String): String =
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
internal fun paceLabel(pace: String): String =
    when (pace) {
        "slow" -> stringResource(R.string.pace_slow)
        "normal" -> stringResource(R.string.pace_normal)
        "fast" -> stringResource(R.string.pace_fast)
        else -> pace.toDisplayLabel()
    }

@Composable
internal fun routeSessionStatusLabel(status: RouteSessionStatus): String =
    when (status) {
        RouteSessionStatus.NOT_STARTED -> stringResource(R.string.route_tracking_state_ready)
        RouteSessionStatus.IN_PROGRESS -> stringResource(R.string.route_tracking_state_active)
        RouteSessionStatus.PAUSED -> stringResource(R.string.route_tracking_state_paused)
        RouteSessionStatus.COMPLETED -> stringResource(R.string.route_tracking_state_finished)
        RouteSessionStatus.CANCELLED -> stringResource(R.string.route_tracking_state_cancelled)
    }

internal fun String.toDisplayLabel(): String =
    split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

internal fun String?.toRouteDateTimeLabel(unknownLabel: String): String =
    runCatching {
        this?.toDisplayDateTime()?.format(RouteTimeFormatter)
    }.getOrNull() ?: unknownLabel

internal fun formatDistanceMeters(distanceMeters: Float): String =
    if (distanceMeters >= 1000f) {
        String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1000f)
    } else {
        String.format(Locale.getDefault(), "%.0f m", distanceMeters)
    }

internal fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)

private fun String.toDisplayDateTime(): LocalDateTime? =
    runCatching {
        OffsetDateTime.parse(this)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
    }.recoverCatching {
        LocalDateTime.parse(this)
    }.getOrNull()
