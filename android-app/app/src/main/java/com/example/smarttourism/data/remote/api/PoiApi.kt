package com.example.smarttourism.data.remote.api

import com.example.smarttourism.data.remote.dto.AccountUserDto
import com.example.smarttourism.data.remote.dto.AuthResponseDto
import com.example.smarttourism.data.remote.dto.CityDto
import com.example.smarttourism.data.remote.dto.CuratedRouteDetailDto
import com.example.smarttourism.data.remote.dto.CuratedRouteSummaryDto
import com.example.smarttourism.data.remote.dto.LinkDeviceResponseDto
import com.example.smarttourism.data.remote.dto.LoginRequest
import com.example.smarttourism.data.remote.dto.PoiDto
import com.example.smarttourism.data.remote.dto.RegisterRequest
import com.example.smarttourism.data.remote.dto.RouteFeedbackDto
import com.example.smarttourism.data.remote.dto.RouteFeedbackRequest
import com.example.smarttourism.data.remote.dto.RouteCheckRequest
import com.example.smarttourism.data.remote.dto.RouteCheckResponse
import com.example.smarttourism.data.remote.dto.RouteLegRequest
import com.example.smarttourism.data.remote.dto.RouteRequest
import com.example.smarttourism.data.remote.dto.RouteLegDto
import com.example.smarttourism.data.remote.dto.RouteResponse
import com.example.smarttourism.data.remote.dto.RouteSessionCreateRequest
import com.example.smarttourism.data.remote.dto.RouteSessionDto
import com.example.smarttourism.data.remote.dto.RouteSessionPoiDto
import com.example.smarttourism.data.remote.dto.RouteSessionPoiVisitRequest
import com.example.smarttourism.data.remote.dto.RouteSessionUpdateRequest
import com.example.smarttourism.data.remote.dto.StatusResponseDto
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.PATCH
import retrofit2.http.Path
import retrofit2.http.POST
import retrofit2.http.Query

interface PoiApi {
    @GET("cities")
    suspend fun getCities(): List<CityDto>

    @GET("pois")
    suspend fun getPois(
        @Query("city") city: String
    ): List<PoiDto>

    @GET("cities/{slug}/curated-routes")
    suspend fun getCuratedRoutes(
        @Path("slug") slug: String
    ): List<CuratedRouteSummaryDto>

    @GET("curated-routes/{routeId}")
    suspend fun getCuratedRoute(
        @Path("routeId") routeId: Int,
        @Query("start_datetime") startDatetime: String? = null
    ): CuratedRouteDetailDto

    @POST("route/generate")
    suspend fun generateRoute(
        @Body request: RouteRequest
    ): RouteResponse

    @POST("route/leg")
    suspend fun generateRouteLeg(
        @Body request: RouteLegRequest
    ): RouteLegDto

    @POST("route/check")
    suspend fun checkRoute(
        @Body request: RouteCheckRequest
    ): RouteCheckResponse

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

    @POST("auth/register")
    suspend fun register(
        @Body request: RegisterRequest
    ): AuthResponseDto

    @POST("auth/login")
    suspend fun login(
        @Body request: LoginRequest
    ): AuthResponseDto

    @POST("auth/logout")
    suspend fun logout(): StatusResponseDto

    @GET("auth/me")
    suspend fun getCurrentUser(): AccountUserDto

    @DELETE("auth/me")
    suspend fun deleteCurrentUser(): StatusResponseDto

    @POST("auth/link-device")
    suspend fun linkDevice(): LinkDeviceResponseDto

    @GET("auth/me/route-sessions")
    suspend fun getAccountRouteSessions(): List<RouteSessionDto>
}
