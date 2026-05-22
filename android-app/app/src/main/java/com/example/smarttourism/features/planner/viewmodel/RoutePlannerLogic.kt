package com.example.smarttourism.features.planner

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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarttourism.data.model.ActiveRouteSession
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
import com.example.smarttourism.features.map.StreetStyleUrl
import com.example.smarttourism.features.map.offline.OfflineCityRegion
import com.example.smarttourism.features.map.offline.OfflineMapManager
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

internal fun applySavedSnapshot(
    snapshot: SavedRouteSnapshot,
    onRouteRestored: (RouteResponse) -> Unit,
    onStartPointRestored: (RouteStartDto) -> Unit,
    onAvailableMinutesRestored: (Int) -> Unit,
    onPaceRestored: (String) -> Unit,
    onReturnToStartRestored: (Boolean) -> Unit,
    onRespectOpeningHoursRestored: (Boolean) -> Unit,
    onAllowPublicTransportRestored: (Boolean) -> Unit,
    onStartDateTimeRestored: (LocalDateTime) -> Unit,
    selectedInterests: MutableList<String>
) {
    onRouteRestored(snapshot.response)
    onStartPointRestored(RouteStartDto(snapshot.request.start_lat, snapshot.request.start_lon))
    onAvailableMinutesRestored(snapshot.request.available_minutes)
    onPaceRestored(snapshot.request.pace)
    onReturnToStartRestored(snapshot.request.return_to_start)
    onRespectOpeningHoursRestored(snapshot.request.respect_opening_hours)
    onAllowPublicTransportRestored(snapshot.request.transport_mode == "walk_or_mhd")
    onStartDateTimeRestored(parseRouteStartDateTime(snapshot.request.start_datetime))

    selectedInterests.clear()
    selectedInterests.addAll(snapshot.request.interests)
}

internal fun RouteSessionDto.toSavedRouteSnapshot(): SavedRouteSnapshot? {
    val response = route_snapshot_json ?: return null
    val skippedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.skipped }
        .map { poi -> poi.poi_id }
        .distinct()
    val request = RouteRequest(
        city = city_name ?: response.city,
        start_lat = start_lat,
        start_lon = start_lon,
        available_minutes = available_minutes,
        interests = response.interests,
        pace = pace,
        return_to_start = return_to_start,
        start_datetime = response.start_datetime,
        respect_opening_hours = opening_hours_enabled,
        exclude_poi_ids = skippedPoiIds,
        transport_mode = response.transport_mode ?: "walk"
    )

    return SavedRouteSnapshot(
        request = request,
        response = response
    )
}

internal fun RouteSessionDto.toRouteFeedback(): RouteFeedback? {
    val latestFeedback = feedback.orEmpty().firstOrNull() ?: return null
    return RouteFeedback(
        rating = latestFeedback.rating,
        route_was_comfortable = latestFeedback.was_convenient,
        too_much_walking = latestFeedback.too_much_walking,
        pois_were_interesting = latestFeedback.pois_were_interesting
    )
}

internal fun RouteSessionDto.toRouteHistoryEntry(): RouteHistoryEntry? {
    val snapshot = toSavedRouteSnapshot() ?: return null
    val visitedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.visited && !poi.skipped }
        .map { poi -> poi.poi_id }
        .distinct()
    val skippedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.skipped }
        .map { poi -> poi.poi_id }
        .distinct()

    return RouteHistoryEntry(
        routeId = id,
        cityName = city_name ?: snapshot.response.city,
        status = status,
        startedAt = started_at,
        finishedAt = finished_at,
        availableMinutes = available_minutes,
        usedMinutes = used_minutes ?: snapshot.response.used_minutes,
        totalWalkMinutes = total_walk_minutes ?: snapshot.response.total_walk_minutes,
        totalVisitMinutes = total_visit_minutes ?: snapshot.response.total_visit_minutes,
        snapshot = snapshot,
        visitedPoiIds = visitedPoiIds,
        skippedPoiIds = skippedPoiIds,
        feedback = toRouteFeedback(),
        updatedAtEpochMs = routeHistoryTimestamp(finished_at ?: started_at)
    )
}

