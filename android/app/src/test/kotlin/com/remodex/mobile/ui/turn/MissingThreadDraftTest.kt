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
                plan = true, cwd = "/project")
            MissingThreadDraftStore(directory) { key }.save("device1","thread-A",original)
            assertFalse(directory.listFiles()!!.single().readBytes().decodeToString().contains(original.text))
            val reopened = MissingThreadDraftStore(directory) { key }
            assertNull(reopened.read("device2","thread-A"))
            assertNull(reopened.read("device1","thread-B"))
            assertEquals(original,reopened.read("device1","thread-A"))
            reopened.save("device1","new",original.copy(transferred = true))
            reopened.clear("device1","thread-A")
            assertEquals(original.copy(transferred = true),reopened.read("device1","new"))
        } finally { directory.deleteRecursively() }
    }

    @Test fun onlyExplicitMissingCanOfferANewChat() {
        fun rpc(message: String) = CodexServiceError.RpcFailure(RPCError(-32600,message))
        assertTrue(rpc("thread not found").isMissingSendTarget())
        assertFalse(rpc("thread not yet materialized").isMissingSendTarget())
        assertFalse(rpc("Not initialized").isMissingSendTarget())
        assertFalse(CodexServiceError.Disconnected.isMissingSendTarget())
    }
}
