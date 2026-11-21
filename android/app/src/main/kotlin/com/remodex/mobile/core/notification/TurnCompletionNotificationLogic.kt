package com.remodex.mobile.core.notification

import com.remodex.mobile.core.model.JSONValue

/** Terminal turn state for notification policy (subset of iOS [CodexTurnTerminalState]). */
enum class TurnTerminalStateForNotification {
    Completed,
    Failed,
    Stopped,
    Unconfirmed,
}

/** Vocabulary for local run-completion alerts (iOS [CodexRunCompletionResult]). */
enum class RunCompletionAttentionKind {
    Completed,
    Failed,
}

/**
 * Pure parsing for when to show a run-completion notification after `turn/completed`.
 * Mirrors [CodexService.parseTurnTerminalState] / [CodexService.parseTurnFailureMessage] (iOS).
 */
object TurnCompletionNotificationLogic {
    fun normalizeThreadStatusType(raw: String): String =
        raw
            .trim()
            .lowercase()
            .replace("_", "")
            .replace("-", "")
            .replace(" ", "")

    fun envelopeEventObject(params: Map<String, JSONValue>?): Map<String, JSONValue>? =
        params?.get("msg")?.objectValue ?: params?.get("event")?.objectValue

    fun parseTurnFailureMessage(params: Map<String, JSONValue>?): String? {
        val p = params ?: return null
        val turnObject = p["turn"]?.objectValue
        val status =
            turnObject?.get("status")?.stringValue?.trim()
                ?: p["status"]?.stringValue?.trim()
        if (status != "failed") return null
        val errObj = turnObject?.get("error")?.objectValue ?: p["error"]?.objectValue
        return errObj?.get("message")?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
            ?: p["errorMessage"]?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
            ?: "Turn failed with no details"
    }

    fun parseTurnTerminalState(
        params: Map<String, JSONValue>?,
        turnFailureMessage: String?,
    ): TurnTerminalStateForNotification {
        if (turnFailureMessage != null) return TurnTerminalStateForNotification.Failed
        val p = params ?: return TurnTerminalStateForNotification.Completed
        val eventObject = envelopeEventObject(p)
        val status = sequenceOf(p["turn"]?.objectValue, p, eventObject?.get("turn")?.objectValue, eventObject)
            .mapNotNull { it?.get("status") }.firstOrNull() ?: return TurnTerminalStateForNotification.Completed
        val raw = status.stringValue ?: status.objectValue?.let { firstStringIn(it, listOf("type", "statusType", "status_type")) }.orEmpty()
        return when (normalizeThreadStatusType(raw)) {
            "cancelled", "canceled", "abort", "aborted", "interrupt", "interrupted", "stopped" -> TurnTerminalStateForNotification.Stopped
            "failed", "error" -> TurnTerminalStateForNotification.Failed
            "completed", "complete", "done", "finished", "succeeded", "success" -> TurnTerminalStateForNotification.Completed
            else -> TurnTerminalStateForNotification.Unconfirmed
        }
    }

    fun attentionKindFromTerminalState(
        state: TurnTerminalStateForNotification,
    ): RunCompletionAttentionKind? =
        when (state) {
            TurnTerminalStateForNotification.Completed -> RunCompletionAttentionKind.Completed
            TurnTerminalStateForNotification.Failed -> RunCompletionAttentionKind.Failed
            TurnTerminalStateForNotification.Stopped, TurnTerminalStateForNotification.Unconfirmed -> null
        }

    private fun firstStringIn(
        obj: Map<String, JSONValue>?,
        keys: List<String>,
    ): String? {
        if (obj == null) return null
        for (k in keys) {
            obj[k]?.stringValue?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return null
    }

    fun freshLiveEvent(params: Map<String, JSONValue>, now: Long = System.currentTimeMillis()): Boolean {
        val event = envelopeEventObject(params)
        val sources = listOfNotNull(params, params["turn"]?.objectValue, event, event?.get("turn")?.objectValue)
        if (sources.any { row -> listOf("remodexReplayedEvent", "remodexRolloutBootstrapReplay", "remodexRolloutTerminalCatchUp")
                .any { row[it]?.boolValue == true } }) return false
        for (source in sources) for (key in listOf("completedAtMs", "completed_at_ms", "completedAt", "completed_at")) {
            val value = source[key] ?: continue
            if (value == JSONValue.Null) continue
            val numeric = value.doubleValue ?: value.stringValue?.toDoubleOrNull()
            val stamp = if (numeric != null) {
                if (key.endsWith("ms", ignoreCase = true) || numeric >= 1e12) numeric.toLong() else (numeric * 1000).toLong()
            } else try {
                java.time.Instant.parse(value.stringValue ?: return false).toEpochMilli()
            } catch (_: java.time.DateTimeException) { return false }
            return stamp in (now - 300_000)..(now + 300_000)
        }
        return true
    }
}