internal fun buildRouteHistoryEntry(
    routeId: String,
    cityName: String,
    status: RouteSessionStatus,
    startedAt: String,
    finishedAt: String?,
    snapshot: SavedRouteSnapshot,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    feedback: RouteFeedback?,
    updatedAtEpochMs: Long = System.currentTimeMillis()
): RouteHistoryEntry =
    RouteHistoryEntry(
        routeId = routeId,
        cityName = cityName,
        status = status.rawValue,
        startedAt = startedAt,
        finishedAt = finishedAt,
        availableMinutes = snapshot.request.available_minutes,
        usedMinutes = snapshot.response.used_minutes,
        totalWalkMinutes = snapshot.response.total_walk_minutes,
        totalVisitMinutes = snapshot.response.total_visit_minutes,
        snapshot = snapshot,
        visitedPoiIds = visitedPoiIds.distinct(),
        skippedPoiIds = skippedPoiIds.distinct(),
        feedback = feedback,
        updatedAtEpochMs = updatedAtEpochMs
    )

internal fun defaultRouteBookmarkTitle(
    snapshot: SavedRouteSnapshot,
    cityName: String? = null
): String {
    val displayCity = (cityName ?: snapshot.response.city).ifBlank { snapshot.response.city }
    val leadingStops = snapshot.response.route.take(2).map { item -> item.name }
    val extraCount = (snapshot.response.route.size - leadingStops.size).coerceAtLeast(0)
    val stopLabel = buildString {
        append(leadingStops.joinToString(" • "))
        if (extraCount > 0) {
            append(" +")
            append(extraCount)
        }
    }.trim()

    return if (stopLabel.isBlank()) {
        displayCity
    } else {
        "$displayCity • $stopLabel"
    }
}

internal fun buildPreviewReplacementCandidates(
    targetPoiId: Int,
    routeItems: List<RouteItemDto>,
    pois: List<PoiDto>,
    selectedInterests: List<String>,
    excludePoiIds: List<Int>,
    limit: Int = 12
): List<PoiDto> {
    val targetItem = routeItems.firstOrNull { item -> item.poi_id == targetPoiId } ?: return emptyList()
    val blockedIds = (routeItems.map { item -> item.poi_id } + excludePoiIds + targetPoiId).toSet()
    val allowedCategories = selectedInterests.toSet()

    return pois
        .asSequence()
        .filter { poi -> poi.id !in blockedIds }
        .filter { poi -> allowedCategories.isEmpty() || poi.category in allowedCategories }
        .sortedWith(
            compareByDescending<PoiDto> { poi -> poi.category == targetItem.category }
                .thenByDescending { poi -> poi.base_score ?: 0.0 }
                .thenBy { poi -> poi.name.lowercase(Locale.getDefault()) }
        )
        .take(limit)
        .toList()
}

internal fun RouteSessionStatus.isRestorable(): Boolean =
    this == RouteSessionStatus.NOT_STARTED ||
        this == RouteSessionStatus.IN_PROGRESS ||
        this == RouteSessionStatus.PAUSED

internal fun RouteSessionStatus.isTerminal(): Boolean =
    this == RouteSessionStatus.COMPLETED || this == RouteSessionStatus.CANCELLED

internal fun CityDto.matchesToken(token: String?): Boolean {
    val normalizedToken = normalizedCityToken(token)
    return normalizedToken.isNotBlank() &&
        normalizedToken in setOf(normalizedCityToken(slug), normalizedCityToken(name))
}

