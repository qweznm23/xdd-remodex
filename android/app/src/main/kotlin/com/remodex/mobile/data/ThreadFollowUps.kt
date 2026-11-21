package com.remodex.mobile.data

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.notification.TurnCompletionNotificationLogic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ThreadFollowUp(val id: String, val turn: String, val status: String, val detail: String,
    val review: Boolean, val canAct: Boolean, val actionText: String = "")

internal class ThreadFollowUps(private val now: () -> Long = System::currentTimeMillis) {
    private val rows = MutableStateFlow<Map<String, List<ThreadFollowUp>>>(emptyMap())
    val state = rows.asStateFlow()
    private data class Grant(val event: JSONValue, val expires: Long)
    private val grants = linkedMapOf<Pair<String, String>, Grant>()

    @Synchronized fun clear() { grants.clear(); rows.value = emptyMap() }

    @Synchronized fun observe(method: String, p: Map<String, JSONValue>, followedTurn: String?) {
        val thread = p["threadId"]?.stringValue ?: return
        val turn = p["turnId"]?.stringValue ?: p["turn"]?.objectValue?.get("id")?.stringValue ?: return
        if (!TurnCompletionNotificationLogic.freshLiveEvent(p, now())) return
        val current = rows.value[thread].orEmpty()
        if (method == "turn/started") {
            grants.keys.removeAll { it.first == thread }
            rows.value = rows.value - thread
            return
        }
        if (turn != followedTurn) return
        val row = when (method) {
            "turn/completed" -> {
                val payload = p["turn"]?.objectValue ?: return
                if (payload["status"]?.stringValue != "failed") return
                val error = payload["error"]?.objectValue ?: return
                if (!recoverableStreamError(error["codexErrorInfo"])) return
                if (current.any { !it.review && it.turn == turn }) return
                ThreadFollowUp(turn, turn, "failed", error["message"]?.stringValue.orEmpty(), false, true)
            }
            "item/autoApprovalReview/started", "item/autoApprovalReview/completed" -> {
                val id = p["reviewId"]?.stringValue ?: return
                val review = p["review"]?.objectValue ?: return
                val status = review["status"]?.stringValue ?: return
                if (current.any { it.id == id && it.status != "inProgress" }) return
                val action = p["action"]?.objectValue ?: return
                val event = if (status == "denied" && p["remodexGuardianRetrySupported"]?.boolValue != false &&
                    p["remodexDesktopIpcMirror"]?.boolValue != true) deniedEvent(p, review, action) else null
                if (event != null) {
                    grants.entries.removeAll { it.value.expires <= now() }
                    while (grants.size >= 10) grants.remove(grants.keys.first())
                    grants[thread to id] = Grant(event, now() + 900_000)
                }
                ThreadFollowUp(id, turn, status, review["rationale"]?.stringValue.orEmpty(), true,
                    event != null, JSONValue.toJsonElement(JSONValue.Obj(action)).toString())
            }
            else -> return
        }
        val next = (current.filter { it.id != row.id } + row).takeLast(10)
        rows.value = (rows.value - thread + (thread to next)).entries.toList().takeLast(80).associate { it.toPair() }
    }

    @Synchronized fun consume(thread: String, row: ThreadFollowUp): JSONValue? {
        check(row.canAct && row in rows.value[thread].orEmpty()) { "This action is no longer available." }
        rows.value = rows.value + (thread to rows.value[thread].orEmpty().map { if (it == row) it.copy(canAct = false) else it })
        if (!row.review) return null
        val grant = grants.remove(thread to row.id)
        check(grant != null && grant.expires > now()) { "The live review expired." }
        return grant.event
    }
}

internal fun recoverableStreamError(info: JSONValue?): Boolean {
    if (info?.stringValue in setOf("serverOverloaded", "internalServerError")) return true
    val fields = info?.objectValue ?: return false
    val entry = fields.entries.singleOrNull() ?: return false
    if (entry.key !in setOf("responseStreamDisconnected", "responseStreamConnectionFailed", "responseTooManyFailedAttempts", "httpConnectionFailed")) return false
    val detail = entry.value.objectValue ?: return false
    val status = detail["httpStatusCode"]
    return status == null || status == JSONValue.Null || status.intValue == 408 || status.intValue in 500..599
}

