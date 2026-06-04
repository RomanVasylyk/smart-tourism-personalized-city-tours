package com.example.smarttourism.features.planner.domain.mapper

import com.example.smarttourism.data.remote.dto.CityBboxDto
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RouteCoordinateDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackDto
import com.example.smarttourism.data.remote.dto.RouteItemDto
import com.example.smarttourism.data.remote.dto.RouteLegDto
import com.example.smarttourism.data.remote.dto.RouteLegEndpointDto
import com.example.smarttourism.data.remote.dto.RouteLegRequest
import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteSegmentDto
import com.example.smarttourism.data.remote.dto.RouteSessionDto
import com.example.smarttourism.data.remote.dto.RouteSessionPoiDto
import com.example.smarttourism.data.remote.dto.RouteStartDto
import com.example.smarttourism.data.remote.dto.RoutingLimitsDto
import com.example.smarttourism.data.remote.dto.TransportProfileDto
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.CityBounds
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteCoordinate
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegEndpoint
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteSegment
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.domain.model.RouteSessionFeedback
import com.example.smarttourism.features.planner.domain.model.RouteSessionStop
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.model.RoutingLimits
import com.example.smarttourism.features.planner.domain.model.TransportProfile

internal fun CityDto.toDomain(): City =
    City(
        id = id,
        slug = slug,
        name = name,
        country = country,
        centerLat = center_lat,
        centerLon = center_lon,
        bbox = bbox?.toDomain(),
        availableCategories = available_categories.orEmpty(),
        defaultZoom = default_zoom,
        routingLimits = routing_limits?.toDomain(),
        transport = transport?.toDomain()
    )

internal fun City.toDto(): CityDto =
    CityDto(
        id = id,
        slug = slug,
        name = name,
        country = country,
        center_lat = centerLat,
        center_lon = centerLon,
        bbox = bbox?.toDto(),
        available_categories = availableCategories.orEmpty(),
        default_zoom = defaultZoom,
        routing_limits = routingLimits?.toDto(),
        transport = transport?.toDto()
    )

private fun CityBboxDto.toDomain(): CityBounds =
    CityBounds(south = south, west = west, north = north, east = east)

private fun CityBounds.toDto(): CityBboxDto =
    CityBboxDto(south = south, west = west, north = north, east = east)

private fun RoutingLimitsDto.toDomain(): RoutingLimits =
    RoutingLimits(
        maxAvailableMinutes = max_available_minutes,
        maxPoiCandidates = max_poi_candidates
    )

private fun RoutingLimits.toDto(): RoutingLimitsDto =
    RoutingLimitsDto(
        max_available_minutes = maxAvailableMinutes,
        max_poi_candidates = maxPoiCandidates
    )

private fun TransportProfileDto.toDomain(): TransportProfile =
    TransportProfile(
        mhdEnabled = mhd_enabled,
        provider = provider,
        mode = mode
    )

private fun TransportProfile.toDto(): TransportProfileDto =
    TransportProfileDto(
        mhd_enabled = mhdEnabled,
        provider = provider,
        mode = mode
    )

internal fun PoiDto.toDomain(): Poi =
    Poi(
        id = id,
        name = name,
        category = category,
        lat = lat,
        lon = lon,
        openingHoursRaw = opening_hours_raw,
        visitDurationMin = visit_duration_min,
        baseScore = base_score,
        wikipediaUrl = wikipedia_url
    )

internal fun Poi.toDto(): PoiDto =
    PoiDto(
        id = id,
        name = name,
        category = category,
        lat = lat,
        lon = lon,
        opening_hours_raw = openingHoursRaw,
        visit_duration_min = visitDurationMin,
        base_score = baseScore,
        wikipedia_url = wikipediaUrl
    )

internal fun PlannerPreferences.toDto(): RouteRequest =
    RouteRequest(
        city = city,
        start_lat = startLat,
        start_lon = startLon,
        available_minutes = availableMinutes,
        interests = interests,
        pace = pace,
        return_to_start = returnToStart,
        start_datetime = startDateTime,
        respect_opening_hours = respectOpeningHours,
        exclude_poi_ids = excludedPoiIds,
        preferred_poi_ids = preferredPoiIds,
        transport_mode = transportMode
    )