private fun normalizedCityToken(value: String?): String {
    val asciiValue = Normalizer.normalize(value.orEmpty().trim(), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    return asciiValue
        .lowercase(Locale.getDefault())
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
}

internal fun CityDto.availableCategories(): List<String> =
    available_categories
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.distinct()
        .orEmpty()
        .ifEmpty { DefaultInterestCategories }

internal fun CityDto.supportsPublicTransport(): Boolean =
    transport?.mhd_enabled == true

internal fun CityDto.toStartPoint(): RouteStartDto =
    RouteStartDto(center_lat, center_lon)

internal fun CityDto.toOfflineCityRegion(): OfflineCityRegion? {
    val cityBbox = bbox ?: return null
    return OfflineCityRegion(
        slug = slug,
        name = name,
        styleUrl = StreetStyleUrl,
        south = cityBbox.south,
        west = cityBbox.west,
        north = cityBbox.north,
        east = cityBbox.east
    )
}

@Composable
internal fun routeSegmentLabel(segment: RouteSegmentDto): String {
    val durationMinutes = segment.duration_minutes ?: 0
    return when (segment.mode) {
        "transit" -> {
            val lineName = segment.line_name
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

internal fun routeProgressMetrics(
    routeResponse: RouteResponse?,
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    isTracking: Boolean
): RouteProgressMetrics {
    val visitedIds = visitedPoiIds.distinct()
    val skippedIds = skippedPoiIds.distinct()
    val nextTarget = nextPendingPoi(routeItems, visitedIds, skippedIds)
    val distanceToNextTargetMeters = if (currentLocation != null && nextTarget != null) {
        distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
    } else {
        null
    }
    val totalCount = progressTotalCount(routeItems, skippedIds)
    val visitedCount = visitedIds.size.coerceAtMost(totalCount)
    val isOffRoute = isTracking &&
        currentLocation != null &&
        nextTarget != null &&
        distanceToNextRouteSegmentMeters(routeResponse, nextTarget.poi_id, currentLocation) > OffRouteDistanceMeters

    return RouteProgressMetrics(
        visitedCount = visitedCount,
        totalCount = totalCount,
        nextTarget = nextTarget,
        distanceToNextTargetMeters = distanceToNextTargetMeters,
        estimatedRemainingMinutes = estimateRemainingMinutes(
            routeResponse = routeResponse,
            routeItems = routeItems,
            visitedPoiIds = visitedIds,
            skippedPoiIds = skippedIds,
            currentLocation = currentLocation,
            nextTarget = nextTarget
        ),
        isOffRoute = isOffRoute,
        canComplete = totalCount > 0 && visitedCount >= requiredCompletionCount(totalCount)
    )
}

internal fun progressTotalCount(
    routeItems: List<RouteItemDto>,
    skippedPoiIds: List<Int>
): Int =
    routeItems.count { item -> item.poi_id !in skippedPoiIds }

internal fun requiredCompletionCount(totalCount: Int): Int =
    ceil(totalCount / 2.0).toInt().coerceAtLeast(1)

internal fun nextPendingPoi(
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>
): RouteItemDto? =
    routeItems.firstOrNull { item -> item.poi_id !in visitedPoiIds && item.poi_id !in skippedPoiIds }

internal fun mergeReroutedRouteResponse(
    previousResponse: RouteResponse,
    reroutedResponse: RouteResponse,
    visitedPoiIds: List<Int>
): RouteResponse {
    val visitedIds = visitedPoiIds.distinct()
    if (visitedIds.isEmpty()) {
        return reroutedResponse
    }

    val visitedItems = previousResponse.route
        .filter { item -> item.poi_id in visitedIds }
        .sortedBy { item -> item.order }
    if (visitedItems.isEmpty()) {
        return reroutedResponse
    }

    val visitedElapsedMinutes = visitedItems.maxOfOrNull { item -> item.departure_after_min } ?: 0
    val renumberedVisitedItems = visitedItems.mapIndexed { index, item ->
        item.copy(order = index + 1)
    }
    val renumberedRemainingItems = reroutedResponse.route.mapIndexed { index, item ->
        item.copy(
            order = renumberedVisitedItems.size + index + 1,
            arrival_after_min = visitedElapsedMinutes + item.arrival_after_min,
            departure_after_min = visitedElapsedMinutes + item.departure_after_min
        )
    }

    val visitedLegs = previousResponse.legs
        .orEmpty()
        .filter { leg -> leg.to.poi_id in visitedIds }
        .sortedBy { leg -> leg.order }
    val renumberedVisitedLegs = visitedLegs.mapIndexed { index, leg ->
        leg.copy(order = index + 1)
    }
    val renumberedRemainingLegs = reroutedResponse.legs
        .orEmpty()
        .mapIndexed { index, leg ->
            leg.copy(order = renumberedVisitedLegs.size + index + 1)
        }

    val mergedRouteItems = renumberedVisitedItems + renumberedRemainingItems
    val mergedLegs = (renumberedVisitedLegs + renumberedRemainingLegs).ifEmpty { null }
    val mergedVisitMinutes = mergedRouteItems.sumOf { item -> item.visit_duration_min }
    val mergedUsedMinutes = visitedElapsedMinutes + reroutedResponse.used_minutes
    val mergedAvailableMinutes = maxOf(previousResponse.available_minutes, mergedUsedMinutes)
    val mergedRemainingMinutes = maxOf(0, mergedAvailableMinutes - mergedUsedMinutes)

    return reroutedResponse.copy(
        start = previousResponse.start,
        start_datetime = previousResponse.start_datetime,
        available_minutes = mergedAvailableMinutes,
        used_minutes = mergedUsedMinutes,
        remaining_minutes = mergedRemainingMinutes,
        total_visit_minutes = mergedVisitMinutes,
        total_walk_minutes = maxOf(0, mergedUsedMinutes - mergedVisitMinutes),
        poi_count = mergedRouteItems.size,
        route = mergedRouteItems,
        legs = mergedLegs,
        full_geometry = mergeLegGeometries(mergedLegs.orEmpty())
    )
}

internal fun finalizedHandledRouteResponse(
    previousResponse: RouteResponse,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>
): RouteResponse {
    val handledPoiIds = (visitedPoiIds + skippedPoiIds).distinct()
    if (handledPoiIds.isEmpty()) {
        return previousResponse.copy(
            used_minutes = 0,
            remaining_minutes = previousResponse.available_minutes,
            total_visit_minutes = 0,
            total_walk_minutes = 0,
            return_to_start_minutes = 0,
            poi_count = 0,
            route = emptyList(),
            legs = null,
            full_geometry = emptyList()
        )
    }

    val handledItems = previousResponse.route
        .filter { item -> item.poi_id in handledPoiIds }
        .sortedBy { item -> item.order }
    val handledLegs = previousResponse.legs
        .orEmpty()
        .filter { leg -> leg.to.poi_id in handledPoiIds }
        .sortedBy { leg -> leg.order }

    val renumberedItems = handledItems.mapIndexed { index, item ->
        item.copy(order = index + 1)
    }
    val renumberedLegs = handledLegs.mapIndexed { index, leg ->
        leg.copy(order = index + 1)
    }
    val usedMinutes = renumberedItems.maxOfOrNull { item -> item.departure_after_min } ?: 0
    val totalVisitMinutes = renumberedItems.sumOf { item -> item.visit_duration_min }

    return previousResponse.copy(
        used_minutes = usedMinutes,
        remaining_minutes = maxOf(0, previousResponse.available_minutes - usedMinutes),
        total_visit_minutes = totalVisitMinutes,
        total_walk_minutes = maxOf(0, usedMinutes - totalVisitMinutes),
        return_to_start_minutes = 0,
        poi_count = renumberedItems.size,
        route = renumberedItems,
        legs = renumberedLegs.ifEmpty { null },
        full_geometry = mergeLegGeometries(renumberedLegs)
    )
}

internal fun removePreviewRoutePoi(
    previousResponse: RouteResponse,
    poiId: Int,
): RouteResponse =
    rebuildPreviewRouteAfterRemovingPoi(
        previousResponse = previousResponse,
        poiId = poiId,
        replacementLegToNext = null,
        replacementReturnLeg = null
    )

internal fun rebuildPreviewRouteAfterRemovingPoi(
    previousResponse: RouteResponse,
    poiId: Int,
    replacementLegToNext: RouteLegDto?,
    replacementReturnLeg: RouteLegDto?
): RouteResponse {
    val remainingItems = previousResponse.route
        .filterNot { item -> item.poi_id == poiId }
        .sortedBy { item -> item.order }
    if (remainingItems.isEmpty()) {
        return previousResponse.copy(
            used_minutes = 0,
            remaining_minutes = previousResponse.available_minutes,
            total_visit_minutes = 0,
            total_walk_minutes = 0,
            return_to_start_minutes = 0,
            poi_count = 0,
            route = emptyList(),
            legs = null,
            full_geometry = emptyList()
        )
    }

    val existingLegs = previousResponse.legs
        .orEmpty()
        .sortedBy { leg -> leg.order }
    val replacementLegToNextPoiId = replacementLegToNext?.to?.poi_id
    val poiLegs = remainingItems.mapNotNull { item ->
        if (item.poi_id == replacementLegToNextPoiId) {
            replacementLegToNext
        } else {
            existingLegs.firstOrNull { leg -> leg.to.poi_id == item.poi_id }
        }
    }
    val lastRemainingPoiId = remainingItems.lastOrNull()?.poi_id
    val returnLeg = if (previousResponse.return_to_start) {
        replacementReturnLeg
            ?: existingLegs.firstOrNull { leg ->
                leg.to.type == "start" && leg.from.poi_id == lastRemainingPoiId
            }
    } else {
        null
    }

    val renumberedLegs = (poiLegs + listOfNotNull(returnLeg)).mapIndexed { index, leg ->
        leg.copy(order = index + 1)
    }
    var elapsedMinutes = 0
    val renumberedItems = remainingItems.mapIndexed { index, item ->
        val incomingLeg = renumberedLegs.firstOrNull { leg -> leg.to.poi_id == item.poi_id }
        val travelMinutes = incomingLeg?.duration_minutes ?: item.travel_minutes_from_previous
        elapsedMinutes += travelMinutes
        val arrivalMinutes = elapsedMinutes
        elapsedMinutes += item.visit_duration_min
        item.copy(
            order = index + 1,
            travel_minutes_from_previous = travelMinutes,
            arrival_after_min = arrivalMinutes,
            departure_after_min = elapsedMinutes
        )
    }
    val returnLegMinutes = returnLeg
        ?.takeIf { leg -> leg.to.type == "start" }
        ?.duration_minutes
        ?: 0
    val usedMinutes = elapsedMinutes + returnLegMinutes
    val totalVisitMinutes = renumberedItems.sumOf { item -> item.visit_duration_min }

    return previousResponse.copy(
        used_minutes = usedMinutes,
        remaining_minutes = maxOf(0, previousResponse.available_minutes - usedMinutes),
        total_visit_minutes = totalVisitMinutes,
        total_walk_minutes = maxOf(0, usedMinutes - totalVisitMinutes),
        return_to_start_minutes = returnLegMinutes,
        poi_count = renumberedItems.size,
        route = renumberedItems,
        legs = renumberedLegs.ifEmpty { null },
        full_geometry = mergeLegGeometries(renumberedLegs)
    )
}

internal fun replaceActiveRouteApproachLeg(
    previousResponse: RouteResponse,
    nextPoiId: Int,
    replacementLeg: RouteLegDto
): RouteResponse {
    val previousLegs = previousResponse.legs.orEmpty().sortedBy { leg -> leg.order }
    if (previousLegs.isEmpty()) {
        return previousResponse
    }

    val targetLegIndex = previousLegs.indexOfFirst { leg -> leg.to.poi_id == nextPoiId }
    if (targetLegIndex == -1) {
        return previousResponse
    }

    val targetLeg = previousLegs[targetLegIndex]
    val durationDeltaMinutes = replacementLeg.duration_minutes - targetLeg.duration_minutes
    val updatedLegs = previousLegs.toMutableList().apply {
        this[targetLegIndex] = replacementLeg.copy(
            order = targetLeg.order,
            to = targetLeg.to
        )
    }

    val targetItem = previousResponse.route.firstOrNull { item -> item.poi_id == nextPoiId }
        ?: return previousResponse.copy(
            legs = updatedLegs,
            full_geometry = mergeLegGeometries(updatedLegs)
        )
    val targetOrder = targetItem.order
    val updatedItems = previousResponse.route.map { item ->
        when {
            item.poi_id == nextPoiId -> item.copy(
                travel_minutes_from_previous = replacementLeg.duration_minutes,
                arrival_after_min = item.arrival_after_min + durationDeltaMinutes,
                departure_after_min = item.departure_after_min + durationDeltaMinutes
            )

            item.order > targetOrder -> item.copy(
                arrival_after_min = item.arrival_after_min + durationDeltaMinutes,
                departure_after_min = item.departure_after_min + durationDeltaMinutes
            )

            else -> item
        }
    }

    val returnLegMinutes = updatedLegs.lastOrNull()
        ?.takeIf { leg -> leg.to.type == "start" }
        ?.duration_minutes
        ?: 0
    val usedMinutes = (updatedItems.maxOfOrNull { item -> item.departure_after_min } ?: 0) + returnLegMinutes
    val totalVisitMinutes = updatedItems.sumOf { item -> item.visit_duration_min }

    return previousResponse.copy(
        used_minutes = usedMinutes,
        remaining_minutes = maxOf(0, previousResponse.available_minutes - usedMinutes),
        total_visit_minutes = totalVisitMinutes,
        total_walk_minutes = maxOf(0, usedMinutes - totalVisitMinutes),
        route = updatedItems,
        legs = updatedLegs,
        full_geometry = mergeLegGeometries(updatedLegs)
    )
}

internal fun mergeLegGeometries(legs: List<RouteLegDto>): List<com.example.smarttourism.data.remote.dto.RouteCoordinateDto> {
    if (legs.isEmpty()) {
        return emptyList()
    }

    val mergedGeometry = mutableListOf<com.example.smarttourism.data.remote.dto.RouteCoordinateDto>()
    legs.forEach { leg ->
        val geometry = leg.geometry
        if (geometry.isEmpty()) {
            return@forEach
        }

        if (mergedGeometry.isEmpty()) {
            mergedGeometry.addAll(geometry)
        } else {
            mergedGeometry.addAll(geometry.drop(1))
        }
    }
    return mergedGeometry
}

internal fun estimateRemainingMinutes(
    routeResponse: RouteResponse?,
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    nextTarget: RouteItemDto?
): Int {
    val remainingItems = routeItems.filter { item ->
        item.poi_id !in visitedPoiIds && item.poi_id !in skippedPoiIds
    }
    if (remainingItems.isEmpty()) {
        return 0
    }

    val firstWalkMinutes = if (currentLocation != null && nextTarget != null) {
        val distanceMeters = distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
        estimateWalkingMinutes(distanceMeters, routeResponse?.pace)
    } else {
        remainingItems.first().travel_minutes_from_previous
    }
    val remainingVisits = remainingItems.sumOf { item -> item.visit_duration_min }
    val remainingWalksAfterTarget = remainingItems.drop(1).sumOf { item ->
        item.travel_minutes_from_previous
    }
    val returnToStartMinutes = if (routeResponse?.return_to_start == true) {
        routeResponse.return_to_start_minutes
    } else {
        0
    }

    return firstWalkMinutes + remainingVisits + remainingWalksAfterTarget + returnToStartMinutes
}

internal fun rerouteStartPoint(
    routeItems: List<RouteItemDto>,
    visitedPoiIds: List<Int>,
    currentLocation: RouteStartDto?,
    fallbackStart: RouteStartDto
): RouteStartDto {
    currentLocation?.let { return it }

    val lastVisitedStop = routeItems
        .filter { item -> item.poi_id in visitedPoiIds }
        .maxByOrNull { item -> item.order }

    return lastVisitedStop?.let { stop ->
        RouteStartDto(lat = stop.lat, lon = stop.lon)
    } ?: fallbackStart
}

internal fun estimateWalkingMinutes(
    distanceMeters: Float,
    pace: String?
): Int {
    val speedMetersPerMinute = when (pace) {
        "slow" -> 4_000.0 / 60.0
        "fast" -> 5_600.0 / 60.0
        else -> 4_800.0 / 60.0
    }
    return maxOf(1, ceil(distanceMeters / speedMetersPerMinute).toInt())
}

internal fun distanceToNextRouteSegmentMeters(
    routeResponse: RouteResponse?,
    nextTargetPoiId: Int,
    currentLocation: RouteStartDto
): Float {
    val legGeometry = routeResponse
        ?.legs
        .orEmpty()
        .firstOrNull { leg -> leg.to.poi_id == nextTargetPoiId }
        ?.geometry
        .orEmpty()

    if (legGeometry.size < 2) {
        val nextTarget = routeResponse
            ?.route
            .orEmpty()
            .firstOrNull { item -> item.poi_id == nextTargetPoiId }
            ?: return 0f

        return distanceMeters(
            startLat = currentLocation.lat,
            startLon = currentLocation.lon,
            endLat = nextTarget.lat,
            endLon = nextTarget.lon
        )
    }

    return legGeometry
        .zipWithNext()
        .minOf { (start, end) ->
            distanceToSegmentMeters(
                point = currentLocation,
                startLat = start.lat,
                startLon = start.lon,
                endLat = end.lat,
                endLon = end.lon
            )
        }
}

internal fun distanceToSegmentMeters(
    point: RouteStartDto,
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double
): Float {
    val segmentDistance = distanceMeters(startLat, startLon, endLat, endLon)
    if (segmentDistance == 0f) {
        return distanceMeters(point.lat, point.lon, startLat, startLon)
    }

    val referenceLatRadians = Math.toRadians(point.lat)
    val startX = longitudeToMeters(startLon - point.lon, referenceLatRadians)
    val startY = latitudeToMeters(startLat - point.lat)
    val endX = longitudeToMeters(endLon - point.lon, referenceLatRadians)
    val endY = latitudeToMeters(endLat - point.lat)
    val segmentX = endX - startX
    val segmentY = endY - startY
    val segmentLengthSquared = segmentX * segmentX + segmentY * segmentY
    val projectionRatio = if (segmentLengthSquared == 0.0) {
        0.0
    } else {
        ((-startX * segmentX + -startY * segmentY) / segmentLengthSquared).coerceIn(0.0, 1.0)
    }
    val projectedX = startX + segmentX * projectionRatio
    val projectedY = startY + segmentY * projectionRatio

    return kotlin.math.sqrt(projectedX * projectedX + projectedY * projectedY).toFloat()
}

internal fun latitudeToMeters(latitudeDelta: Double): Double =
    latitudeDelta * 111_320.0

internal fun longitudeToMeters(
    longitudeDelta: Double,
    referenceLatRadians: Double
): Double =
    longitudeDelta * 111_320.0 * kotlin.math.cos(referenceLatRadians)

internal fun fetchCurrentLocation(
    context: Context,
    onSuccess: (Double, Double) -> Unit,
    onError: (String) -> Unit
) {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError(context.getString(R.string.error_location_service_unavailable))
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
            onError(context.getString(R.string.error_location_permission_missing))
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
                onError(context.getString(R.string.error_no_location_provider_enabled))
            }
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            locationManager.getCurrentLocation(provider, null, context.mainExecutor) { location ->
                when {
                    location != null -> onSuccess(location.latitude, location.longitude)
                    lastKnownLocation != null -> onSuccess(lastKnownLocation.latitude, lastKnownLocation.longitude)
                    else -> onError(context.getString(R.string.error_current_location_unavailable))
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
                        onError(context.getString(R.string.error_no_location_provider_enabled))
                    }
                    locationManager.removeUpdates(this)
                }
            },
            Looper.getMainLooper()
        )
    } catch (securityException: SecurityException) {
        onError(context.getString(R.string.error_location_permission_missing))
    }
}

