package com.example.smarttourism.features.planner.viewmodel

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
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RouteCoordinate
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSegment
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.R
import com.example.smarttourism.features.map.StreetStyleUrl
import com.example.smarttourism.features.map.offline.OfflineCityRegion
import com.example.smarttourism.features.planner.state.DefaultInterestCategories
import com.example.smarttourism.features.planner.state.OffRouteDistanceMeters
import com.example.smarttourism.features.planner.state.PoiVisitedRadiusMeters
import com.example.smarttourism.features.planner.state.RouteClockFormatter
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.RouteTimeFormatter
import com.example.smarttourism.features.planner.state.RouteTrackingMinDistanceMeters
import com.example.smarttourism.features.planner.state.RouteTrackingMinTimeMs
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
    onRouteRestored: (RoutePlan) -> Unit,
    onStartPointRestored: (RoutePoint) -> Unit,
    onAvailableMinutesRestored: (Int) -> Unit,
    onPaceRestored: (String) -> Unit,
    onReturnToStartRestored: (Boolean) -> Unit,
    onRespectOpeningHoursRestored: (Boolean) -> Unit,
    onAllowPublicTransportRestored: (Boolean) -> Unit,
    onStartDateTimeRestored: (LocalDateTime) -> Unit,
    selectedInterests: MutableList<String>
) {
    onRouteRestored(snapshot.response)
    onStartPointRestored(RoutePoint(snapshot.request.startLat, snapshot.request.startLon))
    onAvailableMinutesRestored(snapshot.request.availableMinutes)
    onPaceRestored(snapshot.request.pace)
    onReturnToStartRestored(snapshot.request.returnToStart)
    onRespectOpeningHoursRestored(snapshot.request.respectOpeningHours)
    onAllowPublicTransportRestored(snapshot.request.transportMode == "walk_or_mhd")
    onStartDateTimeRestored(parseRouteStartDateTime(snapshot.request.startDateTime))

    selectedInterests.clear()
    selectedInterests.addAll(snapshot.request.interests)
}

internal fun RouteSession.toSavedRouteSnapshot(): SavedRouteSnapshot? {
    val response = routeSnapshot ?: return null
    val skippedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.skipped }
        .map { poi -> poi.poiId }
        .distinct()
    val request = PlannerPreferences(
        city = cityName ?: response.city,
        startLat = startLat,
        startLon = startLon,
        availableMinutes = availableMinutes,
        interests = response.interests,
        pace = pace,
        returnToStart = returnToStart,
        startDateTime = response.startDateTime,
        respectOpeningHours = openingHoursEnabled,
        excludedPoiIds = skippedPoiIds,
        transportMode = response.transportMode ?: "walk"
    )

    return SavedRouteSnapshot(
        request = request,
        response = response
    )
}

internal fun RouteSession.toRouteFeedback(): RouteFeedback? {
    val latestFeedback = feedback.orEmpty().firstOrNull() ?: return null
    return RouteFeedback(
        rating = latestFeedback.rating,
        route_was_comfortable = latestFeedback.wasConvenient ?: false,
        too_much_walking = latestFeedback.tooMuchWalking ?: false,
        pois_were_interesting = latestFeedback.poisWereInteresting ?: false
    )
}

