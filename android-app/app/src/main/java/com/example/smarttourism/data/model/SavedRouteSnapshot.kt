package com.example.smarttourism.data.model

import com.example.smarttourism.features.planner.domain.model.PlannerPreferences
import com.example.smarttourism.features.planner.domain.model.RoutePlan

data class SavedRouteSnapshot(
    val request: PlannerPreferences,
    val response: RoutePlan
)
