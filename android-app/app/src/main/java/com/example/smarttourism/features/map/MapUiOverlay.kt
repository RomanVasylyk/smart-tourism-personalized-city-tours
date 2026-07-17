package com.example.smarttourism.features.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel

@Composable
internal fun MapSelectionHint(
    text: String,
    modifier: Modifier = Modifier
) {
    Surface(modifier = modifier.padding(12.dp)) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
internal fun MapLocationButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val description = stringResource(R.string.map_current_location_button)
    val containerColor = if (enabled) {
        MaterialTheme.colorScheme.surface
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = if (enabled) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Box(
        modifier = modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics {
                contentDescription = description
                role = Role.Button
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(22.dp)) {
            val strokeWidth = size.minDimension * 0.1f
            val outerRadius = size.minDimension * 0.38f
            val innerRadius = size.minDimension * 0.08f
            val centerX = size.width / 2f
            val centerY = size.height / 2f
            val crosshairGap = size.minDimension * 0.2f

            drawCircle(
                color = contentColor,
                radius = outerRadius,
                style = Stroke(width = strokeWidth)
            )
            drawCircle(
                color = contentColor,
                radius = innerRadius
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(centerX, 0f),
                end = androidx.compose.ui.geometry.Offset(centerX, centerY - crosshairGap),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(centerX, centerY + crosshairGap),
                end = androidx.compose.ui.geometry.Offset(centerX, size.height),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(0f, centerY),
                end = androidx.compose.ui.geometry.Offset(centerX - crosshairGap, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
            drawLine(
                color = contentColor,
                start = androidx.compose.ui.geometry.Offset(centerX + crosshairGap, centerY),
                end = androidx.compose.ui.geometry.Offset(size.width, centerY),
                strokeWidth = strokeWidth,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
internal fun MapRouteLegend(
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        tonalElevation = 4.dp,
        shadowElevation = 4.dp
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = stringResource(R.string.map_route_legend_title),
                style = MaterialTheme.typography.labelLarge
            )
            MapLegendItem(
                color = Color(android.graphics.Color.parseColor(RoutePrimaryLineColor)),
                label = stringResource(R.string.map_route_legend_active)
            )
            MapLegendItem(
                color = Color(android.graphics.Color.parseColor(RouteSecondaryLineColor)),
                label = stringResource(R.string.map_route_legend_upcoming)
            )
            MapLegendItem(
                color = Color(android.graphics.Color.parseColor(TransitLineColors.first())),
                label = stringResource(R.string.map_route_legend_transit)
            )
        }
    }
}

@Composable
internal fun MapPoiInfoPanel(
    poi: Poi,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    onDismiss: () -> Unit,
    isFullScreen: Boolean = true,
    modifier: Modifier = Modifier
) {
    if (!isFullScreen) {
        CompactMapPoiInfoPanel(
            poi = poi,
            primaryActionLabel = primaryActionLabel,
            onPrimaryAction = onPrimaryAction,
            onDismiss = onDismiss,
            modifier = modifier
        )
        return
    }

    val description = poi.shortDescription
        ?.takeIf { value -> value.isNotBlank() }
        ?: stringResource(R.string.map_poi_description_missing)
    val contentScrollState = rememberScrollState()

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 156.dp, max = 430.dp),
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 5.dp,
        shadowElevation = 8.dp
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Column(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .verticalScroll(contentScrollState),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = poi.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    MapInfoChip(label = categoryLabel(poi.category))
                    poi.visitDurationMin?.let { minutes ->
                        MapInfoChip(label = stringResource(R.string.route_stop_visit_badge, minutes))
                    }
                }
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                poi.openingHoursRaw?.takeIf { value -> value.isNotBlank() }?.let { hours ->
                    val hoursLabel = if (poi.openingHoursSource == "service_times") {
                        stringResource(R.string.poi_service_times_label)
                    } else {
                        stringResource(R.string.poi_opening_hours_label)
                    }
                    Text(
                        text = "$hoursLabel: $hours",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                poi.address?.takeIf { value -> value.isNotBlank() }?.let { address ->
                    Text(
                        text = address,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                poi.website?.takeIf { value -> value.isNotBlank() }?.let { website ->
                    val context = LocalContext.current
                    TextButton(
                        onClick = { openPoiUrl(context, website) },
                        modifier = Modifier.align(Alignment.Start)
                    ) {
                        Text(stringResource(R.string.action_open_website))
                    }
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 44.dp)
                ) {
                    Text(stringResource(R.string.action_done))
                }
                if (primaryActionLabel != null && onPrimaryAction != null) {
                    Button(
                        onClick = onPrimaryAction,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 44.dp)
                    ) {
                        Text(primaryActionLabel)
                    }
                } else {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

private fun openPoiUrl(context: Context, url: String) {
    val normalized = if (url.startsWith("http://") || url.startsWith("https://")) {
        url
    } else {
        "https://$url"
    }
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(normalized)))
    }
}

@Composable
private fun CompactMapPoiInfoPanel(
    poi: Poi,
    primaryActionLabel: String?,
    onPrimaryAction: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
        tonalElevation = 5.dp,
        shadowElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = poi.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                        MapInfoChip(label = categoryLabel(poi.category))
                        poi.visitDurationMin?.let { minutes ->
                            MapInfoChip(label = stringResource(R.string.route_stop_visit_badge, minutes))
                        }
                    }
                }
                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 36.dp)
                ) {
                    Text("✕")
                }
            }
            if (primaryActionLabel != null && onPrimaryAction != null) {
                Button(
                    onClick = {
                        onPrimaryAction()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 36.dp)
                ) {
                    Text(primaryActionLabel, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

private const val CollapsedDescriptionLengthThreshold = 140

@Composable
private fun MapInfoChip(label: String) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f)
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MapLegendItem(
    color: Color,
    label: String
) {
    Row(
        modifier = Modifier.defaultMinSize(minHeight = 28.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        Box(
            modifier = Modifier
                .width(28.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
