package com.example.smarttourism.features.planner.components

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.smarttourism.R
import com.example.smarttourism.data.model.RouteBookmark
import com.example.smarttourism.data.model.RouteFeedback
import com.example.smarttourism.data.model.RouteHistoryEntry
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.state.RouteSessionStatus
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel
import com.example.smarttourism.features.planner.ui.formatters.routeSessionStatusLabel
import com.example.smarttourism.features.planner.ui.formatters.toRouteDateTimeLabel

@Composable
internal fun RouteBookmarksSheetContent(
    bookmarks: List<RouteBookmark>,
    activeBookmarkId: String?,
    onOpenBookmark: (String) -> Unit,
    onDeleteBookmark: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.route_bookmarks_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_bookmarks_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (bookmarks.isEmpty()) {
            Text(
                text = stringResource(R.string.route_bookmarks_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                bookmarks.forEach { bookmark ->
                    Surface(
                        shape = RoundedCornerShape(PlannerUiTokens.CardRadius),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(PlannerUiTokens.CardPadding),
                            verticalArrangement = Arrangement.spacedBy(PlannerUiTokens.ItemSpacing)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = bookmark.title,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = stringResource(
                                            R.string.route_bookmark_meta,
                                            bookmark.snapshot.response.city,
                                            bookmark.snapshot.response.poiCount
                                        ),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (activeBookmarkId == bookmark.id) {
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.route_bookmark_current),
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                            Text(
                                text = bookmark.snapshot.response.route.take(3).joinToString(" • ") { item -> item.name },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                Button(
                                    onClick = { onOpenBookmark(bookmark.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(PlannerUiTokens.ButtonHeight)
                                ) {
                                    Text(stringResource(R.string.action_open_bookmark))
                                }
                                OutlinedButton(
                                    onClick = { onDeleteBookmark(bookmark.id) },
                                    modifier = Modifier
                                        .weight(1f)
                                        .height(PlannerUiTokens.ButtonHeight)
                                ) {
                                    Text(stringResource(R.string.action_delete_bookmark))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteHistorySheetContent(
    historyEntries: List<RouteHistoryEntry>,
    currentRouteId: String?,
    isLoading: Boolean,
    errorMessage: String?,
    onRefresh: () -> Unit,
    onOpenEntry: (RouteHistoryEntry) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxSize()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = stringResource(R.string.route_history_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = stringResource(R.string.route_history_body),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isLoading,
                        modifier = Modifier.height(PlannerUiTokens.ButtonHeight)
                    ) {
                        Text(stringResource(R.string.action_refresh_history))
                    }
                }
            }

            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error
                )
            }

            if (isLoading && historyEntries.isEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center
                ) {
                    CircularProgressIndicator()
                }
            } else if (historyEntries.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    historyEntries.forEach { entry ->
                        val response = entry.snapshot.response
                        val ratingLabel = entry.feedback?.rating?.let { rating ->
                            stringResource(R.string.route_history_rating_value, rating)
                        } ?: stringResource(R.string.route_history_rating_missing)
                        Surface(
                            shape = RoundedCornerShape(PlannerUiTokens.CardRadius),
                            tonalElevation = 2.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(PlannerUiTokens.CardPadding),
                                verticalArrangement = Arrangement.spacedBy(PlannerUiTokens.ItemSpacing)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.Top
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = entry.cityName,
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Text(
                                            text = entry.startedAt.toRouteDateTimeLabel(stringResource(R.string.common_unknown)),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Column(
                                        horizontalAlignment = Alignment.End,
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant
                                        ) {
                                            Text(
                                                text = routeSessionStatusLabel(RouteSessionStatus.fromRawValue(entry.status)),
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                style = MaterialTheme.typography.labelMedium
                                            )
                                        }
                                        if (currentRouteId == entry.routeId) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.route_history_current),
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                    style = MaterialTheme.typography.labelMedium,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.Medium
                                                )
                                            }
                                        }
                                    }
                                }

                                Text(
                                    text = stringResource(
                                        R.string.route_history_meta,
                                        response.poiCount,
                                        entry.usedMinutes ?: response.usedMinutes,
                                        ratingLabel
                                    ),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                Text(
                                    text = response.route.take(3).joinToString(" • ") { item -> item.name },
                                    style = MaterialTheme.typography.bodyMedium
                                )

                                Button(
                                    onClick = { onOpenEntry(entry) },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(PlannerUiTokens.ButtonHeight)
                                ) {
                                    Text(stringResource(R.string.action_view_history_details))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteHistoryDetailsDialog(
    entry: RouteHistoryEntry,
    onDismiss: () -> Unit
) {
    val routeResponse = entry.snapshot.response
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.92f)
                .padding(16.dp),
            shape = RoundedCornerShape(PlannerUiTokens.CardRadius),
            tonalElevation = 6.dp
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = entry.cityName,
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                text = entry.startedAt.toRouteDateTimeLabel(stringResource(R.string.common_unknown)),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = routeSessionStatusLabel(RouteSessionStatus.fromRawValue(entry.status)),
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    }
                }

                item {
                    RoutePreviewSummaryPanel(routeResponse = routeResponse)
                }

                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        SummaryMetric(
                            label = stringResource(R.string.route_history_metric_visited),
                            value = entry.visitedPoiIds.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                        SummaryMetric(
                            label = stringResource(R.string.route_history_metric_skipped),
                            value = entry.skippedPoiIds.size.toString(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }

                item {
                    RouteFeedbackSummaryCard(feedback = entry.feedback)
                }

                item {
                    Text(
                        text = stringResource(R.string.route_history_stops_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                items(routeResponse.route.size) { index ->
                    val item = routeResponse.route[index]
                    RouteStopTimelineItem(
                        item = item,
                        incomingLeg = routeResponse.legs.orEmpty().firstOrNull { leg -> leg.to.poiId == item.poiId },
                        isVisited = item.poiId in entry.visitedPoiIds,
                        isSkipped = item.poiId in entry.skippedPoiIds,
                        isNext = false,
                        isLast = index == routeResponse.route.lastIndex,
                        isRouteActive = false,
                        canSkip = false,
                        canReplace = false,
                        canMoveUp = false,
                        canMoveDown = false,
                        isActionInProgress = false,
                        onMarkVisited = {},
                        onSkip = {},
                        onMoveUp = {},
                        onMoveDown = {},
                        onReplace = {}
                    )
                }

                item {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(PlannerUiTokens.ButtonHeight)
                    ) {
                        Text(stringResource(R.string.action_close_history_details))
                    }
                }
            }
        }
    }
}

@Composable
internal fun RouteFeedbackSummaryCard(feedback: RouteFeedback?) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(PlannerUiTokens.CardRadius),
        tonalElevation = 3.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(PlannerUiTokens.CardPadding),
            verticalArrangement = Arrangement.spacedBy(PlannerUiTokens.ItemSpacing)
        ) {
            Text(
                text = stringResource(R.string.route_history_feedback_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            if (feedback == null || feedback.rating <= 0) {
                Text(
                    text = stringResource(R.string.route_history_feedback_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Text(
                    text = stringResource(R.string.route_history_rating_value, feedback.rating),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                FeedbackSummaryRow(
                    title = stringResource(R.string.route_feedback_comfortable),
                    value = feedback.route_was_comfortable
                )
                FeedbackSummaryRow(
                    title = stringResource(R.string.route_feedback_too_much_walking),
                    value = feedback.too_much_walking
                )
                FeedbackSummaryRow(
                    title = stringResource(R.string.route_feedback_interesting_pois),
                    value = feedback.pois_were_interesting
                )
            }
        }
    }
}

@Composable
private fun FeedbackSummaryRow(
    title: String,
    value: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )
        Text(
            text = stringResource(if (value) R.string.state_yes else R.string.state_no),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
internal fun ReplaceRouteStopSheetContent(
    targetStopName: String,
    candidates: List<Poi>,
    isActionInProgress: Boolean,
    onUseBestSuggestion: () -> Unit,
    onChooseCandidate: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.route_replace_stop_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_replace_stop_body, targetStopName),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Button(
            onClick = onUseBestSuggestion,
            enabled = !isActionInProgress,
            modifier = Modifier
                .fillMaxWidth()
                .height(PlannerUiTokens.ButtonHeight)
        ) {
            Text(stringResource(R.string.action_replace_with_best))
        }

        if (candidates.isEmpty()) {
            Text(
                text = stringResource(R.string.route_replace_candidates_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(
                    items = candidates,
                    key = { poi -> poi.id }
                ) { poi ->
                    Surface(
                        shape = RoundedCornerShape(PlannerUiTokens.CardRadius),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
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
                            }
                            OutlinedButton(
                                onClick = { onChooseCandidate(poi.id) },
                                enabled = !isActionInProgress,
                                modifier = Modifier.height(PlannerUiTokens.ButtonHeight)
                            ) {
                                Text(stringResource(R.string.action_choose_stop))
                            }
                        }
                    }
                }
            }
        }
    }
}
