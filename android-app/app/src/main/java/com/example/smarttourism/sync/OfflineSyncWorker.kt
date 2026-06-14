package com.example.smarttourism.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.remote.api.PoiApi
import com.example.smarttourism.data.repository.OfflineCacheStore
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

class OfflineSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!NetworkMonitor.isNetworkAvailable(applicationContext)) {
            return Result.retry()
        }

        val dependencies = EntryPointAccessors.fromApplication(
            applicationContext,
            OfflineSyncWorkerDependencies::class.java
        )
        val offlineCacheStore = dependencies.offlineCacheStore()
        if (offlineCacheStore.getPendingSyncOperationCount() == 0) {
            return Result.success()
        }

        val summary = runCatching {
            offlineCacheStore.syncPendingOperations(dependencies.poiApi())
        }.getOrElse {
            return Result.retry()
        }

        return if (summary.hasFailures) {
            Result.retry()
        } else {
            Result.success()
        }
    }
}

@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface OfflineSyncWorkerDependencies {
    fun offlineCacheStore(): OfflineCacheStore

    fun poiApi(): PoiApi
}
