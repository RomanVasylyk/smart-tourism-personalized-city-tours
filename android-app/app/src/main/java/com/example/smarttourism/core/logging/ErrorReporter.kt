package com.example.smarttourism.core.logging

import android.util.Log

internal interface ErrorReporter {
    fun report(
        tag: String,
        message: String,
        throwable: Throwable? = null,
        metadata: Map<String, String> = emptyMap()
    )
}

internal object AndroidLogErrorReporter : ErrorReporter {
    override fun report(
        tag: String,
        message: String,
        throwable: Throwable?,
        metadata: Map<String, String>
    ) {
        val fullMessage = if (metadata.isEmpty()) {
            message
        } else {
            "$message | ${metadata.entries.joinToString { "${it.key}=${it.value}" }}"
        }

        if (throwable == null) {
            Log.w(tag, fullMessage)
        } else {
            Log.w(tag, fullMessage, throwable)
        }
    }
}
