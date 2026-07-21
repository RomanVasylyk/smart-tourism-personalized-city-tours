package com.example.smarttourism.features.planner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.components.ReplaceRouteStopSheetContent
import com.example.smarttourism.features.planner.components.RequiredPlacesCard
import com.example.smarttourism.features.planner.components.RequiredPlacesPickerSheetContent
import com.example.smarttourism.features.planner.components.RouteParametersCard
import com.example.smarttourism.features.planner.components.StartPointCard
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.state.RouteSessionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlannerSheets(
    state: PlannerScreenState,
    actions: PlannerActions,
    replacementCandidatesForPoi: (Int) -> List<Poi>
) {
    if (state.isParameterSheetOpen) {
        ModalBottomSheet(onDismissRequest = actions.onDismissParameterSheet) {
            ParameterSheetContent(
                state = state,
                actions = actions
            )
        }
    }

    if (state.isRequiredPlacesSheetOpen) {
        ModalBottomSheet(onDismissRequest = actions.onDismissRequiredPlacesSheet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
            ) {
                RequiredPlacesPickerSheetContent(
                    pois = state.pois,
                    selectedPoiIds = state.requiredPoiIds,
                    isEditingEnabled = !state.isPlannerEditingLocked,
                    onTogglePoi = actions.onToggleRequiredPoi
                )
            }
        }
    }

    state.replacingPoiId?.let { targetPoiId ->
        val targetStop = state.routeItems.firstOrNull { item -> item.poiId == targetPoiId }
        if (targetStop != null) {
            ModalBottomSheet(onDismissRequest = actions.onDismissReplacementSheet) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 32.dp)
                ) {
                    ReplaceRouteStopSheetContent(
                        targetStopName = targetStop.name,
                        candidates = replacementCandidatesForPoi(targetPoiId),
                        isActionInProgress = state.isRerouting,
                        onUseBestSuggestion = { actions.onUseBestReplacement(targetPoiId) },
                        onChooseCandidate = { preferredPoiId ->
                            actions.onChooseReplacementCandidate(targetPoiId, preferredPoiId)
                        }
                    )
                }
            }
        }
    }

    if (state.isStopsSheetOpen) {
        ModalBottomSheet(onDismissRequest = actions.onDismissStopsSheet) {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                routeStopItems(
                    titleRes = R.string.route_section_all_stops,
                    routeStops = state.routeItems,
                    routeResponse = state.routeResponse,
                    visitedPoiIds = state.visitedPoiIds,
                    skippedPoiIds = state.skippedPoiIds,
                    isRouteActive = state.routeSessionStatus == RouteSessionStatus.IN_PROGRESS,
                    canSkip = state.canSkipStops,
                    canReplace = false,
                    canMove = false,
                    isActionInProgress = state.isRerouting,
                    highlightedPoiId = state.highlightedPoiId,
                    onMarkVisited = actions.onMarkVisited,
                    onSkip = actions.onSkip,
                    onMove = { _, _ -> },
                    onReplace = {}
                )
            }
        }
    }
}

@Composable
private fun ParameterSheetContent(
    state: PlannerScreenState,
    actions: PlannerActions
) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.route_preview_edit_title),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = stringResource(R.string.route_preview_edit_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        item {
            StartPointCard(
                startPoint = state.startPoint,
                isSelectingStart = state.isSelectingStart,
                isLocating = state.isLocating,
                enabled = !state.isPlannerEditingLocked,
                onToggleMapSelection = actions.onToggleStartSelection,
                onUseCurrentLocation = actions.onUseCurrentLocation
            )
        }

        item {
            RequiredPlacesCard(
                selectedPois = state.selectedRequiredPois,
                availablePoiCount = state.pois.size,
                isEditingEnabled = !state.isPlannerEditingLocked,
                onChooseOnMap = actions.onChooseRequiredOnMap,
                onChooseFromList = actions.onChooseRequiredFromList,
                onRemovePoi = actions.onRemoveRequiredPoi,
                onClearAll = actions.onClearRequiredPois
            )
        }

        item {
            RouteParametersCard(
                availableMinutes = state.availableMinutes,
                maxAvailableMinutes = state.maxAvailableMinutesLimit,
                onAvailableMinutesChange = actions.onAvailableMinutesChange,
                availableInterests = state.selectedCityAvailableCategories,
                selectedInterests = state.selectedInterests,
                onInterestToggle = actions.onInterestToggle,
                pace = state.pace,
                onPaceChange = actions.onPaceChange,
                returnToStart = state.returnToStart,
                onReturnToStartChange = actions.onReturnToStartChange,
                respectOpeningHours = state.respectOpeningHours,
                onRespectOpeningHoursChange = actions.onRespectOpeningHoursChange,
                isPublicTransportAvailable = state.isPublicTransportAvailable,
                allowPublicTransport = state.allowPublicTransport,
                onAllowPublicTransportChange = actions.onAllowPublicTransportChange,
                startDateTime = state.startDateTime,
                onStartDateTimeChange = actions.onStartDateTimeChange,
                onUseCurrentTime = actions.onUseCurrentTime,
                isEditingEnabled = !state.isPlannerEditingLocked,
                isGenerating = state.isRouteLoading,
                onGenerateRoute = actions.onGenerateRoute
            )
        }
    }
}
