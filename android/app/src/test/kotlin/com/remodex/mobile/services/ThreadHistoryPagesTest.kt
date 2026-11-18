package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCError
import com.remodex.mobile.core.model.RPCMessage
import com.remodex.mobile.data.HistoryMessageMerge
import com.remodex.mobile.data.ThreadHistoryDecoder
import com.remodex.mobile.data.ThreadTurnSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import kotlin.test.assertFailsWith

class ThreadHistoryPagesTest {
    private fun obj(raw: String) = JSONValue.fromJsonElement(Json.parseToJsonElement(raw)) as JSONValue.Obj
    private fun response(raw: String) = RPCMessage(result = obj(raw))

    @Test fun initialReasoningOnlyPageContinuesToVisibleHistoryWithoutManualRefresh() = runTest {
        val cursors = mutableListOf<String?>()
        val page = fetchRecentThreadHistory("thread") { _, params ->
            val cursor = params.map["cursor"]?.stringValue
            cursors += cursor
            if (cursor == null) response("""{"data":[{"turnId":"turn","item":{"id":"reasoning","type":"reasoning","summary":["thinking"]}}],"nextCursor":"older"}""")
            else response("""{"data":[{"turnId":"turn","item":{"id":"answer","type":"agentMessage","text":"可显示的回答"}}],"nextCursor":"more"}""")
        }
        assertEquals(listOf(null, "older"), cursors)
        assertEquals("more", page.nextCursor?.stringValue)
        assertTrue(ThreadHistoryDecoder.decodeFromThreadRead("thread", page.threadObject(emptyMap())).any { it.text == "可显示的回答" })
    }

    @Test fun invisibleInitialPagesAreBoundedAndKeepCursorForExplicitContinuation() = runTest {
        var requests = 0
        val page = fetchRecentThreadHistory("thread") { _, _ ->
            requests++
            response("""{"data":[],"nextCursor":"cursor-$requests"}""")
        }
        assertEquals(4, requests)
        assertEquals("cursor-4", page.nextCursor?.stringValue)
    }

    @Test fun invisiblePageFollowUpFailureIsNotMisreportedAsEmptySuccess() = runTest {
        val failure = CodexServiceError.RpcFailure(RPCError(-32000, "history unavailable"))
        var requests = 0
        try {
            fetchRecentThreadHistory("thread") { _, _ ->
                if (++requests == 1) response("""{"data":[],"nextCursor":"older"}""") else throw failure
            }
            fail("Expected original RPC failure")
        } catch (actual: CodexServiceError.RpcFailure) { assertSame(failure, actual) }
        assertEquals(2, requests)
    }

    @Test fun recentPartialPageIsPublishedWithoutWaitingForMoreRequests() = runTest {
        var requests = 0
        val page = fetchRecentThreadHistory("thread") { _, params ->
            requests++
            when (params.map["cursor"]?.stringValue) {
                null -> response("""{"data":[{"turnId":"5","item":{"id":"item-5","type":"agentMessage","text":"hello"}}],"nextCursor":"c4"}""")
                "c4" -> response("""{"data":[{"id":"4","items":[]},{"id":"3","items":[]}],"nextCursor":"c2"}""")
                "c2" -> response("""{"data":[{"id":"2","items":[]},{"id":"1","items":[]}],"nextCursor":"older"}""")
                else -> error("unexpected cursor")
            }
        }
        assertEquals(1, requests)
        assertEquals(listOf("5"), page.turns.map { it.objectValue?.get("id")?.stringValue })
        assertEquals(JSONValue.Str("c4"), page.nextCursor)
    }

    @Test fun olderPaginationRejectsRepeatedCursorRatherThanLooping() = runTest {
        assertFailsWith<CodexServiceError.InvalidResponse> {
            fetchThreadHistoryPage("thread", JSONValue.Str("repeat")) { _, _ -> response("""{"data":[{"id":"same","items":[]}],"nextCursor":"repeat"}""") }
        }
    }

    @Test fun firstPageUsesRealBoundedTurnsEndpointAndRetainsOpaqueCursor() = runTest {
        val page = fetchThreadHistoryPage("thread", null) { method, params ->
            assertEquals("thread/turns/list", method)
            assertEquals(obj("""{"threadId":"thread","limit":5,"sortDirection":"desc","itemsView":"full"}"""), params)
            response("""{"data":[{"id":"new","items":[]},{"id":"old","items":[]}],"nextCursor":"opaque+/= cursor","backwardsCursor":"reverse"}""")
        }
        assertEquals(listOf("old", "new"), page.turns.map { it.objectValue?.get("id")?.stringValue })
        assertEquals(JSONValue.Str("opaque+/= cursor"), page.nextCursor)
    }

