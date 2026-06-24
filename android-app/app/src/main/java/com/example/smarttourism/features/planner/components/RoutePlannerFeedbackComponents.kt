package com.example.smarttourism.features.planner.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.data.model.RouteFeedback

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