private fun deniedEvent(p: Map<String, JSONValue>, review: Map<String, JSONValue>, action: Map<String, JSONValue>): JSONValue? {
    val type = action["type"]?.stringValue ?: return null
    // Forward only known, losslessly representable actions. Desktop handles other runtime dialects.
    val required = when (type) {
        "command" -> listOf("source", "command", "cwd")
        "execve" -> listOf("source", "program", "argv", "cwd")
        "applyPatch" -> listOf("cwd", "files")
        "networkAccess" -> listOf("target", "host", "protocol", "port")
        "mcpToolCall" -> listOf("server", "toolName")
        "requestPermissions" -> listOf("permissions")
        else -> return null
    }
    val optional = when (type) {
        "mcpToolCall" -> listOf("connectorId", "connectorName", "toolTitle")
        "requestPermissions" -> listOf("reason")
        else -> emptyList()
    }
    if (action.keys.any { it != "type" && it !in required && it !in optional }) return null
    fun snake(s: String) = s.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
    val core = mutableMapOf<String, JSONValue>("type" to JSONValue.Str(snake(type)))
    for (key in required + optional) {
        val value = action[key]
        if (key in optional && (value == null || value == JSONValue.Null)) continue
        value ?: return null
        if (key == "permissions") {
            core[key] = reviewPermissionProfile(value) ?: return null
            continue
        }
        when (key) {
            "port" -> if (value.intValue !in 1..65535) return null
            "argv", "files" -> if (value.arrayValue?.all { it.stringValue != null } != true) return null
            else -> if (value.stringValue == null) return null
        }
        core[snake(key)] = if (key in setOf("protocol", "source")) JSONValue.Str(snake(value.stringValue!!)) else value
    }
    val event = mutableMapOf("id" to (p["reviewId"] ?: return null), "turn_id" to (p["turnId"] ?: return null),
        "started_at_ms" to (p["startedAtMs"]?.takeIf { it.longValue != null } ?: return null), "status" to JSONValue.Str("denied"),
        "action" to JSONValue.Obj(core))
    for (key in listOf("completedAtMs","targetItemId","decisionSource","riskLevel","userAuthorization","rationale")) {
        val v = (p[key] ?: review[key])?.takeUnless { it == JSONValue.Null } ?: continue
        event[snake(key)] = if (key == "decisionSource") JSONValue.Str(snake(v.stringValue ?: return null)) else v
    }
    return JSONValue.Obj(event)
}

/** Preserve the exact requested access. Mixed/unknown filesystem dialects remain read-only in the UI. */
private fun reviewPermissionProfile(value: JSONValue): JSONValue? {
    val profile = value.objectValue ?: return null
    if (profile.keys.any { it !in setOf("fileSystem", "network") }) return null
    val result = mutableMapOf<String, JSONValue>()
    profile["network"]?.let { network ->
        if (network != JSONValue.Null) {
            val fields = network.objectValue ?: return null
            if (fields.keys.any { it != "enabled" }) return null
            val enabled = fields["enabled"]
            if (enabled != null && enabled != JSONValue.Null && enabled !is JSONValue.Bool) return null
        }
        result["network"] = network
    }
    val fs = profile["fileSystem"]
    if (fs == null || fs == JSONValue.Null) {
        if (fs != null) result["file_system"] = fs
        return JSONValue.Obj(result)
    }
    val fields = fs.objectValue?.filterValues { it != JSONValue.Null } ?: return null
    if (fields.keys.any { it !in setOf("entries", "globScanMaxDepth", "read", "write") }) return null
    val mapped = mutableMapOf<String, JSONValue>()
    val depth = fields["globScanMaxDepth"]
    if (depth != null) {
        if (depth !is JSONValue.NumLong || depth.value < 1) return null
        mapped["glob_scan_max_depth"] = depth
    }
    val entries = fields["entries"]
    if (entries != null) {
        if (entries !is JSONValue.Arr || "read" in fields || "write" in fields) return null
        mapped["entries"] = entries
    } else {
        val canonical = mutableListOf<JSONValue>()
        for (access in listOf("read", "write")) {
            val paths = fields[access] ?: continue
            if (paths !is JSONValue.Arr || paths.elements.any { it !is JSONValue.Str }) return null
            if (depth == null) mapped[access] = paths
            else for (path in paths.elements) canonical += JSONValue.Obj(mapOf(
                "path" to JSONValue.Obj(mapOf("type" to JSONValue.Str("path"), "path" to path)),
                "access" to JSONValue.Str(access),
            ))
        }
        if (depth != null) mapped["entries"] = JSONValue.Arr(canonical)
    }
    result["file_system"] = JSONValue.Obj(mapped)
    return JSONValue.Obj(result)
}
