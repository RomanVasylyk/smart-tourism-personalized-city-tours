package com.example.smarttourism.features.planner

import com.example.smarttourism.data.remote.dto.RouteItemDto
import com.example.smarttourism.data.remote.dto.RouteStartDto
import java.time.format.DateTimeFormatter
import java.util.Locale

internal const val DefaultCitySlug = "nitra"
internal val EmptyStartPoint = RouteStartDto(lat = 48.3076, lon = 18.0845)
internal val AvailableMinutesOptions = listOf(120, 180, 240)
internal val DefaultInterestCategories = listOf(
    "attraction",
    "museum",
    "gallery",
    "viewpoint",
    "monument",
    "historical_site",
    "park",
    "religious_site"
)
internal val PaceOptions = listOf("slow", "normal", "fast")
internal val RouteTimeFormatter = DateTimeFormatter.ofPattern("EEE, MMM d HH:mm", Locale.getDefault())
internal val RouteClockFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
internal const val RouteTrackingMinTimeMs = 5_000L
internal const val RouteTrackingMinDistanceMeters = 5f
internal const val PoiVisitedRadiusMeters = 60f
internal const val OffRouteDistanceMeters = 120f
internal const val OffRouteSustainDurationMs = 10_000L
internal const val AutoRerouteCooldownMs = 20_000L

internal enum class RouteSessionStatus(val rawValue: String) {
    NOT_STARTED("not_started"),
    IN_PROGRESS("in_progress"),
    PAUSED("paused"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    companion object {
        fun fromRawValue(rawValue: String?): RouteSessionStatus =
            entries.firstOrNull { status -> status.rawValue == rawValue } ?: NOT_STARTED
    }
}

internal enum class TrackingPermissionAction {
    START,
    RESUME
}

internal data class RouteProgressMetrics(
    val visitedCount: Int,
    val totalCount: Int,
    val nextTarget: RouteItemDto?,
    val distanceToNextTargetMeters: Float?,
    val estimatedRemainingMinutes: Int,
    val isOffRoute: Boolean,
    val canComplete: Boolean
)

internal data class OfflineDownloadProgress(
    val completed: Long,
    val required: Long,
    val percent: Double
)
