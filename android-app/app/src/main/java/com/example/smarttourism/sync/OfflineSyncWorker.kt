package com.example.smarttourism.sync

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.example.smarttourism.core.network.ApiModule
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.repository.OfflineCacheRepository

class OfflineSyncWorker(
    appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        if (!NetworkMonitor.isNetworkAvailable(applicationContext)) {
            return Result.retry()
        }

        if (OfflineCacheRepository.getPendingSyncOperationCount(applicationContext) == 0) {
            return Result.success()
        }

        val summary = runCatching {
            OfflineCacheRepository.syncPendingOperations(
                context = applicationContext,
                api = ApiModule.poiApi
            )
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
