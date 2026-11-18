package com.remodex.mobile.core.transport

import java.util.concurrent.TimeoutException
import kotlinx.coroutines.withTimeoutOrNull

internal class ConnectionStageTimeout(val stage: String, val timeoutMillis: Long) :
    TimeoutException("Connection timed out while waiting for $stage (${timeoutMillis / 1000}s). Tap Reconnect to retry.")

/** Only the deadline owned by this stage is a connection failure; caller cancellation still propagates. */
internal suspend fun <T> awaitConnectionStage(
    stage: String,
    timeoutMillis: Long,
    block: suspend () -> T,
): T = (withTimeoutOrNull(timeoutMillis) { Result.success(block()) }
    ?: throw ConnectionStageTimeout(stage, timeoutMillis)).getOrThrow()
