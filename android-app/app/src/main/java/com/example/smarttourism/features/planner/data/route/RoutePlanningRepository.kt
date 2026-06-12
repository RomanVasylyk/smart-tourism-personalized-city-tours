package com.example.smarttourism.features.planner.data.route

import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.RouteLegQuery
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import javax.inject.Inject

internal interface RoutePlanningRepository {
    suspend fun generateRoute(request: PlannerPreferences): RoutePlan

    suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg

    suspend fun saveSnapshot(snapshot: SavedRouteSnapshot)

    suspend fun loadSnapshot(): SavedRouteSnapshot?
}

internal class DefaultRoutePlanningRepository @Inject constructor(
    private val remoteDataSource: PlannerRemoteDataSource,
    private val localDataSource: PlannerLocalDataSource
) : RoutePlanningRepository {
    override suspend fun generateRoute(request: PlannerPreferences): RoutePlan =
        remoteDataSource.generateRoute(request)

    override suspend fun generateRouteLeg(request: RouteLegQuery): RouteLeg =
        remoteDataSource.generateRouteLeg(request)

    override suspend fun saveSnapshot(snapshot: SavedRouteSnapshot) {
        localDataSource.saveSnapshot(snapshot)
    }

    override suspend fun loadSnapshot(): SavedRouteSnapshot? =
        localDataSource.loadSnapshot()
}
