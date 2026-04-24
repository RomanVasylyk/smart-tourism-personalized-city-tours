package com.example.smarttourism.data

import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query

interface PoiApi {
    @GET("pois")
    suspend fun getPois(
        @Query("city") city: String = "nitra"
    ): List<PoiDto>

    @POST("route/generate")
    suspend fun generateRoute(
        @Body request: RouteRequest
    ): RouteResponse

    @POST("route-sessions")
    suspend fun createRouteSession(
        @Body request: RouteSessionCreateRequest
    ): RouteSessionDto

    @PATCH("route-sessions/{sessionId}")
    suspend fun updateRouteSession(
        @Path("sessionId") sessionId: String,
        @Body request: RouteSessionUpdateRequest
    ): RouteSessionDto

    @POST("route-sessions/{sessionId}/pois/{poiId}/visit")
    suspend fun markRouteSessionPoiVisited(
        @Path("sessionId") sessionId: String,
        @Path("poiId") poiId: Int,
        @Body request: RouteSessionPoiVisitRequest
    ): RouteSessionPoiDto

    @POST("route-sessions/{sessionId}/feedback")
    suspend fun saveRouteFeedback(
        @Path("sessionId") sessionId: String,
        @Body request: RouteFeedbackRequest
    ): RouteFeedbackDto

    @GET("route-sessions/{sessionId}")
    suspend fun getRouteSession(
        @Path("sessionId") sessionId: String
    ): RouteSessionDto

    @GET("route-sessions")
    suspend fun getRouteSessions(
        @Query("device_id") deviceId: String
    ): List<RouteSessionDto>
}