internal fun RouteRequest.toDomain(): PlannerPreferences =
    PlannerPreferences(
        city = city,
        startLat = start_lat,
        startLon = start_lon,
        availableMinutes = available_minutes,
        interests = interests,
        pace = pace,
        returnToStart = return_to_start,
        startDateTime = start_datetime,
        respectOpeningHours = respect_opening_hours,
        excludedPoiIds = exclude_poi_ids,
        preferredPoiIds = preferred_poi_ids.orEmpty(),
        transportMode = transport_mode
    )

internal fun RouteResponse.toDomain(): RoutePlan =
    RoutePlan(
        city = city,
        start = start.toDomain(),
        startDateTime = start_datetime,
        pace = pace,
        interests = interests,
        transportMode = transport_mode,
        returnToStart = return_to_start,
        respectOpeningHours = respect_opening_hours,
        availableMinutes = available_minutes,
        usedMinutes = used_minutes,
        remainingMinutes = remaining_minutes,
        totalVisitMinutes = total_visit_minutes,
        totalWalkMinutes = total_walk_minutes,
        returnToStartMinutes = return_to_start_minutes,
        poiCount = poi_count,
        route = route.map { item -> item.toDomain() },
        legs = legs?.map { leg -> leg.toDomain() },
        fullGeometry = full_geometry?.map { coordinate -> coordinate.toDomain() }
    )

internal fun RoutePlan.toDto(): RouteResponse =
    RouteResponse(
        city = city,
        start = start.toDto(),
        start_datetime = startDateTime,
        pace = pace,
        interests = interests,
        transport_mode = transportMode,
        return_to_start = returnToStart,
        respect_opening_hours = respectOpeningHours,
        available_minutes = availableMinutes,
        used_minutes = usedMinutes,
        remaining_minutes = remainingMinutes,
        total_visit_minutes = totalVisitMinutes,
        total_walk_minutes = totalWalkMinutes,
        return_to_start_minutes = returnToStartMinutes,
        poi_count = poiCount,
        route = route.map { item -> item.toDto() },
        legs = legs?.map { leg -> leg.toDto() },
        full_geometry = fullGeometry?.map { coordinate -> coordinate.toDto() }
    )

internal fun RouteStartDto.toDomain(): RoutePoint =
    RoutePoint(lat = lat, lon = lon)

internal fun RoutePoint.toDto(): RouteStartDto =
    RouteStartDto(lat = lat, lon = lon)

private fun RouteItemDto.toDomain(): RouteStop =
    RouteStop(
        order = order,
        poiId = poi_id,
        name = name,
        category = category,
        lat = lat,
        lon = lon,
        travelMinutesFromPrevious = travel_minutes_from_previous,
        visitDurationMin = visit_duration_min,
        arrivalAfterMin = arrival_after_min,
        departureAfterMin = departure_after_min,
        baseScore = base_score,
        wikipediaUrl = wikipedia_url,
        openingHoursRaw = opening_hours_raw
    )

private fun RouteStop.toDto(): RouteItemDto =
    RouteItemDto(
        order = order,
        poi_id = poiId,
        name = name,
        category = category,
        lat = lat,
        lon = lon,
        travel_minutes_from_previous = travelMinutesFromPrevious,
        visit_duration_min = visitDurationMin,
        arrival_after_min = arrivalAfterMin,
        departure_after_min = departureAfterMin,
        base_score = baseScore,
        wikipedia_url = wikipediaUrl,
        opening_hours_raw = openingHoursRaw
    )

internal fun RouteLegDto.toDomain(): RouteLeg =
    RouteLeg(
        order = order,
        mode = mode,
        from = from.toDomain(),
        to = to.toDomain(),
        durationSeconds = duration_seconds,
        durationMinutes = duration_minutes,
        distanceMeters = distance_meters,
        geometry = geometry.map { coordinate -> coordinate.toDomain() },
        routingSource = routing_source,
        departureTime = departure_time,
        arrivalTime = arrival_time,
        segments = segments?.map { segment -> segment.toDomain() }
    )

