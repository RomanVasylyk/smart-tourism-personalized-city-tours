package com.example.smarttourism.features.planner.domain.route

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

internal fun defaultRouteStartDateTime(): LocalDateTime =
    LocalDateTime.now().truncatedTo(ChronoUnit.MINUTES)

internal fun parseRouteStartDateTime(rawValue: String?): LocalDateTime =
    rawValue?.toDisplayDateTime()?.truncatedTo(ChronoUnit.MINUTES) ?: defaultRouteStartDateTime()

private fun String.toDisplayDateTime(): LocalDateTime? =
    runCatching {
        OffsetDateTime.parse(this)
            .atZoneSameInstant(ZoneId.systemDefault())
            .toLocalDateTime()
    }.recoverCatching {
        LocalDateTime.parse(this)
    }.getOrNull()
