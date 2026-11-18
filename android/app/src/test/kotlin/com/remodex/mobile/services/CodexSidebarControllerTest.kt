package com.remodex.mobile.services

import com.remodex.mobile.core.model.*
import kotlin.test.*

class CodexSidebarControllerTest {
    @Test fun duplicateUnreadSnapshotDoesNotDiscardReadAcknowledgement() {
        val controller = CodexSidebarController(); controller.reset("device")
        val unread = CodexThreadReadState("seen-row", true)
        controller.receipt("device", "thread", unread)
        val ack = controller.begin("device")
        val history = controller.begin("device")
        controller.receipt("device", "thread", unread, history)
        controller.receipt("device", "thread", CodexThreadReadState("read-ack", false), ack)
        assertFalse(controller.state.value.threads.getValue("thread").unread)
    }
    @Test fun liveReceiptWinsOverOlderSnapshotAndReadAcknowledgement() {
        val c = CodexSidebarController(); c.reset("a")
        val list = c.begin("a"); val ack = c.begin("a")
        val fresh = CodexThreadReadState("new-opaque", true)
        c.receipt("a", "thread", fresh)
        c.receipt("a", "thread", CodexThreadReadState("old-ack", false), ack)
        c.apply(list, CodexSidebarState(initialized=true, threads=mapOf("thread" to CodexThreadReadState("old-list", false))))
        assertEquals(fresh, c.state.value.threads["thread"])
        c.finish(list);c.finish(ack)
        val next = c.begin("a")
        c.apply(next, CodexSidebarState(initialized=true, threads=mapOf("thread" to CodexThreadReadState("server-read", false))))
        assertFalse(c.state.value.threads.getValue("thread").unread)
    }
    @Test fun deviceSwitchRejectsLateResultsIncludingSwitchBack() {
        val c=CodexSidebarController();val old=c.begin("a")
        c.reset("b");c.reset("a")
        c.apply(old,CodexSidebarState(initialized=true,pinnedThreadIds=listOf("old")))
        assertFalse(c.state.value.initialized);assertTrue(c.state.value.pinnedThreadIds.isEmpty())
        c.receipt("b","thread",CodexThreadReadState("b",true));assertTrue(c.state.value.threads.isEmpty())
    }
    @Test fun pinNotificationInvalidatesOldOrderingWhileNewRefreshCanReplaceIt() {
        val c=CodexSidebarController();val initial=c.begin("a")
        c.apply(initial,CodexSidebarState(initialized=true,pinnedThreadIds=listOf("first")))
        c.finish(initial);val old=c.begin("a");c.sectionsChanged()
        c.apply(old,CodexSidebarState(initialized=true,pinnedThreadIds=listOf("stale")))
        assertEquals(listOf("first"),c.state.value.pinnedThreadIds)
        val fresh=c.begin("a");c.apply(fresh,CodexSidebarState(initialized=true,pinnedThreadIds=listOf("second")))
        assertEquals(listOf("second"),c.state.value.pinnedThreadIds)
    }
    @Test fun unavailableReceiptIsUnknownRatherThanLocallyInferredReadOrUnread() {
        assertNull(CodexThreadReadState.decode(JSONValue.Null))
        assertFailsWith<IllegalStateException>{CodexThreadReadState.decode(JSONValue.Obj(emptyMap()))}
        val read=CodexThreadReadState.decode(JSONValue.Obj(mapOf("revision" to JSONValue.Str("opaque"),"firstUnread" to JSONValue.Null)))!!
        assertFalse(read.unread);assertEquals("opaque",read.revision)
        val c=CodexSidebarController();val req=c.begin("a")
        c.apply(req,CodexSidebarState(initialized=true));assertNull(c.state.value.threads["unavailable"])
    }
}