internal fun startRouteLocationTracking(
    context: Context,
    onLocation: (Location) -> Unit,
    onError: (String) -> Unit
): () -> Unit {
    try {
        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        if (locationManager == null) {
            onError(context.getString(R.string.error_location_service_unavailable))
            return {}
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
            onError(context.getString(R.string.error_location_permission_missing))
            return {}
        }

        val providers = buildList {
            if (hasFinePermission && locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                add(LocationManager.GPS_PROVIDER)
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                add(LocationManager.NETWORK_PROVIDER)
            }
        }.distinct()

        if (providers.isEmpty()) {
            onError(context.getString(R.string.error_no_location_provider_enabled))
            return {}
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                onLocation(location)
            }

            override fun onProviderDisabled(provider: String) {
                val hasEnabledProvider = providers.any { enabledProvider ->
                    runCatching {
                        locationManager.isProviderEnabled(enabledProvider)
                    }.getOrDefault(false)
                }

                if (!hasEnabledProvider) {
                    onError(context.getString(R.string.error_no_location_provider_enabled))
                }
            }
        }

        providers.forEach { provider ->
            locationManager.requestLocationUpdates(
                provider,
                RouteTrackingMinTimeMs,
                RouteTrackingMinDistanceMeters,
                listener,
                Looper.getMainLooper()
            )
        }

        providers
            .mapNotNull { provider ->
                runCatching {
                    locationManager.getLastKnownLocation(provider)
                }.getOrNull()
            }
            .maxByOrNull { location -> location.time }
            ?.let(onLocation)

        val stopTracking = {
            locationManager.removeUpdates(listener)
        }

        return stopTracking
    } catch (securityException: SecurityException) {
        onError(context.getString(R.string.error_location_permission_missing))
        return {}
    }
}

