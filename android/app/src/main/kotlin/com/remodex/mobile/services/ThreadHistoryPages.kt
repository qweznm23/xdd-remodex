package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage

internal data class ThreadHistoryPage(val turns: List<JSONValue>, val nextCursor: JSONValue?, val itemPage: Boolean = false) {
    val itemKeys: Set<Pair<String, String>> get() = turns.flatMap { turn ->
        val id = turn.objectValue?.get("id")?.stringValue ?: return@flatMap emptyList()
        turn.objectValue?.get("items")?.arrayValue.orEmpty().mapNotNull { item ->
            item.objectValue?.get("id")?.stringValue?.let { id to it }
        }
    }.toSet()
    fun threadObject(metadata: Map<String, JSONValue>): Map<String, JSONValue> =
        metadata + ("turns" to JSONValue.Arr(turns))
}

/**
 * The host can allocate a thread id before it exposes the first page. Carry the original
 * RPC and method separately from an empty page, which would establish a history boundary.
 */
internal class ThreadAwaitingFirstMessage(val method: String, val failure: CodexServiceError.RpcFailure) :
    Exception(failure.message, failure)

internal suspend fun readInitialThreadRpc(
    threadId: String, method: String, params: JSONValue.Obj,
    request: suspend (String, JSONValue.Obj) -> RPCMessage,
): RPCMessage = try {
    request(method, params)
} catch (failure: CodexServiceError.RpcFailure) {
    val rpc = failure.rpcError
    val description = rpc.message.substringBefore(';').trim()
    val initialRead = params.map["cursor"].let { it == null || it == JSONValue.Null }
    if (initialRead && rpc.code == -32600 &&
        (description == "thread $threadId is not materialized yet" ||
            description == "thread $threadId is not yet materialized") &&
        rpc.message.substringAfter(';', "").contains("before first user message", ignoreCase = true)) {
        throw ThreadAwaitingFirstMessage(method, failure)
    }
    throw failure
}

/** Skip invisible-only pages, with a strict bound; never scan an entire conversation on open. */
internal suspend fun fetchRecentThreadHistory(
    threadId: String,
    request: suspend (String, JSONValue.Obj) -> RPCMessage,
): ThreadHistoryPage {
    var page = try {
        fetchThreadItemPage(threadId, null, request)
    } catch (error: CodexServiceError.RpcFailure) {
        if (!isUnsupportedItemPagination(error)) throw error
        fetchThreadHistoryPage(threadId, null, request = request)
    }
    var loadedPages = 1
    val visited = mutableSetOf<JSONValue>()
    while (page.itemPage && page.nextCursor != null && loadedPages < 4 &&
        com.remodex.mobile.data.ThreadHistoryDecoder.decodeFromThreadRead(threadId, page.threadObject(emptyMap()))
            .none { it.kind != com.remodex.mobile.core.model.CodexMessageKind.thinking }) {
        val cursor = page.nextCursor!!
        if (!visited.add(cursor)) throw CodexServiceError.InvalidResponse("Initial item pagination repeated a cursor")
        val older = fetchThreadItemPage(threadId, cursor, request)
        page = older.copy(turns = older.turns + page.turns)
        loadedPages++
    }
    return page
}

internal fun isUnsupportedItemPagination(error: CodexServiceError.RpcFailure): Boolean {
    val rpc = error.rpcError
    if (rpc.code == -32601) return true
    val message = rpc.message.lowercase()
    return rpc.code == -32600 && message.contains("item") &&
        (message.contains("pagination") || message.contains("thread/items/list")) &&
        (message.contains("not supported") || message.contains("unsupported"))
}

