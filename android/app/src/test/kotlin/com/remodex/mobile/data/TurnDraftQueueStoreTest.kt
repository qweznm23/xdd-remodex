package com.remodex.mobile.data

import com.remodex.mobile.core.model.*
import com.remodex.mobile.services.QueueOutbox
import com.remodex.mobile.services.RemoteTurnQueue
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.*

class TurnDraftQueueStoreTest {
    private val dir = Files.createTempDirectory("host-queue").toFile()
    private val key = ByteArray(32) { 7 }
    private var device = "host-a"
    private var online = false
    private val calls = mutableListOf<String>()
    private val native = mutableMapOf<String, JSONValue>()
    private var failAdd = false
    private var failSteer = false

    private fun service() = RemoteTurnQueue(QueueOutbox({dir}, {key}), { device }, { online }, { 1L }, { "thread" }, { method, body, _, _ ->
        calls += method
        val p = body.map
        val result = when (method) {
            "remodex/queue/add" -> {
                val id = "host-" + p["clientUserMessageId"]!!.stringValue
                native[id] = JSONValue.Obj(p + ("id" to JSONValue.Str(id)))
                if (failAdd) throw java.net.SocketTimeoutException("lost reply")
                mapOf("queuedSubmissionId" to JSONValue.Str(id))
            }
            "thread/queue/list" -> mapOf("data" to JSONValue.Arr(native.values.toList()))
            "thread/queue/delete" -> mapOf("deleted" to JSONValue.Bool(native.remove(p["queuedSubmissionId"]?.stringValue) != null))
            "turn/steer" -> {
                if (failSteer) throw java.net.SocketTimeoutException("steer outcome unknown")
                emptyMap()
            }
            else -> error("Unexpected method $method")
        }
        RPCMessage.success(JSONValue.NumLong(1), JSONValue.Obj(result))
    }, { _, _ -> })
    private val draft = QueuedTurnDraft("id", "queued text", listOf(CodexImageAttachment(thumbnailBase64JPEG="", remotePath="/upload/p.png")),
        listOf(CodexTurnSkillMention("name","name","/skill")), listOf(CodexTurnMention("A","/a")), null, "editor text",
        listOf(CodexFileAttachment(fileName="file", mimeType="text/plain",sizeBytes=1,textContent="x",remotePath="/file")))

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    @Test fun offlineSurvivesProcessDeathAndOnlyFlushesOnItsOwnDevice() = runTest {
        service().enqueue(device, "thread", draft)
        assertTrue(dir.listFiles()!!.none { it.readText().contains("editor text") })
        val restored = service()
        device = "host-b"
        online = true
        restored.sync()
        assertFalse("remodex/queue/add" in calls)
        device = "host-a"
        restored.sync()
        val preview = restored.previews.value["thread"]!!.single()
        assertFalse(preview.waiting)
        assertEquals(2, preview.attachmentCount)
        assertEquals("editor text", preview.text)
        assertEquals(draft, restored.take(device,"thread",preview.id))
        assertTrue(native.isEmpty())
    }

    @Test fun lostReplyRetriesStableIdAndCompletedRowsDoNotReappear() = runTest {
        online = true
        val app = service()
        app.enqueue(device,"thread",draft)
        failAdd = true
        app.sync()
        assertTrue(app.previews.value["thread"]!!.single().waiting)
        assertContains(app.errors.value["thread"].orEmpty(),"lost reply")
        failAdd = false
        val next = service()
        next.sync()
        assertEquals(1, native.size)
        native.clear()
        next.sync()
        assertTrue(next.depths.value.isEmpty())
        assertEquals(2,calls.count { it == "remodex/queue/add" })
    }

    @Test fun offlineWithdrawalNeverCreatesARemoteTurn() = runTest {
        val app = service()
        app.enqueue(device,"thread",draft)
        assertEquals(draft,app.take(device,"thread",draft.id))
        assertTrue(calls.isEmpty())
    }

    @Test fun unreadableDeviceQueueDoesNotDisplayPreviousDevicesMessages() = runTest {
        val app = service()
        device = "host-b"
        app.enqueue(device, "thread", draft)
        val corrupt = dir.listFiles()!!.single()
        device = "host-a"
        app.enqueue(device, "thread", draft)
        corrupt.writeBytes(byteArrayOf(0))
        device = "host-b"
        assertFails { app.sync() }
        assertTrue(app.previews.value.isEmpty())
        assertTrue(app.depths.value.isEmpty())
    }

    @Test fun failedSteerLeavesRecoverableContentWithoutAutomaticResubmission() = runTest {
        val app = service()
        online = true
        app.enqueue(device,"thread",draft)
        app.sync()
        failSteer = true
        assertFailsWith<java.net.SocketTimeoutException> { app.steer(device,"thread","host-id","active") }
        val next = service()
        next.sync()
        val recovery = next.previews.value["thread"]!!.single()
        assertTrue(recovery.recovery)
        assertTrue(native.isEmpty())
        assertEquals(draft.files, next.take(device,"thread",recovery.id)?.files)
        assertEquals(1,calls.count { it == "remodex/queue/add" })
    }
}
