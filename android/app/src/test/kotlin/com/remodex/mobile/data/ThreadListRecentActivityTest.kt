package com.remodex.mobile.data

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCMessage
import com.remodex.mobile.services.MinimalSendRepository
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class ThreadListRecentActivityTest {
    private fun thread(id: String, created: Long, updated: Long) = JSONValue.Obj(mapOf(
        "id" to JSONValue.Str(id), "createdAt" to JSONValue.NumLong(created),
        "updatedAt" to JSONValue.NumLong(updated),
    ))
    private fun page(rows: List<JSONValue>, next: String? = null) = RPCMessage.success(JSONValue.NumLong(1), JSONValue.Obj(mapOf(
        "data" to JSONValue.Arr(rows), "nextCursor" to (next?.let(JSONValue::Str) ?: JSONValue.Null),
    )))

    @Test fun oldActiveThreadStaysFirstAcrossPagesAndDuplicateRecords() = runTest {
        val requests = mutableListOf<String?>()
        val repository = MinimalSendRepository { _, params ->
            val p = params!!.objectValue!!
            assertEquals("recency_at", p["sortKey"]?.stringValue)
            assertEquals("desc", p["sortDirection"]?.stringValue)
            if (p["archived"]?.boolValue == true) page(emptyList())
            else {
                val cursor = p["cursor"]?.stringValue
                requests.add(cursor)
                when (cursor) {
                    null -> page(listOf(thread("old-active", 100, 900)), "second")
                    "second" -> page(listOf(thread("new-idle", 700, 700), thread("old-active", 100, 200)))
                    else -> error("unexpected cursor")
                }
            }
        }
        val rows = ThreadListSync.fetchMerged(repository)
        assertEquals(listOf(null, "second"), requests)
        assertEquals(listOf("old-active", "new-idle"), rows.map { it.id })
        assertEquals(900L, rows.first().updatedAt!!.epochSecond)
    }

    @Test fun archivedFailureAndCancellationAreNotConvertedIntoEmptySuccess() = runTest {
        for (failure in listOf(IOException("connection reset", IllegalStateException("transport detail")), CancellationException("caller cancelled"))) {
            val repo = MinimalSendRepository { _, params ->
                if (params!!.objectValue!!["archived"]?.boolValue == true) throw failure
                page(listOf(thread("active", 100, 200)))
            }
            val caught = assertFails { ThreadListSync.fetchMerged(repo) }
            assertSame(failure, caught)
            assertSame(failure.cause, caught.cause)
        }
    }
    @Test fun unopenedThreadListPreservesLiveStatusWithoutReadingConversationHistory() = runTest {
        val repository = MinimalSendRepository { method, params ->
            assertEquals("thread/list", method)
            if (params!!.objectValue!!["archived"]?.boolValue == true) page(emptyList())
            else page(listOf(JSONValue.Obj(mapOf("id" to JSONValue.Str("unopened"),
                "status" to JSONValue.Obj(mapOf("type" to JSONValue.Str("active"), "activeFlags" to JSONValue.Arr(emptyList())))))))
        }
        val thread = ThreadListSync.fetchMerged(repository).single()
        assertEquals(com.remodex.mobile.core.model.ThreadRuntimeStatus.Active, thread.runtimeStatus)
    }

}
