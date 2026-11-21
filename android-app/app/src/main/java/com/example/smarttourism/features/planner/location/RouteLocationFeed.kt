package com.example.smarttourism.features.planner.location

import com.example.smarttourism.features.planner.domain.model.RoutePoint
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel

internal sealed interface RouteTrackingUpdate {
    data class Location(val point: RoutePoint) : RouteTrackingUpdate

    data class Error(val message: String) : RouteTrackingUpdate
}

internal object RouteLocationFeed {
    private const val BufferCapacity = 512

    private val channel = Channel<RouteTrackingUpdate>(
        capacity = BufferCapacity,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val updates: ReceiveChannel<RouteTrackingUpdate>
        get() = channel

    fun publish(update: RouteTrackingUpdate) {
        channel.trySend(update)
    }

    fun clear() {
        while (channel.tryReceive().isSuccess) {
            continue
        }
    }
}
