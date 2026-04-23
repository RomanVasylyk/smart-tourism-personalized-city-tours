package com.example.smarttourism.data

data class RouteFeedback(
    val rating: Int,
    val route_was_comfortable: Boolean,
    val too_much_walking: Boolean,
    val pois_were_interesting: Boolean
)
