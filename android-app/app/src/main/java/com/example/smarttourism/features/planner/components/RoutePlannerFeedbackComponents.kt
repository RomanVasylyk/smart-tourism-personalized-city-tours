package com.example.smarttourism.features.planner.components

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Looper
import android.text.format.DateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.smarttourism.data.model.ActiveRouteSession
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.core.network.ApiModule
import com.example.smarttourism.features.planner.domain.model.City
import com.example.smarttourism.core.platform.NetworkMonitor
import com.example.smarttourism.data.repository.OfflineCacheRepository
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.RouteLeg
import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RoutePlan
import com.example.smarttourism.features.planner.domain.model.RouteSegment
import com.example.smarttourism.features.planner.domain.model.RouteSession
import com.example.smarttourism.features.planner.domain.model.RoutePoint
import com.example.smarttourism.data.repository.RouteStorage
import com.example.smarttourism.features.planner.domain.model.RouteStop
import com.example.smarttourism.data.model.SavedRouteSnapshot
import com.example.smarttourism.sync.OfflineSyncScheduler
import com.example.smarttourism.R
import com.example.smarttourism.features.map.offline.OfflineCityRegion
import com.example.smarttourism.features.map.offline.OfflineMapManager
import com.example.smarttourism.features.map.offline.OfflineStoredRegion
import com.example.smarttourism.features.planner.state.AvailableMinutesStepMinutes
import com.example.smarttourism.features.planner.state.MinimumAvailableMinutes
import com.example.smarttourism.features.planner.state.OfflineDownloadProgress
import com.example.smarttourism.features.planner.state.PaceOptions
import com.example.smarttourism.features.planner.state.PoiVisitedRadiusMeters
import com.example.smarttourism.features.planner.state.RouteProgressMetrics
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.state.RouteTimeFormatter
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel
import com.example.smarttourism.features.planner.ui.formatters.formatCoordinate
import com.example.smarttourism.features.planner.ui.formatters.formatDistanceMeters
import com.example.smarttourism.features.planner.ui.formatters.paceLabel
import com.example.smarttourism.features.planner.domain.route.requiredCompletionCount
import com.example.smarttourism.features.planner.ui.formatters.routeSegmentLabel
import com.example.smarttourism.features.planner.ui.formatters.routeSessionStatusLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteDateTimeLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteTimeOfDayLabel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

@Composable
internal fun RouteFeedbackCard(
    feedback: RouteFeedback?,
    onFeedbackChange: (RouteFeedback) -> Unit,
    framed: Boolean = true
) {
    val currentFeedback = feedback ?: RouteFeedback(
        rating = 0,
        route_was_comfortable = false,
        too_much_walking = false,
        pois_were_interesting = false
    )

    if (framed) {
        ElevatedCard {
            RouteFeedbackContent(
                currentFeedback = currentFeedback,
                onFeedbackChange = onFeedbackChange
            )
        }
    } else {
        RouteFeedbackContent(
            currentFeedback = currentFeedback,
            onFeedbackChange = onFeedbackChange
        )
    }
}

@Composable
private fun RouteFeedbackContent(
    currentFeedback: RouteFeedback,
    onFeedbackChange: (RouteFeedback) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.route_feedback_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = if (currentFeedback.rating > 0) {
                    stringResource(R.string.route_feedback_rating_selected, currentFeedback.rating)
                } else {
                    stringResource(R.string.route_feedback_rating_body)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stringResource(R.string.route_feedback_rating_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                FeedbackRatingSelector(
                    selectedRating = currentFeedback.rating,
                    onRatingSelected = { rating ->
                        onFeedbackChange(currentFeedback.copy(rating = rating))
                    }
                )
            }
        }

        FeedbackSwitchRow(
            title = stringResource(R.string.route_feedback_comfortable),
            checked = currentFeedback.route_was_comfortable,
            onCheckedChange = { checked ->
                onFeedbackChange(currentFeedback.copy(route_was_comfortable = checked))
            }
        )
        FeedbackSwitchRow(
            title = stringResource(R.string.route_feedback_too_much_walking),
            checked = currentFeedback.too_much_walking,
            onCheckedChange = { checked ->
                onFeedbackChange(currentFeedback.copy(too_much_walking = checked))
            }
        )
        FeedbackSwitchRow(
            title = stringResource(R.string.route_feedback_interesting_pois),
            checked = currentFeedback.pois_were_interesting,
            onCheckedChange = { checked ->
                onFeedbackChange(currentFeedback.copy(pois_were_interesting = checked))
            }
        )
    }
}

@Composable
private fun FeedbackRatingSelector(
    selectedRating: Int,
    onRatingSelected: (Int) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        (1..5).forEach { rating ->
            val selected = selectedRating == rating
            val shape = RoundedCornerShape(14.dp)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .border(
                        width = 1.dp,
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = shape
                    )
                    .clip(shape)
                    .background(
                        if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                    )
                    .clickable { onRatingSelected(rating) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = rating.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (selected) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
internal fun FeedbackSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    val shape = RoundedCornerShape(16.dp)
    val containerColor = if (checked) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (checked) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outlineVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, borderColor, shape)
            .clip(shape)
            .background(containerColor)
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (checked) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.outline
                    }
                )
        )
        Text(
            text = title,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            color = if (checked) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onSurface
            },
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}
