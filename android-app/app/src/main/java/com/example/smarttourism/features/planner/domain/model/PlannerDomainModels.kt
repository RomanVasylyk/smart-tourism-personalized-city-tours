package com.example.smarttourism.features.planner.domain.model

import com.google.gson.annotations.SerializedName

data class City(
    val id: Int = 0,
    val slug: String = "",
    val name: String = "",
    val country: String = "",
    @SerializedName("center_lat")
    val centerLat: Double = 0.0,
    @SerializedName("center_lon")
    val centerLon: Double = 0.0,
    val bbox: CityBounds? = null,
    @SerializedName("available_categories")
    val availableCategories: List<String>? = emptyList(),
    @SerializedName("default_zoom")
    val defaultZoom: Double? = null,
    @SerializedName("routing_limits")
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
    @SerializedName("max_available_minutes")
    val maxAvailableMinutes: Int? = null,
    @SerializedName("max_poi_candidates")
    val maxPoiCandidates: Int? = null
)

data class TransportProfile(
    @SerializedName("mhd_enabled")
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
    @SerializedName("opening_hours_raw")
    val openingHoursRaw: String? = null,
    @SerializedName("visit_duration_min")
    val visitDurationMin: Int? = null,
    @SerializedName("base_score")
    val baseScore: Double? = null,
    @SerializedName("wikipedia_url")
    val wikipediaUrl: String? = null
)

data class PlannerPreferences(
    val city: String = "nitra",
    @SerializedName("start_lat")
    val startLat: Double = 0.0,
    @SerializedName("start_lon")
    val startLon: Double = 0.0,
    @SerializedName("available_minutes")
    val availableMinutes: Int = 0,
    val interests: List<String> = emptyList(),
    val pace: String = "normal",
    @SerializedName("return_to_start")
    val returnToStart: Boolean = true,
    @SerializedName("start_datetime")
    val startDateTime: String? = null,
    @SerializedName("respect_opening_hours")
    val respectOpeningHours: Boolean = true,
    @SerializedName("exclude_poi_ids")
    val excludedPoiIds: List<Int> = emptyList(),
    @SerializedName("preferred_poi_ids")
    val preferredPoiIds: List<Int> = emptyList(),
    @SerializedName("transport_mode")
    val transportMode: String? = "walk"
)

data class RoutePlan(
    val city: String = "",
    val start: RoutePoint = RoutePoint(),
    @SerializedName("start_datetime")
    val startDateTime: String? = null,
    val pace: String = "normal",
    val interests: List<String> = emptyList(),
    @SerializedName("transport_mode")
    val transportMode: String? = null,
    @SerializedName("return_to_start")
    val returnToStart: Boolean = true,
    @SerializedName("respect_opening_hours")
    val respectOpeningHours: Boolean = true,
    @SerializedName("available_minutes")
    val availableMinutes: Int = 0,
    @SerializedName("used_minutes")
    val usedMinutes: Int = 0,
    @SerializedName("remaining_minutes")
    val remainingMinutes: Int = 0,
    @SerializedName("total_visit_minutes")
    val totalVisitMinutes: Int = 0,
    @SerializedName("total_walk_minutes")
    val totalWalkMinutes: Int = 0,
    @SerializedName("return_to_start_minutes")
    val returnToStartMinutes: Int = 0,
    @SerializedName("poi_count")
    val poiCount: Int = 0,
    val route: List<RouteStop> = emptyList(),
    val legs: List<RouteLeg>? = null,
    @SerializedName("full_geometry")
    val fullGeometry: List<RouteCoordinate>? = null
)

data class RoutePoint(
    val lat: Double = 0.0,
    val lon: Double = 0.0
)

data class RouteStop(
    val order: Int = 0,
    @SerializedName("poi_id")
    val poiId: Int = 0,
    val name: String = "",
    val category: String = "",
    val lat: Double = 0.0,
    val lon: Double = 0.0,
    @SerializedName("travel_minutes_from_previous")
    val travelMinutesFromPrevious: Int = 0,
    @SerializedName("visit_duration_min")
    val visitDurationMin: Int = 0,
    @SerializedName("arrival_after_min")
    val arrivalAfterMin: Int = 0,
    @SerializedName("departure_after_min")
    val departureAfterMin: Int = 0,
    @SerializedName("base_score")
    val baseScore: Double? = null,
    @SerializedName("wikipedia_url")
    val wikipediaUrl: String? = null,
    @SerializedName("opening_hours_raw")
    val openingHoursRaw: String? = null
)

data class RouteLeg(
    val order: Int = 0,
    val mode: String? = null,
    val from: RouteLegEndpoint = RouteLegEndpoint(),
    val to: RouteLegEndpoint = RouteLegEndpoint(),
    @SerializedName("duration_seconds")
    val durationSeconds: Double = 0.0,
    @SerializedName("duration_minutes")
    val durationMinutes: Int = 0,
    @SerializedName("distance_meters")
    val distanceMeters: Double = 0.0,
    val geometry: List<RouteCoordinate> = emptyList(),
    @SerializedName("routing_source")
    val routingSource: String? = null,
    @SerializedName("departure_time")
    val departureTime: String? = null,
    @SerializedName("arrival_time")
    val arrivalTime: String? = null,
    val segments: List<RouteSegment>? = null
)

data class RouteLegEndpoint(
    val type: String = "",
    @SerializedName("poi_id")
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
    @SerializedName("duration_seconds")
    val durationSeconds: Double? = null,
    @SerializedName("duration_minutes")
    val durationMinutes: Int? = null,
    @SerializedName("distance_meters")
    val distanceMeters: Double? = null,
    val geometry: List<RouteCoordinate>? = null,
    val source: String? = null,
    @SerializedName("line_name")
    val lineName: String? = null,
    @SerializedName("from_stop_name")
    val fromStopName: String? = null,
    @SerializedName("to_stop_name")
    val toStopName: String? = null,
    @SerializedName("departure_time")
    val departureTime: String? = null,
    @SerializedName("arrival_time")
    val arrivalTime: String? = null,
    @SerializedName("wait_minutes_before_departure")
    val waitMinutesBeforeDeparture: Int? = null,
    @SerializedName("in_vehicle_minutes")
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
