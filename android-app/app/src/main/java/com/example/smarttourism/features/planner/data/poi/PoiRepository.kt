package com.example.smarttourism.features.planner.data.poi

import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.domain.model.Poi
import javax.inject.Inject

internal interface PoiRepository {
    suspend fun fetchPois(citySlug: String): List<Poi>

    suspend fun cachePois(citySlug: String, pois: List<Poi>)

    suspend fun getCachedPois(citySlug: String): List<Poi>
}

internal class DefaultPoiRepository @Inject constructor(
    private val remoteDataSource: PlannerRemoteDataSource,
    private val localDataSource: PlannerLocalDataSource
) : PoiRepository {
    override suspend fun fetchPois(citySlug: String): List<Poi> =
        remoteDataSource.fetchPois(citySlug)

    override suspend fun cachePois(citySlug: String, pois: List<Poi>) {
        localDataSource.cachePois(citySlug, pois)
    }

    override suspend fun getCachedPois(citySlug: String): List<Poi> =
        localDataSource.getCachedPois(citySlug)
}
