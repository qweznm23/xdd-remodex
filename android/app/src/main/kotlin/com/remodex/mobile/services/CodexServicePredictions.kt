package com.remodex.mobile.services

import android.util.Log
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

internal fun CodexService.requestComposerPrediction(request: ComposerPredictions.Request) {
    val generation = connectionGeneration
    scope.launch {
        var stage = "check-draft"
        try {
            if (!predictionStore.pending(request) || !sessionReady || generation != connectionGeneration) return@launch
            if (loadComposerDraft(request.thread).isNotEmpty()) {
                if (predictionStore.pending(request)) predictionStore.finish(request, null)
                return@launch
            }
            if (!predictionStore.pending(request) || generation != connectionGeneration) return@launch
            stage = "composer/prediction/read"
            val params = JSONValue.Obj(mapOf(
                "threadId" to JSONValue.Str(request.thread),
                "sourceTurnId" to JSONValue.Str(request.turn),
                "trigger" to JSONValue.Str("catch_up"),
            ))
            val reply = withTimeoutOrNull(125_000) { sendRequestImpl(stage, params) }
                ?: throw TimeoutException("Composer prediction lookup exceeded 125s")
            val result = reply.result?.objectValue
            val suggestion = result?.get("text")
            if (result?.get("sourceTurnId")?.stringValue != request.turn ||
                (suggestion !is JSONValue.Str && suggestion != JSONValue.Null)
            ) throw CodexServiceError.InvalidResponse("Malformed composer prediction response")
            if (generation == connectionGeneration) predictionStore.finish(request, suggestion?.stringValue)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Log.w("RemodexPrediction", "stage=$stage thread=${request.thread} turn=${request.turn} code=${(error as? CodexServiceError.RpcFailure)?.rpcError?.code}", error)
            if (generation == connectionGeneration) predictionStore.fail(request)
        }
    }
}
