package com.example.smarttourism.features.planner.data.remote

import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.features.planner.data.mapper.toDomain
import com.example.smarttourism.features.planner.data.mapper.toDto
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.CuratedRoute
import com.example.smarttourism.features.planner.domain.model.CuratedRouteDetail
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSession
import javax.inject.Inject

internal interface PlannerRemoteDataSource {
    suspend fun fetchCities(): List<City>

    suspend fun fetchPois(citySlug: String): List<Poi>

    suspend fun fetchCuratedRoutes(citySlug: String): List<CuratedRoute>

    suspend fun fetchCuratedRoute(routeId: Int, startDateTime: String?): CuratedRouteDetail

    suspend fun generateRoute(request: PlannerPreferences): RoutePlan

    suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg

    suspend fun getRouteSession(routeId: String): RouteSession

    suspend fun getRouteSessions(deviceId: String): List<RouteSession>
}

internal class ApiPlannerRemoteDataSource @Inject constructor(
    private val api: PoiApi
) : PlannerRemoteDataSource {
    override suspend fun fetchCities(): List<City> =
        api.getCities().map { city -> city.toDomain() }

    override suspend fun fetchPois(citySlug: String): List<Poi> =
        api.getPois(citySlug).map { poi -> poi.toDomain() }

    override suspend fun fetchCuratedRoutes(citySlug: String): List<CuratedRoute> =
        api.getCuratedRoutes(citySlug).map { route -> route.toDomain() }

    override suspend fun fetchCuratedRoute(routeId: Int, startDateTime: String?): CuratedRouteDetail =
        api.getCuratedRoute(routeId, startDateTime).toDomain()

    override suspend fun generateRoute(request: PlannerPreferences): RoutePlan =
        api.generateRoute(request.toDto()).toDomain()

    override suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg =
        api.generateRouteLeg(request.toDto()).toDomain()

    override suspend fun getRouteSession(routeId: String): RouteSession =
        api.getRouteSession(routeId).toDomain()

    override suspend fun getRouteSessions(deviceId: String): List<RouteSession> =
        api.getRouteSessions(deviceId).map { session -> session.toDomain() }
}
