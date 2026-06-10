package com.example.smarttourism.features.planner.domain.route

import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteCoordinate
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteStop
import java.util.Locale

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