internal fun RouteSession.toRouteHistoryEntry(): RouteHistoryEntry? {
    val snapshot = toSavedRouteSnapshot() ?: return null
    val visitedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.visited && !poi.skipped }
        .map { poi -> poi.poiId }
        .distinct()
    val skippedPoiIds = pois
        .orEmpty()
        .filter { poi -> poi.skipped }
        .map { poi -> poi.poiId }
        .distinct()

    return RouteHistoryEntry(
        routeId = id,
        cityName = cityName ?: snapshot.response.city,
        status = status,
        startedAt = startedAt,
        finishedAt = finishedAt,
        availableMinutes = availableMinutes,
        usedMinutes = usedMinutes ?: snapshot.response.usedMinutes,
        totalWalkMinutes = totalWalkMinutes ?: snapshot.response.totalWalkMinutes,
        totalVisitMinutes = totalVisitMinutes ?: snapshot.response.totalVisitMinutes,
        snapshot = snapshot,
        visitedPoiIds = visitedPoiIds,
        skippedPoiIds = skippedPoiIds,
        feedback = toRouteFeedback(),
        updatedAtEpochMs = routeHistoryTimestamp(finishedAt ?: startedAt)
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
        availableMinutes = snapshot.request.availableMinutes,
        usedMinutes = snapshot.response.usedMinutes,
        totalWalkMinutes = snapshot.response.totalWalkMinutes,
        totalVisitMinutes = snapshot.response.totalVisitMinutes,
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
    routeItems: List<RouteStop>,
    pois: List<Poi>,
    selectedInterests: List<String>,
    excludePoiIds: List<Int>,
    limit: Int = 12
): List<Poi> {
    val targetItem = routeItems.firstOrNull { item -> item.poiId == targetPoiId } ?: return emptyList()
    val blockedIds = (routeItems.map { item -> item.poiId } + excludePoiIds + targetPoiId).toSet()
    val allowedCategories = selectedInterests.toSet()

    return pois
        .asSequence()
        .filter { poi -> poi.id !in blockedIds }
        .filter { poi -> allowedCategories.isEmpty() || poi.category in allowedCategories }
        .sortedWith(
            compareByDescending<Poi> { poi -> poi.category == targetItem.category }
                .thenByDescending { poi -> poi.baseScore ?: 0.0 }
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

internal fun sortRouteHistoryEntries(entries: List<RouteHistoryEntry>): List<RouteHistoryEntry> =
    entries.sortedByDescending { entry -> entry.updatedAtEpochMs }

internal fun mergeRouteHistoryEntries(
    cachedEntries: List<RouteHistoryEntry>,
    remoteEntries: List<RouteHistoryEntry>,
    currentEntry: RouteHistoryEntry?
): List<RouteHistoryEntry> {
    val merged = linkedMapOf<String, RouteHistoryEntry>()
    cachedEntries.forEach { entry ->
        merged[entry.routeId] = entry
    }
    remoteEntries.forEach { entry ->
        val existing = merged[entry.routeId]
        merged[entry.routeId] = if (existing == null) {
            entry
        } else {
            mergeRemoteHistoryEntry(existing, entry)
        }
    }
    if (currentEntry != null) {
        val existing = merged[currentEntry.routeId]
        merged[currentEntry.routeId] = if (existing == null) {
            currentEntry
        } else {
            choosePreferredHistoryEntry(existing, currentEntry)
        }
    }
    return sortRouteHistoryEntries(merged.values.toList())
}

internal fun mergeRemoteHistoryEntry(
    localEntry: RouteHistoryEntry,
    remoteEntry: RouteHistoryEntry
): RouteHistoryEntry =
    remoteEntry.copy(
        feedback = remoteEntry.feedback ?: localEntry.feedback,
        visitedPoiIds = if (
            remoteEntry.visitedPoiIds.isNotEmpty() ||
                remoteEntry.skippedPoiIds.isNotEmpty()
        ) {
            remoteEntry.visitedPoiIds
        } else {
            localEntry.visitedPoiIds
        },
        skippedPoiIds = if (
            remoteEntry.visitedPoiIds.isNotEmpty() ||
                remoteEntry.skippedPoiIds.isNotEmpty()
        ) {
            remoteEntry.skippedPoiIds
        } else {
            localEntry.skippedPoiIds
        }
    )

internal fun upsertRouteHistoryEntry(
    currentEntries: List<RouteHistoryEntry>,
    entry: RouteHistoryEntry
): List<RouteHistoryEntry> =
    sortRouteHistoryEntries(
        buildList {
            val existing = currentEntries.firstOrNull { current -> current.routeId == entry.routeId }
            add(if (existing == null) entry else choosePreferredHistoryEntry(existing, entry))
            addAll(currentEntries.filterNot { existingEntry -> existingEntry.routeId == entry.routeId })
        }
    )

internal fun choosePreferredHistoryEntry(
    existing: RouteHistoryEntry,
    candidate: RouteHistoryEntry
): RouteHistoryEntry {
    val existingStatus = RouteSessionStatus.fromRawValue(existing.status)
    val candidateStatus = RouteSessionStatus.fromRawValue(candidate.status)

    if (existingStatus.isTerminal() && !candidateStatus.isTerminal()) {
        return existing
    }
    if (!existingStatus.isTerminal() && candidateStatus.isTerminal()) {
        return candidate
    }
    if (existing.feedback != null && candidate.feedback == null) {
        return existing
    }
    if (existing.feedback == null && candidate.feedback != null) {
        return candidate
    }

    return if (candidate.updatedAtEpochMs >= existing.updatedAtEpochMs) candidate else existing
}

internal fun City.matchesToken(token: String?): Boolean {
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

internal fun City.availableCategories(): List<String> =
    availableCategories
        ?.map(String::trim)
        ?.filter(String::isNotBlank)
        ?.distinct()
        .orEmpty()
        .ifEmpty { DefaultInterestCategories }

internal fun City.supportsPublicTransport(): Boolean =
    transport?.mhdEnabled == true

internal fun City.toStartPoint(): RoutePoint =
    RoutePoint(centerLat, centerLon)

internal fun City.toOfflineCityRegion(): OfflineCityRegion? {
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

internal fun routeProgressMetrics(
    routeResponse: RoutePlan?,
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    currentLocation: RoutePoint?,
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
        distanceToNextRouteSegmentMeters(routeResponse, nextTarget.poiId, currentLocation) > OffRouteDistanceMeters

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
    routeItems: List<RouteStop>,
    skippedPoiIds: List<Int>
): Int =
    routeItems.count { item -> item.poiId !in skippedPoiIds }

internal fun requiredCompletionCount(totalCount: Int): Int =
    ceil(totalCount / 2.0).toInt().coerceAtLeast(1)

internal fun nextPendingPoi(
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>
): RouteStop? =
    routeItems.firstOrNull { item -> item.poiId !in visitedPoiIds && item.poiId !in skippedPoiIds }

internal fun mergeReroutedRoutePlan(
    previousResponse: RoutePlan,
    reroutedResponse: RoutePlan,
    visitedPoiIds: List<Int>
): RoutePlan {
    val visitedIds = visitedPoiIds.distinct()
    if (visitedIds.isEmpty()) {
        return reroutedResponse
    }

    val visitedItems = previousResponse.route
        .filter { item -> item.poiId in visitedIds }
        .sortedBy { item -> item.order }
    if (visitedItems.isEmpty()) {
        return reroutedResponse
    }

    val visitedElapsedMinutes = visitedItems.maxOfOrNull { item -> item.departureAfterMin } ?: 0
    val renumberedVisitedItems = visitedItems.mapIndexed { index, item ->
        item.copy(order = index + 1)
    }
    val renumberedRemainingItems = reroutedResponse.route.mapIndexed { index, item ->
        item.copy(
            order = renumberedVisitedItems.size + index + 1,
            arrivalAfterMin = visitedElapsedMinutes + item.arrivalAfterMin,
            departureAfterMin = visitedElapsedMinutes + item.departureAfterMin
        )
    }

    val visitedLegs = previousResponse.legs
        .orEmpty()
        .filter { leg -> leg.to.poiId in visitedIds }
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
    val mergedVisitMinutes = mergedRouteItems.sumOf { item -> item.visitDurationMin }
    val mergedUsedMinutes = visitedElapsedMinutes + reroutedResponse.usedMinutes
    val mergedAvailableMinutes = maxOf(previousResponse.availableMinutes, mergedUsedMinutes)
    val mergedRemainingMinutes = maxOf(0, mergedAvailableMinutes - mergedUsedMinutes)

    return reroutedResponse.copy(
        start = previousResponse.start,
        startDateTime = previousResponse.startDateTime,
        availableMinutes = mergedAvailableMinutes,
        usedMinutes = mergedUsedMinutes,
        remainingMinutes = mergedRemainingMinutes,
        totalVisitMinutes = mergedVisitMinutes,
        totalWalkMinutes = maxOf(0, mergedUsedMinutes - mergedVisitMinutes),
        poiCount = mergedRouteItems.size,
        route = mergedRouteItems,
        legs = mergedLegs,
        fullGeometry = mergeLegGeometries(mergedLegs.orEmpty())
    )
}

internal fun finalizedHandledRoutePlan(
    previousResponse: RoutePlan,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>
): RoutePlan {
    val handledPoiIds = (visitedPoiIds + skippedPoiIds).distinct()
    if (handledPoiIds.isEmpty()) {
        return previousResponse.copy(
            usedMinutes = 0,
            remainingMinutes = previousResponse.availableMinutes,
            totalVisitMinutes = 0,
            totalWalkMinutes = 0,
            returnToStartMinutes = 0,
            poiCount = 0,
            route = emptyList(),
            legs = null,
            fullGeometry = emptyList()
        )
    }

    val handledItems = previousResponse.route
        .filter { item -> item.poiId in handledPoiIds }
        .sortedBy { item -> item.order }
    val handledLegs = previousResponse.legs
        .orEmpty()
        .filter { leg -> leg.to.poiId in handledPoiIds }
        .sortedBy { leg -> leg.order }

    val renumberedItems = handledItems.mapIndexed { index, item ->
        item.copy(order = index + 1)
    }
    val renumberedLegs = handledLegs.mapIndexed { index, leg ->
        leg.copy(order = index + 1)
    }
    val usedMinutes = renumberedItems.maxOfOrNull { item -> item.departureAfterMin } ?: 0
    val totalVisitMinutes = renumberedItems.sumOf { item -> item.visitDurationMin }

    return previousResponse.copy(
        usedMinutes = usedMinutes,
        remainingMinutes = maxOf(0, previousResponse.availableMinutes - usedMinutes),
        totalVisitMinutes = totalVisitMinutes,
        totalWalkMinutes = maxOf(0, usedMinutes - totalVisitMinutes),
        returnToStartMinutes = 0,
        poiCount = renumberedItems.size,
        route = renumberedItems,
        legs = renumberedLegs.ifEmpty { null },
        fullGeometry = mergeLegGeometries(renumberedLegs)
    )
}

internal fun removePreviewRoutePoi(
    previousResponse: RoutePlan,
    poiId: Int,
): RoutePlan =
    rebuildPreviewRouteAfterRemovingPoi(
        previousResponse = previousResponse,
        poiId = poiId,
        replacementLegToNext = null,
        replacementReturnLeg = null
    )

internal fun rebuildPreviewRouteAfterRemovingPoi(
    previousResponse: RoutePlan,
    poiId: Int,
    replacementLegToNext: RouteLeg?,
    replacementReturnLeg: RouteLeg?
): RoutePlan {
    val remainingItems = previousResponse.route
        .filterNot { item -> item.poiId == poiId }
        .sortedBy { item -> item.order }
    if (remainingItems.isEmpty()) {
        return previousResponse.copy(
            usedMinutes = 0,
            remainingMinutes = previousResponse.availableMinutes,
            totalVisitMinutes = 0,
            totalWalkMinutes = 0,
            returnToStartMinutes = 0,
            poiCount = 0,
            route = emptyList(),
            legs = null,
            fullGeometry = emptyList()
        )
    }

    val existingLegs = previousResponse.legs
        .orEmpty()
        .sortedBy { leg -> leg.order }
    val replacementLegToNextPoiId = replacementLegToNext?.to?.poiId
    val poiLegs = remainingItems.mapNotNull { item ->
        if (item.poiId == replacementLegToNextPoiId) {
            replacementLegToNext
        } else {
            existingLegs.firstOrNull { leg -> leg.to.poiId == item.poiId }
        }
    }
    val lastRemainingPoiId = remainingItems.lastOrNull()?.poiId
    val returnLeg = if (previousResponse.returnToStart) {
        replacementReturnLeg
            ?: existingLegs.firstOrNull { leg ->
                leg.to.type == "start" && leg.from.poiId == lastRemainingPoiId
            }
    } else {
        null
    }

    val renumberedLegs = (poiLegs + listOfNotNull(returnLeg)).mapIndexed { index, leg ->
        leg.copy(order = index + 1)
    }
    var elapsedMinutes = 0
    val renumberedItems = remainingItems.mapIndexed { index, item ->
        val incomingLeg = renumberedLegs.firstOrNull { leg -> leg.to.poiId == item.poiId }
        val travelMinutes = incomingLeg?.durationMinutes ?: item.travelMinutesFromPrevious
        elapsedMinutes += travelMinutes
        val arrivalMinutes = elapsedMinutes
        elapsedMinutes += item.visitDurationMin
        item.copy(
            order = index + 1,
            travelMinutesFromPrevious = travelMinutes,
            arrivalAfterMin = arrivalMinutes,
            departureAfterMin = elapsedMinutes
        )
    }
    val returnLegMinutes = returnLeg
        ?.takeIf { leg -> leg.to.type == "start" }
        ?.durationMinutes
        ?: 0
    val usedMinutes = elapsedMinutes + returnLegMinutes
    val totalVisitMinutes = renumberedItems.sumOf { item -> item.visitDurationMin }

    return previousResponse.copy(
        usedMinutes = usedMinutes,
        remainingMinutes = maxOf(0, previousResponse.availableMinutes - usedMinutes),
        totalVisitMinutes = totalVisitMinutes,
        totalWalkMinutes = maxOf(0, usedMinutes - totalVisitMinutes),
        returnToStartMinutes = returnLegMinutes,
        poiCount = renumberedItems.size,
        route = renumberedItems,
        legs = renumberedLegs.ifEmpty { null },
        fullGeometry = mergeLegGeometries(renumberedLegs)
    )
}

internal fun rebuildPreviewRouteAfterReplacingPoi(
    previousResponse: RoutePlan,
    targetPoiId: Int,
    replacementPoi: Poi,
    replacementLegToReplacement: RouteLeg,
    replacementLegToNext: RouteLeg?,
    replacementReturnLeg: RouteLeg?
): RoutePlan {
    val originalItems = previousResponse.route.sortedBy { item -> item.order }
    val targetIndex = originalItems.indexOfFirst { item -> item.poiId == targetPoiId }
    if (targetIndex == -1) {
        return previousResponse
    }

    val targetItem = originalItems[targetIndex]
    val replacementVisitMinutes = replacementPoi.visitDurationMin ?: targetItem.visitDurationMin
    val replacedItems = originalItems.map { item ->
        if (item.poiId == targetPoiId) {
            item.copy(
                poiId = replacementPoi.id,
                name = replacementPoi.name,
                category = replacementPoi.category,
                lat = replacementPoi.lat,
                lon = replacementPoi.lon,
                visitDurationMin = replacementVisitMinutes,
                baseScore = replacementPoi.baseScore,
                wikipediaUrl = replacementPoi.wikipediaUrl,
                openingHoursRaw = replacementPoi.openingHoursRaw
            )
        } else {
            item
        }
    }

    val existingLegs = previousResponse.legs.orEmpty().sortedBy { leg -> leg.order }
    val nextOriginalItem = originalItems.getOrNull(targetIndex + 1)
    val poiLegs = replacedItems.mapNotNull { item ->
        when {
            item.poiId == replacementPoi.id -> replacementLegToReplacement
            item.poiId == nextOriginalItem?.poiId -> replacementLegToNext
            else -> existingLegs.firstOrNull { leg -> leg.to.poiId == item.poiId }
        }
    }
    val returnLeg = if (previousResponse.returnToStart) {
        if (targetIndex == originalItems.lastIndex) {
            replacementReturnLeg
        } else {
            val lastPoiId = replacedItems.lastOrNull()?.poiId
            existingLegs.firstOrNull { leg -> leg.to.type == "start" && leg.from.poiId == lastPoiId }
        }
    } else {
        null
    }

    val renumberedLegs = (poiLegs + listOfNotNull(returnLeg)).mapIndexed { index, leg ->
        leg.copy(order = index + 1)
    }
    var elapsedMinutes = 0
    val renumberedItems = replacedItems.mapIndexed { index, item ->
        val incomingLeg = renumberedLegs.firstOrNull { leg -> leg.to.poiId == item.poiId }
        val travelMinutes = incomingLeg?.durationMinutes ?: item.travelMinutesFromPrevious
        elapsedMinutes += travelMinutes
        val arrivalMinutes = elapsedMinutes
        elapsedMinutes += item.visitDurationMin
        item.copy(
            order = index + 1,
            travelMinutesFromPrevious = travelMinutes,
            arrivalAfterMin = arrivalMinutes,
            departureAfterMin = elapsedMinutes
        )
    }
    val returnLegMinutes = returnLeg
        ?.takeIf { leg -> leg.to.type == "start" }
        ?.durationMinutes
        ?: 0
    val usedMinutes = elapsedMinutes + returnLegMinutes
    val totalVisitMinutes = renumberedItems.sumOf { item -> item.visitDurationMin }

    return previousResponse.copy(
        usedMinutes = usedMinutes,
        remainingMinutes = maxOf(0, previousResponse.availableMinutes - usedMinutes),
        totalVisitMinutes = totalVisitMinutes,
        totalWalkMinutes = maxOf(0, usedMinutes - totalVisitMinutes),
        returnToStartMinutes = returnLegMinutes,
        poiCount = renumberedItems.size,
        route = renumberedItems,
        legs = renumberedLegs.ifEmpty { null },
        fullGeometry = mergeLegGeometries(renumberedLegs)
    )
}

internal fun replaceActiveRouteApproachLeg(
    previousResponse: RoutePlan,
    nextPoiId: Int,
    replacementLeg: RouteLeg
): RoutePlan {
    val previousLegs = previousResponse.legs.orEmpty().sortedBy { leg -> leg.order }
    if (previousLegs.isEmpty()) {
        return previousResponse
    }

    val targetLegIndex = previousLegs.indexOfFirst { leg -> leg.to.poiId == nextPoiId }
    if (targetLegIndex == -1) {
        return previousResponse
    }

    val targetLeg = previousLegs[targetLegIndex]
    val durationDeltaMinutes = replacementLeg.durationMinutes - targetLeg.durationMinutes
    val updatedLegs = previousLegs.toMutableList().apply {
        this[targetLegIndex] = replacementLeg.copy(
            order = targetLeg.order,
            to = targetLeg.to
        )
    }

    val targetItem = previousResponse.route.firstOrNull { item -> item.poiId == nextPoiId }
        ?: return previousResponse.copy(
            legs = updatedLegs,
            fullGeometry = mergeLegGeometries(updatedLegs)
        )
    val targetOrder = targetItem.order
    val updatedItems = previousResponse.route.map { item ->
        when {
            item.poiId == nextPoiId -> item.copy(
                travelMinutesFromPrevious = replacementLeg.durationMinutes,
                arrivalAfterMin = item.arrivalAfterMin + durationDeltaMinutes,
                departureAfterMin = item.departureAfterMin + durationDeltaMinutes
            )

            item.order > targetOrder -> item.copy(
                arrivalAfterMin = item.arrivalAfterMin + durationDeltaMinutes,
                departureAfterMin = item.departureAfterMin + durationDeltaMinutes
            )

            else -> item
        }
    }

    val returnLegMinutes = updatedLegs.lastOrNull()
        ?.takeIf { leg -> leg.to.type == "start" }
        ?.durationMinutes
        ?: 0
    val usedMinutes = (updatedItems.maxOfOrNull { item -> item.departureAfterMin } ?: 0) + returnLegMinutes
    val totalVisitMinutes = updatedItems.sumOf { item -> item.visitDurationMin }

    return previousResponse.copy(
        usedMinutes = usedMinutes,
        remainingMinutes = maxOf(0, previousResponse.availableMinutes - usedMinutes),
        totalVisitMinutes = totalVisitMinutes,
        totalWalkMinutes = maxOf(0, usedMinutes - totalVisitMinutes),
        route = updatedItems,
        legs = updatedLegs,
        fullGeometry = mergeLegGeometries(updatedLegs)
    )
}

internal fun mergeLegGeometries(legs: List<RouteLeg>): List<RouteCoordinate> {
    if (legs.isEmpty()) {
        return emptyList()
    }

    val mergedGeometry = mutableListOf<RouteCoordinate>()
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
    routeResponse: RoutePlan?,
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    skippedPoiIds: List<Int>,
    currentLocation: RoutePoint?,
    nextTarget: RouteStop?
): Int {
    val remainingItems = routeItems.filter { item ->
        item.poiId !in visitedPoiIds && item.poiId !in skippedPoiIds
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
        remainingItems.first().travelMinutesFromPrevious
    }
    val remainingVisits = remainingItems.sumOf { item -> item.visitDurationMin }
    val remainingWalksAfterTarget = remainingItems.drop(1).sumOf { item ->
        item.travelMinutesFromPrevious
    }
    val returnToStartMinutes = if (routeResponse?.returnToStart == true) {
        routeResponse.returnToStartMinutes
    } else {
        0
    }

    return firstWalkMinutes + remainingVisits + remainingWalksAfterTarget + returnToStartMinutes
}

internal fun rerouteStartPoint(
    routeItems: List<RouteStop>,
    visitedPoiIds: List<Int>,
    currentLocation: RoutePoint?,
    fallbackStart: RoutePoint
): RoutePoint {
    currentLocation?.let { return it }

    val lastVisitedStop = routeItems
        .filter { item -> item.poiId in visitedPoiIds }
        .maxByOrNull { item -> item.order }

    return lastVisitedStop?.let { stop ->
        RoutePoint(lat = stop.lat, lon = stop.lon)
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
    routeResponse: RoutePlan?,
    nextTargetPoiId: Int,
    currentLocation: RoutePoint
): Float {
    val legGeometry = routeResponse
        ?.legs
        .orEmpty()
        .firstOrNull { leg -> leg.to.poiId == nextTargetPoiId }
        ?.geometry
        .orEmpty()

    if (legGeometry.size < 2) {
        val nextTarget = routeResponse
            ?.route
            .orEmpty()
            .firstOrNull { item -> item.poiId == nextTargetPoiId }
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
    point: RoutePoint,
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
    routeItems: List<RouteStop>,
    currentLocation: RoutePoint,
    visitedPoiIds: MutableList<Int>,
    skippedPoiIds: List<Int>
): List<Int> {
    val newlyVisitedIds = routeItems
        .filter { item -> item.poiId !in visitedPoiIds && item.poiId !in skippedPoiIds }
        .filter { item ->
            distanceMeters(
                startLat = currentLocation.lat,
                startLon = currentLocation.lon,
                endLat = item.lat,
                endLon = item.lon
            ) <= PoiVisitedRadiusMeters
        }
        .map { item -> item.poiId }

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
