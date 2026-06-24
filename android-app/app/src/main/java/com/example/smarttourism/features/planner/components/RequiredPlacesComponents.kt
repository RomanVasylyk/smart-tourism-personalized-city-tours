package com.example.smarttourism.features.planner.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.smarttourism.R
import com.example.smarttourism.features.planner.domain.model.Poi
import com.example.smarttourism.features.planner.ui.formatters.categoryLabel

@Composable
internal fun RequiredPlacesCard(
    selectedPois: List<Poi>,
    availablePoiCount: Int,
    isEditingEnabled: Boolean,
    onChooseOnMap: () -> Unit,
    onChooseFromList: () -> Unit,
    onMovePoi: (Int, Int) -> Unit,
    onRemovePoi: (Int) -> Unit,
    onClearAll: () -> Unit
) {
    ElevatedCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.route_required_places_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_required_places_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (selectedPois.isEmpty()) {
                Text(
                    text = stringResource(R.string.route_required_places_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    selectedPois.forEachIndexed { index, poi ->
                        Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = MaterialTheme.colorScheme.primary
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelLarge,
                                        color = MaterialTheme.colorScheme.onPrimary,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = poi.name,
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = categoryLabel(poi.category),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    TextButton(
                                        onClick = { onMovePoi(poi.id, -1) },
                                        enabled = isEditingEnabled && index > 0
                                    ) {
                                        Text(stringResource(R.string.action_move_up))
                                    }
                                    TextButton(
                                        onClick = { onMovePoi(poi.id, 1) },
                                        enabled = isEditingEnabled && index < selectedPois.lastIndex
                                    ) {
                                        Text(stringResource(R.string.action_move_down))
                                    }
                                    TextButton(
                                        onClick = { onRemovePoi(poi.id) },
                                        enabled = isEditingEnabled
                                    ) {
                                        Text(stringResource(R.string.action_remove_stop))
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onChooseOnMap,
                    enabled = isEditingEnabled && availablePoiCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_choose_required_places_on_map))
                }
                Button(
                    onClick = onChooseFromList,
                    enabled = isEditingEnabled && availablePoiCount > 0,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(stringResource(R.string.action_choose_required_places_from_list))
                }
            }

            if (selectedPois.isNotEmpty()) {
                TextButton(
                    onClick = onClearAll,
                    enabled = isEditingEnabled,
                    modifier = Modifier.align(Alignment.End)
                ) {
                    Text(stringResource(R.string.action_clear_required_places))
                }
            }
        }
    }
}

@Composable
internal fun RequiredPlacesPickerSheetContent(
    pois: List<Poi>,
    selectedPoiIds: List<Int>,
    isEditingEnabled: Boolean,
    onTogglePoi: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(R.string.route_required_places_picker_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.route_required_places_picker_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        if (pois.isEmpty()) {
            Text(
                text = stringResource(R.string.route_required_places_picker_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(
                    items = pois.sortedWith(compareBy<Poi> { poi -> poi.category }.thenBy { poi -> poi.name }),
                    key = { poi -> poi.id }
                ) { poi ->
                    val selected = poi.id in selectedPoiIds
                    SelectableRow(
                        enabled = isEditingEnabled,
                        onClick = { onTogglePoi(poi.id) }
                    ) {
                        Checkbox(
                            checked = selected,
                            onCheckedChange = null,
                            enabled = isEditingEnabled
                        )
                        Spacer(modifier = Modifier.width(12.dp))
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
                    }
                }
            }
        }
    }
}
