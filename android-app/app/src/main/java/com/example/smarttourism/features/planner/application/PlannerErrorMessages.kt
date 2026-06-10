package com.example.smarttourism.features.planner.application

internal fun Throwable.toUserMessage(defaultMessage: String): String {
    val rawMessage = message?.substringBefore('\n')?.trim()
    return if (rawMessage.isNullOrEmpty()) defaultMessage else rawMessage
}
