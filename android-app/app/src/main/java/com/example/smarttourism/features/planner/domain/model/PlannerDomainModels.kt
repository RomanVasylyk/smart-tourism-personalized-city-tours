package com.example.smarttourism.features.planner.domain.model

data class City(
    val id: Int = 0,
    val slug: String = "",
    val name: String = "",
    val country: String = "",
    val centerLat: Double = 0.0,
    val centerLon: Double = 0.0,
    val bbox: CityBounds? = null,
    val availableCategories: List<String>? = emptyList(),
    val defaultZoom: Double? = null,
    val routingLimits: RoutingLimits? = null,
    val transport: TransportProfile? = null
)

data class CityBounds(
    val south: Double = 0.0,
    val west: Double = 0.0,
    val north: Double = 0.0,
    val east: Double = 0.0
)

data class RoutingLimits(
    val maxAvailableMinutes: Int? = null,
    val maxPoiCandidates: Int? = null
)

data class TransportProfile(
    val mhdEnabled: Boolean? = null,
    val provider: String? = null,
    val mode: String? = null
)

data class Poi(
    val id: Int = 0,
    val name: String = "",
    val category: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val openingHoursRaw: String? = null,
    val visitDurationMin: Int? = null,
    val baseScore: Double? = null,
    val shortDescription: String? = null,
    val wikipediaUrl: String? = null
)

data class PlannerPreferences(
    val city: String = "nitra",
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val availableMinutes: Int = 0,
    val interests: List<String> = emptyList(),
    val pace: String = "normal",
    val returnToStart: Boolean = true,
    val startDateTime: String? = null,
    val respectOpeningHours: Boolean = true,
    val excludedPoiIds: List<Int> = emptyList(),
    val preferredPoiIds: List<Int> = emptyList(),
    val transportMode: String? = "walk"
)

data class RoutePlan(
    val city: String = "",
    val start: RoutePoint = RoutePoint(),
    val startDateTime: String? = null,
    val pace: String = "normal",
    val interests: List<String> = emptyList(),
    val transportMode: String? = null,
    val returnToStart: Boolean = true,
    val respectOpeningHours: Boolean = true,
    val availableMinutes: Int = 0,
    val usedMinutes: Int = 0,
    val remainingMinutes: Int = 0,
    val totalVisitMinutes: Int = 0,
    val totalWalkMinutes: Int = 0,
    val returnToStartMinutes: Int = 0,
    val poiCount: Int = 0,
    val totalWaitMinutes: Int = 0,
    val requiredPoisOverBudget: Boolean = false,
    val closedRequiredPoiIds: List<Int> = emptyList(),
    val routingDegraded: Boolean = false,
    val route: List<RouteStop> = emptyList(),
    val legs: List<RouteLeg>? = null,
    val fullGeometry: List<RouteCoordinate>? = null
)

data class RoutePoint(
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

data class CuratedRoute(
    val id: Int = 0,
    val slug: String? = null,
    val title: String = "",
    val summary: String? = null,
    val theme: String? = null,
    val city: String? = null,
    val recommendedDurationMin: Int? = null,
    val pace: String? = null,
    val transportMode: String? = null,
    val returnToStart: Boolean? = null,
    val poiCount: Int = 0,
    val stopNames: List<String> = emptyList()
)

data class CuratedRouteDetail(
    val id: Int = 0,
    val slug: String? = null,
    val title: String = "",
    val summary: String? = null,
    val theme: String? = null,
    val city: String? = null,
    val recommendedDurationMin: Int? = null,
    val pace: String? = null,
    val transportMode: String? = null,
    val returnToStart: Boolean? = null,
    val poiCount: Int = 0,
    val route: RoutePlan = RoutePlan()
)

data class RouteStop(
    val order: Int = 0,
    val poiId: Int = 0,
    val name: String = "",
    val category: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    val travelMinutesFromPrevious: Int = 0,
    val visitDurationMin: Int = 0,
    val waitMinutes: Int = 0,
    val arrivalAfterMin: Int = 0,
    val departureAfterMin: Int = 0,
    val baseScore: Double? = null,
    val shortDescription: String? = null,
    val wikipediaUrl: String? = null,
    val openingHoursRaw: String? = null
)

data class RouteLeg(
    val order: Int = 0,
    val mode: String? = null,
    val from: RouteLegEndpoint = RouteLegEndpoint(),
    val to: RouteLegEndpoint = RouteLegEndpoint(),
    val durationSeconds: Double = 0.0,
    val durationMinutes: Int = 0,
    val distanceMeters: Double = 0.0,
    val geometry: List<RouteCoordinate> = emptyList(),
    val routingSource: String? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val segments: List<RouteSegment>? = null
)

data class RouteLegEndpoint(
    val type: String = "",
    val poiId: Int? = null,
    val name: String? = null,
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

data class RouteCoordinate(
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

data class RouteSegment(
    val order: Int? = null,
    val mode: String? = null,
    val durationSeconds: Double? = null,
    val durationMinutes: Int? = null,
    val distanceMeters: Double? = null,
    val geometry: List<RouteCoordinate>? = null,
    val source: String? = null,
    val lineName: String? = null,
    val fromStopName: String? = null,
    val toStopName: String? = null,
    val departureTime: String? = null,
    val arrivalTime: String? = null,
    val waitMinutesBeforeDeparture: Int? = null,
    val inVehicleMinutes: Int? = null
)

data class RouteLegQuery(
    val city: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val endPoiId: Int,
    val endName: String?,
    val startDateTime: String?,
    val pace: String,
    val transportMode: String
)

data class RouteSession(
    val id: String = "",
    val deviceId: String = "",
    val cityId: Int = 0,
    val cityName: String? = null,
    val status: String = "",
    val startLat: Double = 0.0,
    val startLon: Double = 0.0,
    val availableMinutes: Int = 0,
    val pace: String = "normal",
    val returnToStart: Boolean = true,
    val openingHoursEnabled: Boolean = true,
    val startedAt: String = "",
    val finishedAt: String? = null,
    val usedMinutes: Int? = null,
    val totalWalkMinutes: Int? = null,
    val totalVisitMinutes: Int? = null,
    val routeSnapshot: RoutePlan? = null,
    val pois: List<RouteSessionStop> = emptyList(),
    val feedback: List<RouteSessionFeedback> = emptyList()
)

data class RouteSessionStop(
    val id: Int = 0,
    val sessionId: String = "",
    val poiId: Int = 0,
    val visitOrder: Int = 0,
    val plannedArrivalMin: Int? = null,
    val plannedDepartureMin: Int? = null,
    val visited: Boolean = false,
    val visitedAt: String? = null,
    val skipped: Boolean = false
)

data class RouteSessionFeedback(
    val rating: Int = 0,
    val wasConvenient: Boolean? = null,
    val tooMuchWalking: Boolean? = null,
    val poisWereInteresting: Boolean? = null
)
