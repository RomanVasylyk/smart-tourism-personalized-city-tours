package com.example.smarttourism.features.planner.viewmodel

import com.example.smarttourism.features.planner.application.OfflineMapController
import com.example.smarttourism.features.planner.state.OfflineDownloadProgress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.Locale

internal class PlannerOfflineMapActions(
    private val state: PlannerStateStore,
    private val scope: CoroutineScope,
    private val offlineMapController: OfflineMapController,
    private val messages: PlannerMessages
) {
    fun downloadOfflineMap() {
        val city = state.selectedCity ?: return
        state.isOfflineMapBusy = true
        state.offlineMapProgress = OfflineDownloadProgress(0, 0, 0.0)
        state.offlineMapMessage = null
        val started = offlineMapController.downloadCityRegion(
            city = city,
            onProgress = { completed, required, percent ->
                state.offlineMapProgress = OfflineDownloadProgress(
                    completed = completed,
                    required = required,
                    percent = percent
                )
            },
            onComplete = {
                state.isOfflineMapBusy = false
                state.offlineMapMessage = String.format(
                    Locale.getDefault(),
                    messages.offlineMapDownloaded,
                    city.name
                )
                scope.launch {
                    state.offlineStoredRegion = offlineMapController.findStoredRegion(city.slug)
                }
            },
            onError = { error ->
                state.isOfflineMapBusy = false
                state.offlineMapMessage = "${messages.offlineMapDownloadFailed} $error"
            }
        )
        if (!started) {
            state.isOfflineMapBusy = false
            state.offlineMapProgress = null
            state.offlineMapMessage = null
        }
    }

    fun deleteOfflineMap() {
        val city = state.selectedCity ?: return
        val storedRegion = state.offlineStoredRegion ?: return

        state.isOfflineMapBusy = true
        offlineMapController.deleteRegion(
            storedRegion = storedRegion,
            onComplete = {
                state.isOfflineMapBusy = false
                state.offlineMapProgress = null
                state.offlineStoredRegion = null
                state.offlineMapMessage = String.format(
                    Locale.getDefault(),
                    messages.offlineMapDeleted,
                    city.name
                )
            },
            onError = { error ->
                state.isOfflineMapBusy = false
                state.offlineMapMessage = "${messages.offlineMapDeleteFailed} $error"
            }
        )
    }
}
