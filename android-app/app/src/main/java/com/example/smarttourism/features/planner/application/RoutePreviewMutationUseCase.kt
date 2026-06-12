package com.example.smarttourism.features.planner.application

import com.example.smarttourism.features.planner.data.route.RoutePlanningRepository
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegEndpoint
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.route.mergeLegGeometries
import com.example.smarttourism.features.planner.domain.route.rebuildPreviewRouteAfterRemovingPoi
import com.example.smarttourism.features.planner.domain.route.rebuildPreviewRouteAfterReplacingPoi
import com.example.smarttourism.features.planner.domain.route.removePreviewRoutePoi
import javax.inject.Inject

internal data class RoutePreviewMutationContext(
    val city: String?,
    val pace: String?,
    val startDateTime: String?,
    val transportMode: String?
)

internal class RoutePreviewMutationUseCase @Inject constructor(
    private val routePlanningRepository: RoutePlanningRepository
) {
    suspend fun removeStop(
        previousResponse: RoutePlan,
        poiId: Int,
        context: RoutePreviewMutationContext
    ): RoutePlan {
        val originalItems = previousResponse.route.sortedBy { item -> item.order }
        val removedIndex = originalItems.indexOfFirst { item -> item.poiId == poiId }
        if (removedIndex == -1) {
            return previousResponse
        }

        val remainingItems = originalItems.filterNot { item -> item.poiId == poiId }
        if (remainingItems.isEmpty()) {
            return removePreviewRoutePoi(previousResponse, poiId)
        }

        val startEndpoint = previousResponse.start.toLegEndpoint()
        val previousItem = originalItems.getOrNull(removedIndex - 1)
        val nextItem = originalItems.getOrNull(removedIndex + 1)
        val replacementLegToNext = nextItem?.let { nextStop ->
            generatePreviewRouteLeg(
                previousResponse = previousResponse,
                fromEndpoint = previousItem?.toLegEndpoint() ?: startEndpoint,
                toEndpoint = nextStop.toLegEndpoint(),
                context = context
            )
        }
        val replacementReturnLeg = if (nextItem == null && previousResponse.returnToStart) {
            remainingItems.lastOrNull()?.let { lastStop ->
                generatePreviewRouteLeg(
                    previousResponse = previousResponse,
                    fromEndpoint = lastStop.toLegEndpoint(),
                    toEndpoint = startEndpoint,
                    context = context
                )
            }
        } else {
            null
        }

        return rebuildPreviewRouteAfterRemovingPoi(
            previousResponse = previousResponse,
            poiId = poiId,
            replacementLegToNext = replacementLegToNext,
            replacementReturnLeg = replacementReturnLeg
        )
    }

    suspend fun replaceStop(
        previousResponse: RoutePlan,
        targetPoiId: Int,
        replacementPoi: Poi,
        context: RoutePreviewMutationContext
    ): RoutePlan {
        val originalItems = previousResponse.route.sortedBy { item -> item.order }
        val targetIndex = originalItems.indexOfFirst { item -> item.poiId == targetPoiId }
        if (targetIndex == -1) {
            return previousResponse
        }

        val startEndpoint = previousResponse.start.toLegEndpoint()
        val previousItem = originalItems.getOrNull(targetIndex - 1)
        val nextItem = originalItems.getOrNull(targetIndex + 1)
        val replacementEndpoint = replacementPoi.toLegEndpoint()
        val replacementLegToReplacement = generatePreviewRouteLeg(
            previousResponse = previousResponse,
            fromEndpoint = previousItem?.toLegEndpoint() ?: startEndpoint,
            toEndpoint = replacementEndpoint,
            context = context
        )
        val replacementLegToNext = nextItem?.let { nextStop ->
            generatePreviewRouteLeg(
                previousResponse = previousResponse,
                fromEndpoint = replacementEndpoint,
                toEndpoint = nextStop.toLegEndpoint(),
                context = context
            )
        }
        val replacementReturnLeg = if (nextItem == null && previousResponse.returnToStart) {
            generatePreviewRouteLeg(
                previousResponse = previousResponse,
                fromEndpoint = replacementEndpoint,
                toEndpoint = startEndpoint,
                context = context
            )
        } else {
            null
        }

        return rebuildPreviewRouteAfterReplacingPoi(
            previousResponse = previousResponse,
            targetPoiId = targetPoiId,
            replacementPoi = replacementPoi,
            replacementLegToReplacement = replacementLegToReplacement,
            replacementLegToNext = replacementLegToNext,
            replacementReturnLeg = replacementReturnLeg
        )
    }

    suspend fun moveStop(
        previousResponse: RoutePlan,
        poiId: Int,
        direction: Int,
        context: RoutePreviewMutationContext
    ): RoutePlan {
        val originalItems = previousResponse.route.sortedBy { item -> item.order }
        val currentIndex = originalItems.indexOfFirst { item -> item.poiId == poiId }
        if (currentIndex == -1) {
            return previousResponse
        }

        val targetIndex = (currentIndex + direction).coerceIn(0, originalItems.lastIndex)
        if (targetIndex == currentIndex) {
            return previousResponse
        }

        val reorderedItems = originalItems.toMutableList().also { mutableItems ->
            val movedItem = mutableItems.removeAt(currentIndex)
            mutableItems.add(targetIndex, movedItem)
        }
        val startEndpoint = previousResponse.start.toLegEndpoint()
        val poiLegs = mutableListOf<RouteLeg>()
        var fromEndpoint = startEndpoint
        reorderedItems.forEach { item ->
            val toEndpoint = item.toLegEndpoint()
            poiLegs.add(
                generatePreviewRouteLeg(
                    previousResponse = previousResponse,
                    fromEndpoint = fromEndpoint,
                    toEndpoint = toEndpoint,
                    context = context
                )
            )
            fromEndpoint = toEndpoint
        }
        val returnLeg = if (previousResponse.returnToStart && reorderedItems.isNotEmpty()) {
            generatePreviewRouteLeg(
                previousResponse = previousResponse,
                fromEndpoint = reorderedItems.last().toLegEndpoint(),
                toEndpoint = startEndpoint,
                context = context
            )
        } else {
            null
        }

        val renumberedLegs = (poiLegs + listOfNotNull(returnLeg)).mapIndexed { index, leg ->
            leg.copy(order = index + 1)
        }
        var elapsedMinutes = 0
        val renumberedItems = reorderedItems.mapIndexed { index, item ->
            val incomingLeg = poiLegs[index]
            elapsedMinutes += incomingLeg.durationMinutes
            val arrivalMinutes = elapsedMinutes
            elapsedMinutes += item.visitDurationMin
            item.copy(
                order = index + 1,
                travelMinutesFromPrevious = incomingLeg.durationMinutes,
                arrivalAfterMin = arrivalMinutes,
                departureAfterMin = elapsedMinutes
            )
        }
        val returnLegMinutes = returnLeg?.durationMinutes ?: 0
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

    private suspend fun generatePreviewRouteLeg(
        previousResponse: RoutePlan,
        fromEndpoint: RouteLegEndpoint,
        toEndpoint: RouteLegEndpoint,
        context: RoutePreviewMutationContext
    ): RouteLeg {
        val routeLegRequest = RouteLegQuery(
            city = context.city ?: previousResponse.city,
            startLat = fromEndpoint.lat,
            startLon = fromEndpoint.lon,
            endLat = toEndpoint.lat,
            endLon = toEndpoint.lon,
            endPoiId = toEndpoint.poiId ?: -1,
            endName = toEndpoint.name,
            pace = context.pace ?: previousResponse.pace,
            startDateTime = context.startDateTime ?: previousResponse.startDateTime,
            transportMode = context.transportMode ?: previousResponse.transportMode ?: "walk"
        )

        return routePlanningRepository.generateRouteLeg(routeLegRequest).copy(
            from = fromEndpoint,
            to = toEndpoint
        )
    }
}

private fun RoutePoint.toLegEndpoint(): RouteLegEndpoint =
    RouteLegEndpoint(
        type = "start",
        poiId = null,
        name = null,
        lat = lat,
        lon = lon
    )

private fun RouteStop.toLegEndpoint(): RouteLegEndpoint =
    RouteLegEndpoint(
        type = "poi",
        poiId = poiId,
        name = name,
        lat = lat,
        lon = lon
    )

private fun Poi.toLegEndpoint(): RouteLegEndpoint =
    RouteLegEndpoint(
        type = "poi",
        poiId = id,
        name = name,
        lat = lat,
        lon = lon
    )
