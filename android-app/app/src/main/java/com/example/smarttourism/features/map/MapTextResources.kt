package com.example.smarttourism.features.map

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.smarttourism.R

@Composable
internal fun mapTextResources(): MapTextResources =
    MapTextResources(
        startPointTitle = stringResource(R.string.start_point_title),
        startPointMarkerLabel = stringResource(R.string.map_start_marker_label),
        currentLocationTitle = stringResource(R.string.map_current_location_title),
        routeStopTitleFormat = stringResource(R.string.route_stop_title),
        visitedRouteStopTitleFormat = stringResource(R.string.map_visited_route_stop_title),
        routeStopSnippetFormat = stringResource(R.string.map_route_stop_snippet),
        selectedPoiSnippetFormat = stringResource(R.string.map_poi_selected_snippet),
        categoryLabels = mapOf(
            "attraction" to stringResource(R.string.category_attraction),
            "museum" to stringResource(R.string.category_museum),
            "gallery" to stringResource(R.string.category_gallery),
            "viewpoint" to stringResource(R.string.category_viewpoint),
            "monument" to stringResource(R.string.category_monument),
            "historical_site" to stringResource(R.string.category_historical_site),
            "park" to stringResource(R.string.category_park),
            "religious_site" to stringResource(R.string.category_religious_site)
        )
    )