    @Test fun olderPagePassesExactCursorAndOnlyNullNextCursorEndsHistory() = runTest {
        val cursor = JSONValue.Str("opaque+/= cursor")
        val page = fetchThreadHistoryPage("thread", cursor) { method, params ->
            assertEquals("thread/turns/list", method)
            assertEquals(cursor, params.map["cursor"])
            response("""{"data":[],"nextCursor":"another","hasMore":false}""")
        }
        assertEquals(JSONValue.Str("another"), page.nextCursor)
        val end = fetchThreadHistoryPage("thread", page.nextCursor) { _, _ -> response("""{"data":[],"nextCursor":null}""") }
        assertNull(end.nextCursor)
    }

    @Test fun latestSummaryPageRecoversInterruptibleTurnWithoutLoadingHistory() = runTest {
        val page = fetchThreadHistoryPage("thread", null, limit = 1, itemsView = "summary") { method, params ->
            assertEquals("thread/turns/list", method)
            assertEquals(JSONValue.NumLong(1), params.map["limit"])
            assertEquals(JSONValue.Str("summary"), params.map["itemsView"])
            response("""{"data":[{"id":"running-turn","status":"inProgress","items":[]}],"nextCursor":"older"}""")
        }
        assertEquals("running-turn", ThreadTurnSnapshot.fromThreadObject(page.threadObject(emptyMap())).interruptibleTurnId)
    }

    @Test fun malformedPaginationFailsInsteadOfMarkingHistoryExhausted() = runTest {
        try {
            fetchThreadHistoryPage("thread", null) { _, _ -> response("""{"data":[]}""") }
            fail("Missing nextCursor must not look like exhausted history")
        } catch (expected: CodexServiceError.InvalidResponse) {
            assertTrue(expected.message!!.contains("nextCursor"))
        }
    }

    @Test fun pageFailureAndCallerCancellationPreserveOriginalIdentityWithoutFallback() = runTest {
        for (failure in listOf(CodexServiceError.RpcFailure(RPCError(-32602, "bad cursor")), CancellationException("caller left"))) {
            var calls = 0
            try {
                fetchThreadHistoryPage("thread", null) { _, _ -> calls++; throw failure }
                fail("Expected failure")
            } catch (actual: Exception) {
                assertSame(failure, actual)
            }
            assertEquals(1, calls)
        }
    }

    @Test fun olderPagesPrependAndRepeatedPagesDoNotDuplicateItems() = runTest {
        suspend fun page(id: String, time: Int, cursor: String) = fetchThreadHistoryPage("thread", null) { _, _ ->
            response("""{"data":[{"id":"$id","startedAt":$time,"status":"completed","items":[{"id":"item-$id","type":"agentMessage","text":"$id"}]}],"nextCursor":$cursor}""")
        }
        val recent = ThreadHistoryDecoder.decodeFromThreadRead("thread", page("new", 200, "\"older\"").threadObject(emptyMap()))
        val older = ThreadHistoryDecoder.decodeFromThreadRead("thread", page("old", 100, "null").threadObject(emptyMap()))
        val merged = HistoryMessageMerge.mergeOlderPage(recent, older, "new")
        assertEquals(listOf("old", "new"), merged.map { it.turnId })
        assertEquals(listOf("old", "new"), HistoryMessageMerge.mergeOlderPage(merged, older, "old").map { it.turnId })
    }
    @Test fun metadataReadDoesNotRequestFullTurns() = runTest {
        val metadata = fetchThreadMetadata("thread") { method, params ->
            assertEquals("thread/read", method)
            assertEquals(obj("""{"threadId":"thread","includeTurns":false}"""), params)
            response("""{"thread":{"id":"thread","model":"gpt-6-astra","turns":[]}}""")
        }
        assertEquals("gpt-6-astra", metadata.map["model"]?.stringValue)
    }

    @Test fun olderPageBoundaryPreservesCachedOlderTurnsOverlapAndLiveTail() = runTest {
        val page = fetchThreadHistoryPage("thread", null) { _, _ -> response(
            """{"data":[{"id":"new","items":[{"id":"item-new","type":"agentMessage","text":"new"}]},{"id":"gap","items":[{"id":"item-gap","type":"agentMessage","text":"gap"}]}],"nextCursor":null}""",
        ) }
        val incoming = ThreadHistoryDecoder.decodeFromThreadRead("thread", page.threadObject(emptyMap()))
        val recent = incoming.last()
        val cached = recent.copy(id = "cached-row", itemId = "item-cached", turnId = "cached", text = "cached")
        val live = recent.copy(id = "live-row", itemId = "item-live", turnId = "live", text = "still streaming", isStreaming = true)
        val existing = listOf(cached, recent, live)
        val merged = HistoryMessageMerge.mergeOlderPage(existing, incoming, "new")
        assertEquals(listOf("cached", "gap", "new", "live"), merged.map { it.turnId })
        assertEquals(live, merged.last())
        assertEquals(merged, HistoryMessageMerge.mergeOlderPage(merged, incoming, "gap"))
        assertEquals(existing, HistoryMessageMerge.mergeOlderPage(existing, emptyList(), "new"))
    }

}
