package com.remodex.mobile.core.model

import com.remodex.mobile.core.persistence.MacScopedSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ThreadRuntimeStatusTest {
    private fun thread(status: String) = CodexThread.fromJsonObject(Json.parseToJsonElement(
        """{"id":"unopened","status":$status}""",
    ).jsonObject)

    @Test fun decodesActualServerStatesIncludingActiveWithoutTurnId() {
        assertEquals(ThreadRuntimeStatus.Active, thread("""{"type":"active","activeFlags":[]}""").runtimeStatus)
        assertEquals(ThreadRuntimeStatus.Idle, thread("""{"type":"idle"}""").runtimeStatus)
        assertEquals(ThreadRuntimeStatus.SystemError, thread("""{"type":"systemError"}""").runtimeStatus)
        assertEquals(ThreadRuntimeStatus.NotLoaded, thread("""{"type":"notLoaded"}""").runtimeStatus)
    }

    @Test fun unknownOrMissingStatusIsNotIdle() {
        for (value in listOf("null", "{}", """{"type":"future-status"}""", "\"active\"")) {
            assertNull(thread(value).runtimeStatus)
        }
    }

    @Test fun liveStatusIsNotRestoredAsFactFromDiskCache() {
        val active = thread("""{"type":"active","activeFlags":[]}""")
        val restored = MacScopedSessionStore.decodeCachedThreadSnapshot(
            MacScopedSessionStore.encodeCachedThreadSnapshot(listOf(active)),
        ).single()
        assertNull(restored.runtimeStatus)
    }
}
