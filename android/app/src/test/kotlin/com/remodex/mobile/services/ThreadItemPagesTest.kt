package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import com.remodex.mobile.data.HistoryMessageMerge
import com.remodex.mobile.data.ThreadHistoryDecoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ThreadItemPagesTest {
    private fun response(raw: String) = RPCMessage(result = JSONValue.fromJsonElement(Json.parseToJsonElement(raw)))
    private fun row(n: Int, kind: CodexMessageKind = CodexMessageKind.chat) = CodexMessage(
        id = "local-$n", threadId = "thread", turnId = "same-turn", itemId = "item-$n",
        role = if (kind == CodexMessageKind.chat) CodexMessageRole.assistant else CodexMessageRole.system,
        kind = kind, text = "message $n", createdAt = java.time.Instant.ofEpochSecond(n.toLong()),
    )

    @Test fun firstPageUsesOfficialItemsAndRetainsQuestionsImagesAndTimestamps() = runTest {
        val page = fetchRecentThreadHistory("thread") { method, params ->
            assertEquals("thread/items/list", method)
            assertEquals(JSONValue.NumLong(30), params.map["limit"])
            assertEquals("desc", params.map["sortDirection"]?.stringValue)
            response("""{"data":[
                {"turnId":"t","startedAtMs":1730000001000,"item":{"id":"q","type":"agentMessage","text":"","questions":[{"title":"选择","options":["甲","乙"]}]}},
                {"turnId":"t","startedAtMs":1730000000000,"item":{"id":"img","type":"imageView","path":"/tmp/example.png"}}
            ],"nextCursor":"opaque+/="}""")
        }
        assertTrue(page.itemPage)
        assertEquals(JSONValue.Str("opaque+/="), page.nextCursor)
        val rows = ThreadHistoryDecoder.decodeFromThreadRead("thread", page.threadObject(emptyMap()))
        assertEquals(listOf("img", "q"), rows.map { it.itemId })
        assertTrue(rows.first().attachments.isNotEmpty())
        assertNotNull(rows.last().structuredUserInputRequest)
        assertEquals(1730000001000, rows.last().createdAt.toEpochMilli())
    }

    @Test fun onlyExplicitUnsupportedMethodFallsBackToNativeTurnPaging() = runTest {
        val calls = mutableListOf<String>()
        val page = fetchRecentThreadHistory("thread") { method, _ ->
            calls += method
            if (method == "thread/items/list") throw CodexServiceError.RpcFailure(RPCError(-32601, "Method not found"))
            response("""{"data":[{"id":"legacy","items":[]}],"nextCursor":null}""")
        }
        assertEquals(listOf("thread/items/list", "thread/turns/list"), calls)
        assertFalse(page.itemPage)
        for (failure in listOf(CodexServiceError.RpcFailure(RPCError(-32602, "invalid cursor")),
            CodexServiceError.RpcFailure(RPCError(-32000, "network unavailable")), CancellationException("cancelled"))) {
            var count = 0
            try { fetchRecentThreadHistory("thread") { _, _ -> count++; throw failure }; fail() }
            catch (actual: Exception) { assertSame(failure, actual) }
            assertEquals(1, count)
        }
    }

    @Test fun malformedAndRepeatedCursorsCannotSilentlyEndHistory() = runTest {
        for (json in listOf("""{"data":[]}""", """{"data":[],"nextCursor":"same"}""",
            """{"data":[{"turnId":"t","item":{"type":"agentMessage"}}],"nextCursor":null}""")) {
            try { fetchThreadItemPage("thread", JSONValue.Str("same")) { _, _ -> response(json) }; fail() }
            catch (_: CodexServiceError.InvalidResponse) { }
        }
    }

    @Test fun olderPageWithinSameTurnIsInsertedBeforeExistingItemsAndDeduplicatesOverlap() {
        val live = row(5).copy(isStreaming = true, text = "live newer output")
        val existing = listOf(row(3), row(4), live)
        val incoming = listOf(row(1), row(2), row(3).copy(id = "new-local-3"))
        val out = HistoryMessageMerge.mergeItemPage(existing, incoming, older = true, beforeMessageId = "local-3")
        assertEquals((1..5).map { "item-$it" }, out.map { it.itemId })
        assertEquals("local-3", out[2].id)
        assertEquals(live, out.last())
        assertEquals(out, HistoryMessageMerge.mergeItemPage(out, incoming, true, "local-3"))
    }

    @Test fun itemPagesDoNotCollapseDistinctReasoningOrIdenticalCommandsInOneTurn() {
        for (kind in listOf(CodexMessageKind.thinking, CodexMessageKind.commandExecution, CodexMessageKind.fileChange)) {
            val a = row(1, kind).copy(text = "same command")
            val b = row(2, kind).copy(text = "same command")
            val out = HistoryMessageMerge.mergeItemPage(listOf(b), listOf(a), true, b.id)
            assertEquals(listOf("item-1", "item-2"), out.map { it.itemId })
        }
    }

    @Test fun recentPartialPageKeepsInterleavedLiveItemsAndFillsMissingPrecedingItems() {
        val existing = listOf(row(2), row(4), row(6).copy(isStreaming = true))
        val out = HistoryMessageMerge.mergeItemPage(existing, listOf(row(1), row(2), row(3), row(4), row(5)))
        assertEquals((1..6).map { "item-$it" }, out.map { it.itemId })
        assertTrue(out.last().isStreaming)
    }
}
