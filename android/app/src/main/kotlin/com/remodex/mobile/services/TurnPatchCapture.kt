package com.remodex.mobile.services

import com.remodex.mobile.core.model.*
import java.time.Instant

/** Accumulates only complete patches observed since the start of a live turn. No repository probes. */
internal class TurnPatchCapture {
    private class Pending(val turn: String, val start: Instant) {
        val batches = linkedMapOf<String, String>()
        var aggregate: String? = null
        var incomplete = false
        var size = 0
    }
    private val pending = linkedMapOf<String, Pending>()
    @Synchronized fun clear() = pending.clear()

    @Synchronized fun observe(method: String, p: RPCObject, cwd: String?): AIChangeSet? {
        val thread = p["threadId"]?.stringValue ?: return null
        val turn = p["turnId"]?.stringValue ?: p["turn"]?.objectValue?.get("id")?.stringValue ?: return null
        if (method == "turn/started") {
            if (pending[thread]?.turn != turn) {
                pending.remove(thread)
                while (pending.size >= 12) pending.remove(pending.keys.first())
                pending[thread] = Pending(turn, Instant.now())
            }
            return null
        }
        val record = pending[thread]?.takeIf { it.turn == turn } ?: return null
        when (method) {
            "turn/diff/updated" -> {
                val diff = p["diff"]?.stringValue
                // Empty aggregate can also mean that a later edit undid an earlier edit.
                record.aggregate = diff?.takeIf { it.length <= 512_000 } ?: ""
            }
            "item/completed" -> {
                val item = p["item"]?.objectValue ?: return null
                if (item["type"]?.stringValue != "fileChange" || item["status"]?.stringValue != "completed") return null
                val id = item["id"]?.stringValue ?: return null
                if (id in record.batches || record.incomplete) return null
                val changes = item["changes"]?.arrayValue.orEmpty()
                val diffs = changes.mapNotNull { it.objectValue?.get("diff")?.stringValue }
                if (changes.isEmpty() || diffs.size != changes.size || diffs.any { !it.startsWith("diff --git ") } ||
                    diffs.sumOf { it.length.toLong() } + record.size > 512_000 || record.batches.size >= 64) {
                    record.incomplete = true
                    record.batches.clear()
                } else {
                    val patch = diffs.joinToString("") { it.trimEnd('\n') + "\n" }
                    record.batches[id] = patch
                    record.size += patch.length
                }
            }
            "turn/completed" -> {
                pending.remove(thread)
                if (p["turn"]?.objectValue?.get("status")?.stringValue != "completed" || cwd.isNullOrBlank()) return null
                val aggregate = record.aggregate
                if (aggregate != null && !aggregate.startsWith("diff --git ")) return null
                if (aggregate == null && (record.incomplete || record.batches.isEmpty())) return null
                val batches = if (aggregate == null) record.batches.map { AIPatchBatch(it.key, it.value) } else emptyList()
                val analyses = (aggregate?.let { listOf(it) } ?: batches.map { it.forwardUnifiedPatch }).map(AIUnifiedPatchParser::analyze)
                if (analyses.any { it.fileChanges.isEmpty() || it.unsupportedReasons.isNotEmpty() }) return null
                return AIChangeSet(threadId = thread, turnId = turn, repoRoot = cwd, createdAt = record.start,
                    finalizedAt = Instant.now(), status = AIChangeSetStatus.ready,
                    source = if (aggregate == null) AIChangeSetSource.fileChangeFallback else AIChangeSetSource.turnDiff,
                    forwardUnifiedPatch = aggregate.orEmpty(), fileChanges = analyses.flatMap { it.fileChanges }.distinctBy { it.path },
                    fallbackPatchBatches = batches, fallbackPatchCount = batches.size)
            }
        }
        return null
    }
}