/** Items can straddle pages within a single turn. Never interpret them as complete turns. */
internal suspend fun fetchThreadItemPage(
    threadId: String,
    cursor: JSONValue?,
    request: suspend (String, JSONValue.Obj) -> RPCMessage,
): ThreadHistoryPage {
    val params = JSONValue.Obj(buildMap {
        put("threadId", JSONValue.Str(threadId))
        put("limit", JSONValue.NumLong(30))
        put("sortDirection", JSONValue.Str("desc"))
        cursor?.takeUnless { it == JSONValue.Null }?.let { put("cursor", it) }
    })
    val result = request("thread/items/list", params).result as? JSONValue.Obj
        ?: throw CodexServiceError.InvalidResponse("thread/items/list missing result object")
    val data = result.map["data"] as? JSONValue.Arr
        ?: throw CodexServiceError.InvalidResponse("thread/items/list missing data array")
    val next = result.map["nextCursor"]
    if (next != JSONValue.Null && next !is JSONValue.Str) throw CodexServiceError.InvalidResponse("thread/items/list missing or invalid nextCursor")
    if (cursor != null && cursor != JSONValue.Null && next == cursor) throw CodexServiceError.InvalidResponse("Item pagination repeated a cursor")
    val turns = data.elements.asReversed().map { value ->
        val entry = value.objectValue ?: throw CodexServiceError.InvalidResponse("Invalid history item entry")
        val turnId = entry["turnId"]?.stringValue?.takeIf { it.isNotBlank() }
            ?: throw CodexServiceError.InvalidResponse("History item missing turnId")
        val item = entry["item"]?.objectValue ?: throw CodexServiceError.InvalidResponse("History entry missing item")
        if (item["id"]?.stringValue.isNullOrBlank() || item["type"]?.stringValue.isNullOrBlank()) {
            throw CodexServiceError.InvalidResponse("History item missing id/type")
        }
        val started = entry["startedAtMs"] ?: item["startedAtMs"]
        val timestamp = when (started) {
            is JSONValue.NumLong -> JSONValue.Str(java.time.Instant.ofEpochMilli(started.value).toString())
            is JSONValue.NumDouble -> JSONValue.Str(java.time.Instant.ofEpochMilli(started.value.toLong()).toString())
            else -> null
        }
        JSONValue.Obj(buildMap {
            put("id", JSONValue.Str(turnId))
            put("items", JSONValue.Arr(listOf(JSONValue.Obj(item + if (timestamp != null) mapOf("createdAt" to timestamp) else emptyMap()))))
        })
    }
    return ThreadHistoryPage(turns, next.takeUnless { it == JSONValue.Null }, itemPage = true)
}

internal suspend fun fetchThreadMetadata(
    threadId: String,
    request: suspend (String, JSONValue.Obj) -> RPCMessage,
): JSONValue.Obj {
    val response = request("thread/read", JSONValue.Obj(mapOf(
        "threadId" to JSONValue.Str(threadId),
        "includeTurns" to JSONValue.Bool(false),
    )))
    return (response.result as? JSONValue.Obj)?.map?.get("thread") as? JSONValue.Obj
        ?: throw CodexServiceError.InvalidResponse("thread/read metadata missing thread object")
}

internal suspend fun fetchThreadHistoryPage(
    threadId: String,
    cursor: JSONValue?,
    limit: Int = 5,
    itemsView: String = "full",
    request: suspend (String, JSONValue.Obj) -> RPCMessage,
): ThreadHistoryPage {
    val params = buildMap {
        put("threadId", JSONValue.Str(threadId))
        put("limit", JSONValue.NumLong(limit.toLong()))
        put("sortDirection", JSONValue.Str("desc"))
        put("itemsView", JSONValue.Str(itemsView))
        cursor?.takeUnless { it == JSONValue.Null }?.let { put("cursor", it) }
    }
    val result = request("thread/turns/list", JSONValue.Obj(params)).result as? JSONValue.Obj
        ?: throw CodexServiceError.InvalidResponse("thread/turns/list missing result object")
    val data = result.map["data"] as? JSONValue.Arr
        ?: throw CodexServiceError.InvalidResponse("thread/turns/list missing data array")
    val nextCursor = result.map["nextCursor"]
    if (nextCursor != JSONValue.Null && nextCursor !is JSONValue.Str) {
        throw CodexServiceError.InvalidResponse("thread/turns/list missing or invalid nextCursor")
    }
    if (cursor != null && cursor != JSONValue.Null && nextCursor == cursor) {
        throw CodexServiceError.InvalidResponse("History pagination repeated a cursor")
    }
    val turns = data.elements.asReversed().map { value ->
        val turn = value as? JSONValue.Obj
            ?: throw CodexServiceError.InvalidResponse("thread/turns/list contains a non-object turn")
        // Native Turn.startedAt is Unix seconds; the shared history decoder consumes createdAt.
        val startedAt = turn.map["startedAt"]
        if (turn.map["createdAt"] == null && (startedAt is JSONValue.NumLong || startedAt is JSONValue.NumDouble)) {
            JSONValue.Obj(turn.map + ("createdAt" to startedAt))
        } else {
            turn
        }
    }
    return ThreadHistoryPage(turns, nextCursor.takeUnless { it == JSONValue.Null })
}
