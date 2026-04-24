package com.example.smarttourism.data

data class RouteFeedbackDto(
    val id: Int,
    val session_id: String,
    val rating: Int,
    val was_convenient: Boolean,
    val too_much_walking: Boolean,
    val pois_were_interesting: Boolean,
    val comment: String?,
    val created_at: String
)