internal fun markNearbyPoisVisited(
    routeItems: List<RouteItemDto>,
    currentLocation: RouteStartDto,
    visitedPoiIds: MutableList<Int>,
    skippedPoiIds: List<Int>
): List<Int> {
    val newlyVisitedIds = routeItems
        .filter { item -> item.poi_id !in visitedPoiIds && item.poi_id !in skippedPoiIds }
        .filter { item ->
            distanceMeters(
                startLat = currentLocation.lat,
                startLon = currentLocation.lon,
                endLat = item.lat,
                endLon = item.lon
            ) <= PoiVisitedRadiusMeters
        }
        .map { item -> item.poi_id }

    visitedPoiIds.addAll(newlyVisitedIds)
    return newlyVisitedIds
}

internal fun distanceMeters(
    startLat: Double,
    startLon: Double,
    endLat: Double,
    endLon: Double
): Float {
    val result = FloatArray(1)
    Location.distanceBetween(startLat, startLon, endLat, endLon, result)
    return result[0]
}

internal fun defaultRouteStartDateTime(): LocalDateTime =
    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)

internal fun parseRouteStartDateTime(rawValue: String?): LocalDateTime =
    rawValue?.toDisplayDateTime()?.truncatedTo(ChronoUnit.MINUTES) ?: defaultRouteStartDateTime()

