package com.example.smarttourism.features.map

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

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
            .size(48.dp)
            .clip(CircleShape)
            .background(containerColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(enabled = enabled, onClick = onClick),
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
