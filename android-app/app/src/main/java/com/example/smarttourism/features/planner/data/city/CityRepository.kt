package com.example.smarttourism.features.planner.data.city

import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.domain.model.City
import javax.inject.Inject

internal interface CityRepository {
    suspend fun fetchCities(): List<City>

    suspend fun cacheCities(cities: List<City>)

    suspend fun getCachedCities(): List<City>
}

internal class DefaultCityRepository @Inject constructor(
    private val remoteDataSource: PlannerRemoteDataSource,
    private val localDataSource: PlannerLocalDataSource
) : CityRepository {
    override suspend fun fetchCities(): List<City> =
        remoteDataSource.fetchCities()

    override suspend fun cacheCities(cities: List<City>) {
        localDataSource.cacheCities(cities)
    }

    override suspend fun getCachedCities(): List<City> =
        localDataSource.getCachedCities()
}