internal fun String.toDisplayLabel(): String =
    split('_').joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase() }
    }

internal fun String?.toRouteDateTimeLabel(unknownLabel: String): String =
    runCatching {
        this?.toDisplayDateTime()?.format(RouteTimeFormatter)
    }.getOrNull() ?: unknownLabel

internal fun Throwable.toUserMessage(defaultMessage: String): String {
    val rawMessage = message?.substringBefore('\n')?.trim()
    return if (rawMessage.isNullOrEmpty()) defaultMessage else rawMessage
}

internal fun formatDistanceMeters(distanceMeters: Float): String =
    if (distanceMeters >= 1000f) {
        String.format(Locale.getDefault(), "%.1f km", distanceMeters / 1000f)
    } else {
        String.format(Locale.getDefault(), "%.0f m", distanceMeters)
    }

internal fun formatCoordinate(value: Double): String =
    String.format("%.5f", value)

internal fun routeHistoryTimestamp(rawValue: String?): Long =
    runCatching {
        rawValue?.toInstantEpochMillis()
    }.getOrNull() ?: System.currentTimeMillis()

private fun String.toDisplayDateTime(): LocalDateTime? =
    runCatching {
        OffsetDateTime.parse(this)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
    }.recoverCatching {
        LocalDateTime.parse(this)
    }.getOrNull()

private fun String.toInstantEpochMillis(): Long? =
    runCatching {
        OffsetDateTime.parse(this).toInstant().toEpochMilli()
    }.recoverCatching {
        LocalDateTime.parse(this)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