internal fun RouteLeg.toDto(): RouteLegDto =
    RouteLegDto(
        order = order,
        mode = mode,
        from = from.toDto(),
        to = to.toDto(),
        duration_seconds = durationSeconds,
        duration_minutes = durationMinutes,
        distance_meters = distanceMeters,
        geometry = geometry.map { coordinate -> coordinate.toDto() },
        routing_source = routingSource,
        departure_time = departureTime,
        arrival_time = arrivalTime,
        segments = segments?.map { segment -> segment.toDto() }
    )

private fun RouteLegEndpointDto.toDomain(): RouteLegEndpoint =
    RouteLegEndpoint(
        type = type,
        poiId = poi_id,
        name = name,
        lat = lat,
        lon = lon
    )

private fun RouteLegEndpoint.toDto(): RouteLegEndpointDto =
    RouteLegEndpointDto(
        type = type,
        poi_id = poiId,
        name = name,
        lat = lat,
        lon = lon
    )

private fun RouteCoordinateDto.toDomain(): RouteCoordinate =
    RouteCoordinate(lat = lat, lon = lon)

private fun RouteCoordinate.toDto(): RouteCoordinateDto =
    RouteCoordinateDto(lat = lat, lon = lon)

private fun RouteSegmentDto.toDomain(): RouteSegment =
    RouteSegment(
        order = order,
        mode = mode,
        durationSeconds = duration_seconds,
        durationMinutes = duration_minutes,
        distanceMeters = distance_meters,
        geometry = geometry?.map { coordinate -> coordinate.toDomain() },
        source = source,
        lineName = line_name,
        fromStopName = from_stop_name,
        toStopName = to_stop_name,
        departureTime = departure_time,
        arrivalTime = arrival_time,
        waitMinutesBeforeDeparture = wait_minutes_before_departure,
        inVehicleMinutes = in_vehicle_minutes
    )

private fun RouteSegment.toDto(): RouteSegmentDto =
    RouteSegmentDto(
        order = order,
        mode = mode,
        duration_seconds = durationSeconds,
        duration_minutes = durationMinutes,
        distance_meters = distanceMeters,
        geometry = geometry?.map { coordinate -> coordinate.toDto() },
        source = source,
        line_name = lineName,
        from_stop_name = fromStopName,
        to_stop_name = toStopName,
        departure_time = departureTime,
        arrival_time = arrivalTime,
        wait_minutes_before_departure = waitMinutesBeforeDeparture,
        in_vehicle_minutes = inVehicleMinutes
    )

internal fun RouteLegQuery.toDto(): RouteLegRequest =
    RouteLegRequest(
        city = city,
        start_lat = startLat,
        start_lon = startLon,
        end_lat = endLat,
        end_lon = endLon,
        end_poi_id = endPoiId,
        end_name = endName,
        start_datetime = startDateTime,
        pace = pace,
        transport_mode = transportMode
    )

internal fun RouteSessionDto.toDomain(): RouteSession =
    RouteSession(
        id = id,
        deviceId = device_id,
        cityId = city_id,
        cityName = city_name,
        status = status,
        startLat = start_lat,
        startLon = start_lon,
        availableMinutes = available_minutes,
        pace = pace,
        returnToStart = return_to_start,
        openingHoursEnabled = opening_hours_enabled,
        startedAt = started_at,
        finishedAt = finished_at,
        usedMinutes = used_minutes,
        totalWalkMinutes = total_walk_minutes,
        totalVisitMinutes = total_visit_minutes,
        routeSnapshot = route_snapshot_json?.toDomain(),
        pois = pois.orEmpty().map { poi -> poi.toDomain() },
        feedback = feedback.orEmpty().map { item -> item.toDomain() }
    )

private fun RouteSessionPoiDto.toDomain(): RouteSessionStop =
    RouteSessionStop(
        id = id,
        sessionId = session_id,
        poiId = poi_id,
        visitOrder = visit_order,
        plannedArrivalMin = planned_arrival_min,
        plannedDepartureMin = planned_departure_min,
        visited = visited,
        visitedAt = visited_at,
        skipped = skipped
    )

private fun RouteFeedbackDto.toDomain(): RouteSessionFeedback =
    RouteSessionFeedback(
        rating = rating,
        wasConvenient = was_convenient,
        tooMuchWalking = too_much_walking,
        poisWereInteresting = pois_were_interesting
    )
