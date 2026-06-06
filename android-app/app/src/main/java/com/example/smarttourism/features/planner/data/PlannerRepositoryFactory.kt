package com.example.smarttourism.features.planner.data

import android.content.Context
import com.example.smarttourism.core.network.ApiModule
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.features.planner.data.bookmark.DefaultRouteBookmarkRepository
import com.example.smarttourism.features.planner.data.local.DefaultPlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.ApiPlannerRemoteDataSource
import com.example.smarttourism.features.planner.data.session.DefaultRouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.DefaultOfflineSyncRepository

internal object PlannerRepositoryFactory {
    fun create(
        context: Context,
        api: PoiApi = ApiModule.poiApi
    ): PlannerRepository {
        val appContext = context.applicationContext
        val remoteDataSource = ApiPlannerRemoteDataSource(api)
        val localDataSource = DefaultPlannerLocalDataSource(appContext)
        return PlannerRepository(
            remoteDataSource = remoteDataSource,
            localDataSource = localDataSource,
            routeSessionRepository = DefaultRouteSessionRepository(
                remoteDataSource = remoteDataSource,
                localDataSource = localDataSource
            ),
            routeBookmarkRepository = DefaultRouteBookmarkRepository(localDataSource),
            offlineSyncRepository = DefaultOfflineSyncRepository(appContext)
        )
    }
}
