package com.remodex.mobile.data

import com.remodex.mobile.core.model.*
import com.remodex.mobile.services.MinimalSendRepository
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlin.test.*

class CodexSidebarSnapshotTest {
    private fun row(id: String, recency: Long, updated: Long) = JSONValue.Obj(mapOf("id" to JSONValue.Str(id),"recencyAt" to JSONValue.NumLong(recency),"updatedAt" to JSONValue.NumLong(updated)))
    private fun response(rows: List<JSONValue>, next: String? = null, receipts: Map<String,JSONValue> = emptyMap()) = RPCMessage.success(JSONValue.NumLong(1),JSONValue.Obj(mapOf(
        "data" to JSONValue.Arr(rows),"nextCursor" to (next?.let(JSONValue::Str) ?: JSONValue.Null),"readStates" to JSONValue.Obj(receipts))))
    @Test fun serverRecencySectionPositionAndReadReceiptsAreAuthoritativeAcrossPages() = runTest {
        val receipt=JSONValue.Obj(mapOf("revision" to JSONValue.Str("codex-r1"),"firstUnread" to JSONValue.Obj(mapOf("type" to JSONValue.Str("threadStart")))))
        val calls=mutableListOf<String>()
        val repo=MinimalSendRepository { method,params ->
            val p=params!!.objectValue!!;calls+=method
            if(method=="threadSection/list") {
                if(p["cursor"]==JSONValue.Null) response(emptyList(),"section-next")
                else response(listOf(JSONValue.Obj(mapOf("id" to JSONValue.Str(ThreadListSync.PINNED_SECTION_ID),"name" to JSONValue.Str("Renamed Pinned")))))
            } else {
                assertEquals("thread/list",method);assertEquals(true,p["useStateDbOnly"]?.boolValue)
                if(p["sectionId"]?.stringValue!=null) {
                    assertEquals("section_position",p["sortKey"]?.stringValue);assertEquals("asc",p["sortDirection"]?.stringValue)
                    if(p["cursor"]==JSONValue.Null) response(listOf(row("p2",20,90)),"pin-next")
                    else response(listOf(row("p1",50,80)))
                } else {
                    assertEquals("recency_at",p["sortKey"]?.stringValue)
                    if(p["archived"]?.boolValue==true) response(emptyList())
                    else response(listOf(row("recent",200,10),row("updated-only",100,500)),receipts=mapOf("recent" to receipt))
                }
            }
        }
        val result=ThreadListSync.fetchSnapshot(repo)
        assertEquals(listOf("recent","updated-only"),result.sidebar.recentThreadIds)
        assertEquals(listOf("p2","p1"),result.sidebar.pinnedThreadIds)
        assertEquals(ThreadListSync.PINNED_SECTION_ID,result.sidebar.pinnedSectionId)
        assertEquals(CodexThreadReadState("codex-r1",true),result.sidebar.threads["recent"])
        assertNull(result.sidebar.threads["updated-only"])
        assertEquals("recent",result.threads.first().id)
        assertEquals(6,calls.size)
    }
    @Test fun sectionFailureAndCancellationPreserveOriginalEvidence() = runTest {
        for(failure in listOf(java.io.IOException("source",IllegalArgumentException("cause")),CancellationException("cancelled"))) {
            val repo=MinimalSendRepository {method,_->if(method=="threadSection/list") throw failure else response(emptyList())}
            assertSame(failure,assertFails{ThreadListSync.fetchSnapshot(repo)})
        }
    }
    @Test fun repeatedCursorIsFailureInsteadOfSilentlyTruncatingSidebar() = runTest {
        val repo=MinimalSendRepository {_,_->response(emptyList(),"repeated")}
        val failure=assertFailsWith<IllegalStateException>{ThreadListSync.fetchSnapshot(repo)}
        assertTrue(failure.message!!.contains("repeated cursor"))
    }
}
