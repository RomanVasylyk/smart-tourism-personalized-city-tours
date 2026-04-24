package com.example.smarttourism.data

data class RouteFeedbackRequest(
    val rating: Int,
    val was_convenient: Boolean,
    val too_much_walking: Boolean,
    val pois_were_interesting: Boolean,
    val comment: String? = null
)
