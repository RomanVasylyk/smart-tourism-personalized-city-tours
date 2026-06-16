package com.example.smarttourism.features.planner.data.mapper

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
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.domain.model.RoutingLimits
import com.example.smarttourism.features.planner.domain.model.TransportProfile
import org.junit.Assert.assertEquals
import org.junit.Test

class PlannerDataMappersRoundtripTest {
    @Test
    fun cityAndPoiRoundTripThroughDto() {
        val city = City(
            id = 7,
            slug = "trnava",
            name = "Trnava",
            country = "SK",
            centerLat = 48.377,
            centerLon = 17.587,
            bbox = CityBounds(south = 48.3, west = 17.5, north = 48.4, east = 17.7),
            availableCategories = listOf("museum", "park"),
            defaultZoom = 13.0,
            routingLimits = RoutingLimits(maxAvailableMinutes = 360, maxPoiCandidates = 80),
            transport = TransportProfile(mhdEnabled = true, provider = "imhd", mode = "bus")
        )
        val poi = Poi(
            id = 42,
            name = "City Tower",
            category = "viewpoint",
            lat = 48.378,
            lon = 17.586,
            openingHoursRaw = "Mo-Su 09:00-18:00",
            visitDurationMin = 25,
            baseScore = 4.7,
            wikipediaUrl = "https://example.test/tower"
        )

        assertEquals(city, city.toDto().toDomain())
        assertEquals(poi, poi.toDto().toDomain())
    }

    @Test
    fun plannerPreferencesRoundTripThroughRouteRequest() {
        val preferences = PlannerPreferences(
            city = "zilina",
            startLat = 49.223,
            startLon = 18.739,
            availableMinutes = 210,
            interests = listOf("gallery", "religious_site"),
            pace = "fast",
            returnToStart = false,
            startDateTime = "2026-05-23T09:30:00",
            respectOpeningHours = false,
            excludedPoiIds = listOf(3, 5),
            preferredPoiIds = listOf(11, 13),
            transportMode = "walk_or_mhd"
        )

        assertEquals(preferences, preferences.toDto().toDomain())
    }

    @Test
    fun routePlanRoundTripPreservesStopsLegsSegmentsAndGeometry() {
        val routePlan = RoutePlan(
            city = "nitra",
            start = RoutePoint(lat = 48.309, lon = 18.086),
            startDateTime = "2026-05-23T10:15:00",
            pace = "normal",
            interests = listOf("museum", "park"),
            transportMode = "walk_or_mhd",
            returnToStart = true,
            respectOpeningHours = true,
            availableMinutes = 180,
            usedMinutes = 74,
            remainingMinutes = 106,
            totalVisitMinutes = 40,
            totalWalkMinutes = 22,
            returnToStartMinutes = 9,
            poiCount = 2,
            route = listOf(
                RouteStop(
                    order = 1,
                    poiId = 101,
                    name = "Museum",
                    category = "museum",
                    lat = 48.31,
                    lon = 18.09,
                    travelMinutesFromPrevious = 8,
                    visitDurationMin = 25,
                    arrivalAfterMin = 8,
                    departureAfterMin = 33,
                    baseScore = 4.3,
                    wikipediaUrl = "https://example.test/museum",
                    openingHoursRaw = "Mo-Fr 10:00-17:00"
                ),
                RouteStop(
                    order = 2,
                    poiId = 102,
                    name = "Park",
                    category = "park",
                    lat = 48.32,
                    lon = 18.1,
                    travelMinutesFromPrevious = 14,
                    visitDurationMin = 15,
                    arrivalAfterMin = 47,
                    departureAfterMin = 62,
                    baseScore = 4.1
                )
            ),
            legs = listOf(
                RouteLeg(
                    order = 1,
                    mode = "transit",
                    from = RouteLegEndpoint(type = "start", lat = 48.309, lon = 18.086),
                    to = RouteLegEndpoint(type = "poi", poiId = 101, name = "Museum", lat = 48.31, lon = 18.09),
                    durationSeconds = 480.0,
                    durationMinutes = 8,
                    distanceMeters = 1200.0,
                    geometry = listOf(
                        RouteCoordinate(lat = 48.309, lon = 18.086),
                        RouteCoordinate(lat = 48.31, lon = 18.09)
                    ),
                    routingSource = "test",
                    departureTime = "10:20",
                    arrivalTime = "10:28",
                    segments = listOf(
                        RouteSegment(
                            order = 1,
                            mode = "walk",
                            durationSeconds = 120.0,
                            durationMinutes = 2,
                            distanceMeters = 180.0,
                            geometry = listOf(RouteCoordinate(lat = 48.309, lon = 18.086)),
                            source = "foot",
                            lineName = null
                        ),
                        RouteSegment(
                            order = 2,
                            mode = "transit",
                            durationSeconds = 360.0,
                            durationMinutes = 6,
                            distanceMeters = 1020.0,
                            geometry = listOf(RouteCoordinate(lat = 48.31, lon = 18.09)),
                            source = "mhd",
                            lineName = "12",
                            fromStopName = "Centrum",
                            toStopName = "Museum",
                            departureTime = "10:22",
                            arrivalTime = "10:28",
                            waitMinutesBeforeDeparture = 1,
                            inVehicleMinutes = 5
                        )
                    )
                )
            ),
            fullGeometry = listOf(
                RouteCoordinate(lat = 48.309, lon = 18.086),
                RouteCoordinate(lat = 48.31, lon = 18.09),
                RouteCoordinate(lat = 48.32, lon = 18.1)
            )
        )

        assertEquals(routePlan, routePlan.toDto().toDomain())
    }

    @Test
    fun routeLegQueryMapsToRequestDto() {
        val query = RouteLegQuery(
            city = "levice",
            startLat = 48.22,
            startLon = 18.61,
            endLat = 48.23,
            endLon = 18.62,
            endPoiId = 501,
            endName = "Castle",
            startDateTime = "2026-05-23T14:00:00",
            pace = "slow",
            transportMode = "walk"
        )

        val dto = query.toDto()

        assertEquals(query.city, dto.city)
        assertEquals(query.startLat, dto.start_lat, 0.0)
        assertEquals(query.startLon, dto.start_lon, 0.0)
        assertEquals(query.endLat, dto.end_lat, 0.0)
        assertEquals(query.endLon, dto.end_lon, 0.0)
        assertEquals(query.endPoiId, dto.end_poi_id)
        assertEquals(query.endName, dto.end_name)
        assertEquals(query.startDateTime, dto.start_datetime)
        assertEquals(query.pace, dto.pace)
        assertEquals(query.transportMode, dto.transport_mode)
    }
}
