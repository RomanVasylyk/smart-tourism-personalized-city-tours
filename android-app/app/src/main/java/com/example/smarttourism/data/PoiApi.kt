package com.example.smarttourism.data

import retrofit2.http.Body
import retrofit2.http.GET
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
}
