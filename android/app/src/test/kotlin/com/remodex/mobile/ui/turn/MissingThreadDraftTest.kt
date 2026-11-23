package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import java.nio.file.Files
import kotlin.test.*

class MissingThreadDraftTest {
    @Test fun savesPreparedPayloadAcrossReopenWithoutLeakingBetweenDevicesOrThreads() {
        val directory = Files.createTempDirectory("removed-draft").toFile()
        try {
            val key = com.remodex.mobile.core.persistence.MessageHistoryAesGcm.generateKey()
            val original = MissingThreadDraft("please inspect /uploaded/test.txt",
                images = listOf(CodexImageAttachment(thumbnailBase64JPEG = "thumb", payloadDataURL = "image")),
                skills = listOf(CodexTurnSkillMention("skill")), mentions = listOf(CodexTurnMention("file","/file")),
                plan = true, cwd = "/project", composerText = "please inspect",
                files = listOf(CodexFileAttachment(fileName = "test.txt", mimeType = "text/plain",
                    sizeBytes = 8, textContent = null, remotePath = "/uploaded/test.txt")))
            MissingThreadDraftStore(directory) { key }.save("device1","thread-A",original)
            assertFalse(directory.listFiles()!!.single().readBytes().decodeToString().contains(original.text))
            val reopened = MissingThreadDraftStore(directory) { key }
            assertNull(reopened.read("device2","thread-A"))
            assertNull(reopened.read("device1","thread-B"))
            assertEquals(original,reopened.read("device1","thread-A"))
            assertEquals("please inspect", reopened.read("device1", "thread-A")?.editableText)
            assertIs<TurnComposerAttachmentState.ReadyFile>(reopened.read("device1", "thread-A")?.composerAttachments()?.last()?.state)
            val changed = original.copy(composerText = "later edit")
            reopened.save("device1", "thread-A", changed)
            reopened.clearIfUnchanged("device1", "thread-A", original)
            assertEquals(changed, reopened.read("device1", "thread-A"))
            reopened.clearIfUnchanged("device1", "thread-A", changed)
            assertNull(reopened.read("device1", "thread-A"))
            reopened.save("device1","new",original.copy(transferred = true))
            reopened.clear("device1","thread-A")
            assertEquals(original.copy(transferred = true),reopened.read("device1","new"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun legacyDraftKeepsThePreparedFileReference() {
        val prepared = "check this\n\nAttached files:\nComplete file uploaded to computer: /uploads/report.txt"
        val draft = kotlinx.serialization.json.Json.decodeFromString<MissingThreadDraft>(
            "{\"text\":${kotlinx.serialization.json.JsonPrimitive(prepared)}}",
        )
        assertEquals(prepared, draft.editableText)
        assertTrue(draft.files.isEmpty())
    }

    @Test fun onlyExplicitMissingCanOfferANewChat() {
        fun rpc(message: String) = CodexServiceError.RpcFailure(RPCError(-32600,message))
        assertTrue(rpc("thread not found").isMissingSendTarget())
        assertFalse(rpc("thread not yet materialized").isMissingSendTarget())
        assertFalse(rpc("Not initialized").isMissingSendTarget())
        assertFalse(CodexServiceError.Disconnected.isMissingSendTarget())
    }
}
