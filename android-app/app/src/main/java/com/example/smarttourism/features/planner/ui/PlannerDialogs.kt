package com.example.smarttourism.features.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.smarttourism.R
import com.example.smarttourism.features.map.PoiMapScreen
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.components.RouteFeedbackCard
import com.example.smarttourism.features.planner.components.RouteHistoryDetailsDialog
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel

@Composable
internal fun PlannerDialogs(
    state: PlannerScreenState,
    actions: PlannerActions
) {
    state.errorDialog?.let { errorDialog ->
        AlertDialog(
            onDismissRequest = actions.onDismissErrorDialog,
            title = { Text(errorDialog.title) },
            text = { Text(errorDialog.body) },
            confirmButton = {
                Button(
                    onClick = if (errorDialog.canRetryRoute) {
                        actions.onRetryRouteGeneration
                    } else {
                        actions.onDismissErrorDialog
                    }
                ) {
                    Text(
                        stringResource(
                            if (errorDialog.canRetryRoute) {
                                R.string.action_retry_route_generation
                            } else {
                                R.string.action_done
                            }
                        )
                    )
                }
            },
            dismissButton = if (errorDialog.canFallbackToWalking) {
                {
                    OutlinedButton(onClick = actions.onFallbackToWalking) {
                        Text(stringResource(R.string.action_use_walking_only))
                    }
                }
            } else {
                null
            }
        )
    }

    state.selectedHistoryEntry?.let { historyEntry ->
        RouteHistoryDetailsDialog(
            entry = historyEntry,
            onDismiss = actions.onDismissHistoryEntry
        )
    }

    if (state.isFeedbackDialogOpen) {
        Dialog(onDismissRequest = actions.onDismissFeedback) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    RouteFeedbackCard(
                        feedback = state.routeFeedback,
                        onFeedbackChange = actions.onFeedbackChange,
                        framed = false
                    )
                }
                Button(
                    onClick = actions.onDismissFeedback,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }

    if (state.isMapFullScreen) {
        Dialog(
            onDismissRequest = actions.onDismissFullScreenMap,
            properties = DialogProperties(
                usePlatformDefaultWidth = false,
                decorFitsSystemWindows = false
            )
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                PoiMapScreen(
                    pois = state.pois,
                    routeResponse = state.routeResponse,
                    startLat = state.startPoint.lat,
                    startLon = state.startPoint.lon,
                    defaultZoom = state.selectedCity?.defaultZoom,
                    currentLocation = state.currentRouteLocation,
                    visitedPoiIds = state.visitedPoiIds.toSet(),
                    skippedPoiIds = state.skippedPoiIds.toSet(),
                    isRouteActive = state.routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                    isLoading = state.isPoiLoading,
                    isFullScreen = true,
                    isSelectingStart = state.isSelectingStart,
                    isSelectingRoutePois = state.isSelectingRequiredPlacesOnMap,
                    selectedRoutePoiIds = state.requiredPoiIds.toSet(),
                    recenterLocationRequestKey = state.mapRecenterRequestKey,
                    onCurrentLocationRequested = actions.onCurrentLocationRequested,
                    onStartPointSelected = actions.onStartPointSelected,
                    onRoutePoiSelected = actions.onRoutePoiSelected,
                    modifier = Modifier.fillMaxSize()
                )
                if (state.isSelectingRequiredPlacesOnMap) {
                    state.selectedMapPoiForAction?.let { poi ->
                        SelectedMapPoiCard(
                            poi = poi,
                            isSelected = poi.id in state.requiredPoiIds,
                            onDismiss = actions.onDismissMapPoiAction,
                            onConfirm = { actions.onConfirmMapPoiToggle(poi) },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .windowInsetsPadding(
                                    WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                                )
                                .padding(start = 16.dp, end = 16.dp, bottom = 88.dp)
                        )
                    }
                }
                OutlinedButton(
                    onClick = actions.onDismissFullScreenMap,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(16.dp)
                ) {
                    Text(stringResource(R.string.action_close_map))
                }
            }
        }
    }
}

@Composable
private fun SelectedMapPoiCard(
    poi: Poi,
    isSelected: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
    modifier: Modifier = Modifier
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.map_selected_poi_title),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = poi.name,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = categoryLabel(poi.category),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.action_dismiss_selected_place))
                }
                Button(onClick = onConfirm) {
                    Text(
                        stringResource(
                            if (isSelected) {
                                R.string.action_remove_required_place_from_route
                            } else {
                                R.string.action_add_required_place_to_route
                            }
                        )
                    )
                }
            }
        }
    }
}
