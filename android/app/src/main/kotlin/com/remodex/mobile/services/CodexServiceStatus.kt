package com.remodex.mobile.services

import com.remodex.mobile.R
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import com.remodex.mobile.core.model.UsageStatusRefreshPolicy
import com.remodex.mobile.core.transport.ConnectionState
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Account rate limits (`account/rateLimits/read`, `account/rateLimits/updated`).
 * Parity with [CodexService+Status.swift](../../../../../../../../CodexMobile/CodexMobile/Services/CodexService+Status.swift).
 */
internal fun CodexService.applyRateLimitsPayload(
    payloadObject: Map<String, JSONValue>,
    mergeWithExisting: Boolean,
) {
    val decoded = RateLimitPayloadCodec.decodeRateLimitBuckets(payloadObject)
    val resolved =
        if (mergeWithExisting) {
            RateLimitPayloadCodec.mergeRateLimitBuckets(
                _rateLimitBuckets.value,
                decoded,
            )
        } else {
            decoded
        }
    _rateLimitBuckets.value =
        resolved.sortedWith { lhs, rhs ->
            if (lhs.sortDurationMins == rhs.sortDurationMins) {
                lhs.displayLabel.compareTo(rhs.displayLabel, ignoreCase = true)
            } else {
                lhs.sortDurationMins.compareTo(rhs.sortDurationMins)
            }
        }
}

internal fun CodexService.handleRateLimitsUpdatedParams(params: Map<String, JSONValue>?) {
    if (params == null) return
    synchronized(rateLimitRefreshGuard) {
        try {
            applyRateLimitsPayload(params, mergeWithExisting = true)
            rateLimitRefreshGuard.pushed()
            _hasResolvedRateLimitsSnapshot.value = true
            // Rolling quota updates do not prove that the last full read (including cards) succeeded.
        } catch (failure: Exception) {
            android.util.Log.e("RemodexUsage", "operation=account/rateLimits/updated stage=decode attempt=1", failure)
        }
    }
}

internal suspend fun CodexService.refreshRateLimitsForRepository() =
    withContext(Dispatchers.IO) {
        refreshRateLimitsInternal()
    }

internal suspend fun CodexService.refreshUsageStatusForRepository(threadId: String?) =
    withContext(Dispatchers.IO) {
        refreshRateLimitsInternal()
        val tid = threadId?.trim().orEmpty()
        if (tid.isNotEmpty()) {
            refreshContextWindowUsageInternal(tid)
        }
    }

internal fun CodexService.shouldAutoRefreshUsageStatusForRepository(threadId: String?): Boolean =
    UsageStatusRefreshPolicy.shouldAutoRefresh(
        sessionReady = sessionReady,
        connected = _connectionState.value is ConnectionState.Connected,
        threadId = threadId,
        contextWindowUsageByThread = _contextWindowUsageByThread.value,
        hasResolvedRateLimitsSnapshot = _hasResolvedRateLimitsSnapshot.value,
    )

internal suspend fun CodexService.refreshRateLimitsInternal() {
    val ticket = synchronized(rateLimitRefreshGuard) {
        if (!sessionReady) return
        val request = rateLimitRefreshGuard.begin(connectionGeneration) ?: return
        _isLoadingRateLimits.value = true
        request
    }
    var stage = "request"
    var attempts = 0
    try {
        val response = fetchRateLimitsWithCompatRetry { params ->
            attempts++
            sendRequestImpl("account/rateLimits/read", params)
        }
        kotlinx.coroutines.currentCoroutineContext().ensureActive()
        stage = "decode"
        val resultObject = response.result?.objectValue
            ?: throw CodexServiceError.InvalidResponse("account/rateLimits/read response missing payload")
        // Decode all parts before publishing any part; malformed cards must not create a false success time.
        val details = AccountUsageDetailsCodec.decode(resultObject)
        val buckets = RateLimitPayloadCodec.decodeRateLimitBuckets(resultObject)
        synchronized(rateLimitRefreshGuard) {
            if (!sessionReady || !rateLimitRefreshGuard.isCurrent(ticket, connectionGeneration)) return
            if (!rateLimitRefreshGuard.hasNewerPush(ticket)) _rateLimitBuckets.value = buckets.sortedBy { it.sortDurationMins }
            _accountUsageDetails.value = details.copy(lastSuccessfulReadAt = java.time.Instant.now())
            _hasResolvedRateLimitsSnapshot.value = true
            _rateLimitsErrorMessage.value = null
        }
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        val code = (failure as? CodexServiceError.RpcFailure)?.rpcError?.code
        android.util.Log.e("RemodexUsage", "operation=account/rateLimits/read stage=$stage attempts=$attempts rpcCode=$code", failure)
        synchronized(rateLimitRefreshGuard) {
            if (sessionReady && rateLimitRefreshGuard.isCurrent(ticket, connectionGeneration)) {
                // Keep the previous successful data/time, explicitly accompanied by the refresh failure.
                _rateLimitsErrorMessage.value = failure.message?.takeIf { it.isNotBlank() }
                    ?: appContext.getString(R.string.usage_rate_limits_load_failed)
            }
        }
    } finally {
        synchronized(rateLimitRefreshGuard) {
            if (rateLimitRefreshGuard.isCurrent(ticket, connectionGeneration)) _isLoadingRateLimits.value = false
            rateLimitRefreshGuard.finish(ticket)
        }
    }
}

internal suspend fun fetchRateLimitsWithCompatRetry(request: suspend (JSONValue) -> RPCMessage): RPCMessage {
    return try {
        request(JSONValue.Obj(mapOf("excludeResetCreditDetails" to JSONValue.Bool(false))))
    } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
    catch (first: Exception) {
        if (!shouldRetryRateLimitsWithEmptyParams(first)) throw first
        try { request(JSONValue.Obj(emptyMap())) }
        catch (failure: Exception) {
            if (failure !== first) failure.addSuppressed(first)
            throw failure
        }
    }
}

private fun shouldRetryRateLimitsWithEmptyParams(e: Throwable): Boolean {
    val rpc = (e as? CodexServiceError.RpcFailure)?.rpcError ?: return false
    if (rpc.code != -32602 && rpc.code != -32600) return false
    val lowered = rpc.message.lowercase()
    return lowered.contains("invalid params") ||
        lowered.contains("invalid param") ||
        lowered.contains("failed to parse") ||
        lowered.contains("expected") ||
        lowered.contains("missing field `params`") ||
        lowered.contains("missing field params")
}
