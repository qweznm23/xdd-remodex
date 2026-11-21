package com.remodex.mobile.data

import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState
import com.remodex.mobile.core.model.CodexSidebarState
import com.remodex.mobile.core.model.CodexThreadReadState
import com.remodex.mobile.core.model.JSONValue
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * Fetches `thread/list` pages (parity with [CodexService.fetchServerThreads] on iOS) and merges
 * active + archived results for sidebar state.
 */
internal object ThreadListSync {
    private const val PAGE_LIMIT = 40

    private val SOURCE_KINDS =
        listOf(
            "cli",
            "vscode",
            "appServer",
            "exec",
            "unknown",
        )

    data class Snapshot(val threads: List<CodexThread>, val sidebar: CodexSidebarState, val deletionScope: String? = null)
    // Codex's built-in section has a stable identity even when its visible name changes.
    const val PINNED_SECTION_ID = "01984de2-8f74-7c91-a3b2-5c5e937cf318"

    suspend fun fetchMerged(repository: CodexRepository): List<CodexThread> {
        val receipts = mutableMapOf<String, CodexThreadReadState>()
        return sortThreads(mergeActiveAndArchived(fetchPages(repository, false, receipts), fetchPages(repository, true, receipts)))
    }

    suspend fun fetchSnapshot(repository: CodexRepository): Snapshot {
        val receipts = mutableMapOf<String, CodexThreadReadState>()
        val scopes = mutableSetOf<String?>()
        val active = fetchPages(repository, false, receipts, catalogScopes = scopes)
        val archived = fetchPages(repository, true, receipts, catalogScopes = scopes)
        var sectionCursor: JSONValue? = null
        val seen = mutableSetOf<JSONValue>()
        var pinnedSection: String? = null
        do {
            val row = repository.sendRequest("threadSection/list", JSONValue.Obj(mapOf(
                "limit" to JSONValue.NumLong(100), "cursor" to (sectionCursor ?: JSONValue.Null),
            ))).result?.objectValue ?: error("threadSection/list missing result")
            val sections = row["data"]?.arrayValue ?: error("threadSection/list missing data")
            if (sections.any { it.objectValue?.get("id")?.stringValue == PINNED_SECTION_ID }) pinnedSection = PINNED_SECTION_ID
            sectionCursor = row["nextCursor"]?.takeUnless { it == JSONValue.Null }
            if (sectionCursor != null) check(seen.add(sectionCursor!!)) { "threadSection/list repeated cursor" }
        } while (sectionCursor != null)
        val pinned = if (pinnedSection == null) emptyList() else fetchPages(repository, false, receipts, pinnedSection)
        val threads = sortThreads(mergeActiveAndArchived((active + pinned).distinctBy { it.id }, archived))
        return Snapshot(threads, CodexSidebarState(
            initialized = true, threads = receipts,
            recentThreadIds = active.filter { it.recencyAt != null }.sortedByDescending { it.recencyAt }.map { it.id }.take(30),
            pinnedThreadIds = pinned.map { it.id }, pinnedSectionId = pinnedSection,
        ), scopes.singleOrNull())
    }

    private suspend fun fetchPages(repository: CodexRepository, archived: Boolean,
        receipts: MutableMap<String, CodexThreadReadState>, section: String? = null, catalogScopes: MutableSet<String?>? = null,
    ): List<CodexThread> {
        val out = ArrayList<CodexThread>()
        var cursor: JSONValue? = null
        val seen = mutableSetOf<JSONValue>()
        do {
            val fields = mutableMapOf(
                "sourceKinds" to JSONValue.Arr(SOURCE_KINDS.map { JSONValue.Str(it) }),
                "useStateDbOnly" to JSONValue.Bool(true),
                "cursor" to (cursor ?: JSONValue.Null), "limit" to JSONValue.NumLong(PAGE_LIMIT.toLong()),
                "sortKey" to JSONValue.Str(if (section == null) "recency_at" else "section_position"),
                "sortDirection" to JSONValue.Str(if (section == null) "desc" else "asc"),
            )
            if (archived) fields["archived"] = JSONValue.Bool(true)
            if (section != null) fields["sectionId"] = JSONValue.Str(section)
            val result = repository.sendRequest("thread/list", JSONValue.Obj(fields)).result ?: error("thread/list missing result")
            catalogScopes?.add(result.objectValue?.get("remodexCatalogDeletionScope")?.stringValue?.takeIf { it.isNotBlank() })
            val (threads, next) = parseThreadListPage(result)
            result.objectValue?.get("readStates")?.objectValue?.forEach { (id, value) ->
                CodexThreadReadState.decode(value)?.let { receipts[id] = it }
            }
            out += threads
            cursor = next.takeUnless { it == JSONValue.Null }
            if (cursor != null) check(seen.add(cursor!!)) { "thread/list repeated cursor" }
        } while (cursor != null)
        return out
    }

    private fun parseThreadListPage(result: JSONValue): Pair<List<CodexThread>, JSONValue> {
        val obj = result as? JSONValue.Obj ?: error("thread/list result must be an object")
        val m = obj.map
        val page =
            m["data"]?.arrayValue
                ?: m["items"]?.arrayValue
                ?: m["threads"]?.arrayValue
                ?: error("thread/list response missing data array")
        val threads =
            page.mapNotNull { el ->
                val jo = jsonObjectFromJsonValue(el) ?: return@mapNotNull null
                runCatching { CodexThread.fromJsonObject(jo) }.getOrNull()
            }
        val next =
            m["nextCursor"]
                ?: m["next_cursor"]
                ?: JSONValue.Null
        return threads to next
    }

    private fun jsonObjectFromJsonValue(v: JSONValue): JsonObject? {
        if (v !is JSONValue.Obj) return null
        return buildJsonObject {
            v.map.forEach { (k, child) ->
                put(k, JSONValue.toJsonElement(child))
            }
        }
    }

    private fun mergeActiveAndArchived(
        active: List<CodexThread>,
        archived: List<CodexThread>,
    ): List<CodexThread> {
        val byId = LinkedHashMap<String, CodexThread>()
        for (t in active) {
            val existing = byId[t.id]
            if (existing == null || activityDate(t) > activityDate(existing)) {
                byId[t.id] = t.copy(syncState = CodexThreadSyncState.live)
            }
        }
        val activeIds = byId.keys.toSet()
        for (t in archived) {
            val existing = byId[t.id]
            if (t.id !in activeIds && (existing == null || activityDate(t) > activityDate(existing))) {
                byId[t.id] = t.copy(syncState = CodexThreadSyncState.archivedLocal)
            }
        }
        return byId.values.toList()
    }

    private fun activityDate(thread: CodexThread): Instant = thread.updatedAt ?: thread.createdAt ?: Instant.EPOCH

    private fun sortThreads(value: List<CodexThread>): List<CodexThread> {
        val past = Instant.EPOCH
        return value.sortedWith { lhs, rhs ->
            val l = lhs.recencyAt ?: lhs.updatedAt ?: lhs.createdAt ?: past
            val r = rhs.recencyAt ?: rhs.updatedAt ?: rhs.createdAt ?: past
            r.compareTo(l)
        }
    }
}
