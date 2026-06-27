package com.example.smarttourism.features.planner.ui

import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import java.time.LocalDateTime

internal data class PlannerScreenState(
    val isParameterSheetOpen: Boolean,
    val isRequiredPlacesSheetOpen: Boolean,
    val isStopsSheetOpen: Boolean,
    val replacingPoiId: Int?,
    val selectedHistoryEntry: RouteHistoryEntry?,
    val isFeedbackDialogOpen: Boolean,
    val isMapFullScreen: Boolean,
    val pois: List<Poi>,
    val routeResponse: RoutePlan?,
    val routeItems: List<RouteStop>,
    val startPoint: RoutePoint,
    val selectedCity: City?,
    val currentRouteLocation: RoutePoint?,
    val isSelectingStart: Boolean,
    val isSelectingRequiredPlacesOnMap: Boolean,
    val isLocating: Boolean,
    val isPlannerEditingLocked: Boolean,
    val selectedRequiredPois: List<Poi>,
    val requiredPoiIds: List<Int>,
    val availableMinutes: Int,
    val maxAvailableMinutesLimit: Int,
    val selectedCityAvailableCategories: List<String>,
    val selectedInterests: List<String>,
    val pace: String,
    val returnToStart: Boolean,
    val respectOpeningHours: Boolean,
    val isPublicTransportAvailable: Boolean,
    val allowPublicTransport: Boolean,
    val startDateTime: LocalDateTime,
    val isRouteLoading: Boolean,
    val routeSessionStatus: RouteSessionStatus,
    val visitedPoiIds: List<Int>,
    val skippedPoiIds: List<Int>,
    val canSkipStops: Boolean,
    val isRerouting: Boolean,
    val highlightedPoiId: Int?,
    val routeFeedback: RouteFeedback?,
    val isPoiLoading: Boolean,
    val errorDialog: PlannerErrorDialogState?
)

internal data class PlannerErrorDialogState(
    val key: String,
    val title: String,
    val body: String,
    val canRetryRoute: Boolean,
    val canFallbackToWalking: Boolean
)

internal data class PlannerActions(
    val onDismissParameterSheet: () -> Unit,
    val onDismissRequiredPlacesSheet: () -> Unit,
    val onDismissStopsSheet: () -> Unit,
    val onDismissReplacementSheet: () -> Unit,
    val onToggleStartSelection: () -> Unit,
    val onUseCurrentLocation: () -> Unit,
    val onChooseRequiredOnMap: () -> Unit,
    val onChooseRequiredFromList: () -> Unit,
    val onMoveRequiredPoi: (Int, Int) -> Unit,
    val onRemoveRequiredPoi: (Int) -> Unit,
    val onClearRequiredPois: () -> Unit,
    val onAvailableMinutesChange: (Int) -> Unit,
    val onInterestToggle: (String, Boolean) -> Unit,
    val onPaceChange: (String) -> Unit,
    val onReturnToStartChange: (Boolean) -> Unit,
    val onRespectOpeningHoursChange: (Boolean) -> Unit,
    val onAllowPublicTransportChange: (Boolean) -> Unit,
    val onStartDateTimeChange: (LocalDateTime) -> Unit,
    val onUseCurrentTime: () -> Unit,
    val onGenerateRoute: () -> Unit,
    val onToggleRequiredPoi: (Int) -> Unit,
    val onUseBestReplacement: (Int) -> Unit,
    val onChooseReplacementCandidate: (targetPoiId: Int, preferredPoiId: Int) -> Unit,
    val onMarkVisited: (Int) -> Unit,
    val onSkip: (Int) -> Unit,
    val onDismissHistoryEntry: () -> Unit,
    val onDismissErrorDialog: () -> Unit,
    val onRetryRouteGeneration: () -> Unit,
    val onFallbackToWalking: () -> Unit,
    val onDismissFeedback: () -> Unit,
    val onFeedbackChange: (RouteFeedback) -> Unit,
    val onDismissFullScreenMap: () -> Unit,
    val onStartPointSelected: (Double, Double) -> Unit,
    val onRoutePoiSelected: (Poi) -> Unit
)
