package com.example.smarttourism.features.planner.data.curated

import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.domain.model.CuratedRoute
import com.example.smarttourism.features.planner.domain.model.CuratedRouteDetail
import javax.inject.Inject

internal interface CuratedRouteRepository {
    suspend fun fetchCuratedRoutes(citySlug: String): List<CuratedRoute>

    suspend fun fetchCuratedRoute(routeId: Int, startDateTime: String?): CuratedRouteDetail
}

internal class DefaultCuratedRouteRepository @Inject constructor(
    private val remoteDataSource: PlannerRemoteDataSource
) : CuratedRouteRepository {
    override suspend fun fetchCuratedRoutes(citySlug: String): List<CuratedRoute> =
        remoteDataSource.fetchCuratedRoutes(citySlug)

    override suspend fun fetchCuratedRoute(routeId: Int, startDateTime: String?): CuratedRouteDetail =
        remoteDataSource.fetchCuratedRoute(routeId, startDateTime)
}
