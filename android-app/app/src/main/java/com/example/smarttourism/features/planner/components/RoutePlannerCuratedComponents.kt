package com.example.smarttourism.features.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.CuratedRoute

@Composable
internal fun CuratedRoutesSheetContent(
    cityName: String?,
    curatedRoutes: List<CuratedRoute>,
    isLoading: Boolean,
    errorMessage: String?,
    onOpenCuratedRoute: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.curated_routes_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = cityName?.let { name ->
                    stringResource(R.string.curated_routes_body_for_city, name)
                } ?: stringResource(R.string.curated_routes_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (isLoading && curatedRoutes.isEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (curatedRoutes.isEmpty()) {
            Text(
                text = stringResource(R.string.curated_routes_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                curatedRoutes.forEach { route ->
                    CuratedRouteCard(
                        route = route,
                        isLoading = isLoading,
                        onOpenCuratedRoute = onOpenCuratedRoute
                    )
                }
            }
        }
    }
}

@Composable
private fun CuratedRouteCard(
    route: CuratedRoute,
    isLoading: Boolean,
    onOpenCuratedRoute: (Int) -> Unit
) {
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
            Text(
                text = route.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )

            val stopsText = stringResource(R.string.curated_route_stops_count, route.poiCount)
            val durationText = route.recommendedDurationMin?.let { minutes ->
                stringResource(R.string.curated_route_duration_minutes, minutes)
            }
            val meta = if (durationText != null) "$stopsText • $durationText" else stopsText
            Text(
                text = meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            route.summary?.takeIf { it.isNotBlank() }?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (route.stopNames.isNotEmpty()) {
                Text(
                    text = route.stopNames.take(3).joinToString(" • "),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Button(
                onClick = { onOpenCuratedRoute(route.id) },
                enabled = !isLoading,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = PlannerUiTokens.ButtonHeight)
            ) {
                Text(stringResource(R.string.action_open_curated_route))
            }
        }
    }
}
