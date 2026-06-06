package com.example.smarttourism.di

import com.example.smarttourism.core.network.ApiModule
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.features.planner.data.bookmark.DefaultRouteBookmarkRepository
import com.example.smarttourism.features.planner.data.bookmark.RouteBookmarkRepository
import com.example.smarttourism.features.planner.data.local.DefaultPlannerLocalDataSource
import com.example.smarttourism.features.planner.data.local.PlannerLocalDataSource
import com.example.smarttourism.features.planner.data.remote.ApiPlannerRemoteDataSource
import com.example.smarttourism.features.planner.data.remote.PlannerRemoteDataSource
import com.example.smarttourism.features.planner.data.session.DefaultRouteSessionRepository
import com.example.smarttourism.features.planner.data.session.RouteSessionRepository
import com.example.smarttourism.features.planner.data.sync.DefaultOfflineSyncRepository
import com.example.smarttourism.features.planner.data.sync.OfflineSyncRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
internal abstract class PlannerBindingsModule {
    @Binds
    @Singleton
    abstract fun bindPlannerRemoteDataSource(
        dataSource: ApiPlannerRemoteDataSource
    ): PlannerRemoteDataSource

    @Binds
    @Singleton
    abstract fun bindPlannerLocalDataSource(
        dataSource: DefaultPlannerLocalDataSource
    ): PlannerLocalDataSource

    @Binds
    @Singleton
    abstract fun bindRouteSessionRepository(
        repository: DefaultRouteSessionRepository
    ): RouteSessionRepository

    @Binds
    @Singleton
    abstract fun bindRouteBookmarkRepository(
        repository: DefaultRouteBookmarkRepository
    ): RouteBookmarkRepository

    @Binds
    @Singleton
    abstract fun bindOfflineSyncRepository(
        repository: DefaultOfflineSyncRepository
    ): OfflineSyncRepository
}

@Module
@InstallIn(SingletonComponent::class)
internal object PlannerNetworkModule {
    @Provides
    @Singleton
    fun providePoiApi(): PoiApi = ApiModule.poiApi
}
