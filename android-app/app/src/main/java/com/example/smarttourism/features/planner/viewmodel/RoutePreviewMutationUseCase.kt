package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteItemDto
import com.example.smarttourism.data.remote.dto.RouteLegDto
import com.example.smarttourism.data.remote.dto.RouteLegEndpointDto
import com.example.smarttourism.data.remote.dto.RouteLegRequest
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteStartDto
import com.example.smarttourism.features.planner.data.PlannerRepository

internal data class RoutePreviewMutationContext(
    val city: String?,
    val pace: String?,
    val startDateTime: String?,
    val transportMode: String?
)

internal class RoutePreviewMutationUseCase(
    private val repository: PlannerRepository
) {
    suspend fun removeStop(
        previousResponse: RouteResponse,
        poiId: Int,
        context: RoutePreviewMutationContext
    ): RouteResponse {
        val originalItems = previousResponse.route.sortedBy { item -> item.order }
        val removedIndex = originalItems.indexOfFirst { item -> item.poi_id == poiId }
        if (removedIndex == -1) {
            return previousResponse
        }

        val remainingItems = originalItems.filterNot { item -> item.poi_id == poiId }
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
        val replacementReturnLeg = if (nextItem == null && previousResponse.return_to_start) {
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
        previousResponse: RouteResponse,
        targetPoiId: Int,
        replacementPoi: PoiDto,
        context: RoutePreviewMutationContext
    ): RouteResponse {
        val originalItems = previousResponse.route.sortedBy { item -> item.order }
        val targetIndex = originalItems.indexOfFirst { item -> item.poi_id == targetPoiId }
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
        val replacementReturnLeg = if (nextItem == null && previousResponse.return_to_start) {
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
        previousResponse: RouteResponse,
        poiId: Int,
        direction: Int,
        context: RoutePreviewMutationContext
    ): RouteResponse {
        val originalItems = previousResponse.route.sortedBy { item -> item.order }
        val currentIndex = originalItems.indexOfFirst { item -> item.poi_id == poiId }
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
        val poiLegs = mutableListOf<RouteLegDto>()
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
        val returnLeg = if (previousResponse.return_to_start && reorderedItems.isNotEmpty()) {
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
            elapsedMinutes += incomingLeg.duration_minutes
            val arrivalMinutes = elapsedMinutes
            elapsedMinutes += item.visit_duration_min
            item.copy(
                order = index + 1,
                travel_minutes_from_previous = incomingLeg.duration_minutes,
                arrival_after_min = arrivalMinutes,
                departure_after_min = elapsedMinutes
            )
        }
        val returnLegMinutes = returnLeg?.duration_minutes ?: 0
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

    private suspend fun generatePreviewRouteLeg(
        previousResponse: RouteResponse,
        fromEndpoint: RouteLegEndpointDto,
        toEndpoint: RouteLegEndpointDto,
        context: RoutePreviewMutationContext
    ): RouteLegDto {
        val routeLegRequest = RouteLegRequest(
            city = context.city ?: previousResponse.city,
            start_lat = fromEndpoint.lat,
            start_lon = fromEndpoint.lon,
            end_lat = toEndpoint.lat,
            end_lon = toEndpoint.lon,
            end_poi_id = toEndpoint.poi_id ?: -1,
            end_name = toEndpoint.name,
            pace = context.pace ?: previousResponse.pace,
            start_datetime = context.startDateTime ?: previousResponse.start_datetime,
            transport_mode = context.transportMode ?: previousResponse.transport_mode ?: "walk"
        )

        return repository.generateRouteLeg(routeLegRequest).copy(
            from = fromEndpoint,
            to = toEndpoint
        )
    }
}

private fun RouteStartDto.toLegEndpoint(): RouteLegEndpointDto =
    RouteLegEndpointDto(
        type = "start",
        poi_id = null,
        name = null,
        lat = lat,
        lon = lon
    )

private fun RouteItemDto.toLegEndpoint(): RouteLegEndpointDto =
    RouteLegEndpointDto(
        type = "poi",
        poi_id = poi_id,
        name = name,
        lat = lat,
        lon = lon
    )

private fun PoiDto.toLegEndpoint(): RouteLegEndpointDto =
    RouteLegEndpointDto(
        type = "poi",
        poi_id = id,
        name = name,
        lat = lat,
        lon = lon
    )
