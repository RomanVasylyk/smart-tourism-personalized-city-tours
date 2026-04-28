package com.example.smarttourism.data.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object OfflineSyncScheduler {
    private const val ImmediateWorkName = "offline-sync-immediate"
    private const val PeriodicWorkName = "offline-sync-periodic"

    private val syncConstraints = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.CONNECTED)
        .build()

    fun scheduleImmediate(context: Context) {
        val request = OneTimeWorkRequestBuilder<OfflineSyncWorker>()
            .setConstraints(syncConstraints)
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                ImmediateWorkName,
                ExistingWorkPolicy.KEEP,
                request
            )
    }

    fun schedulePeriodic(context: Context) {
        val request = PeriodicWorkRequestBuilder<OfflineSyncWorker>(1, TimeUnit.HOURS)
            .setConstraints(syncConstraints)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork(
                PeriodicWorkName,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
    }

    fun scheduleOnAppStart(context: Context) {
        schedulePeriodic(context)
        scheduleImmediate(context)
    }
}
