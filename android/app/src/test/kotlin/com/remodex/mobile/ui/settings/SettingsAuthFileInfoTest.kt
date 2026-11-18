package com.remodex.mobile.ui.settings

import com.remodex.mobile.core.model.JSONValue
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SettingsAuthFileInfoTest {
    @Test fun readsWhitelistAndDoesNotRetainTokens() {
        val info = SettingsAuthFileInfo.decode(JSONValue.Obj(mapOf(
            "status" to JSONValue.Str("present"), "path" to JSONValue.Str("/home/account/auth.json"),
            "resolvedPath" to JSONValue.Str("/home/account/active.json"), "sizeBytes" to JSONValue.NumLong(4321),
            "lastRefresh" to JSONValue.Str("2026-11-07T03:53:09Z"), "authMode" to JSONValue.Str("chatgpt"),
            "tokens" to JSONValue.Str("NEVER_DISPLAY"), "OPENAI_API_KEY" to JSONValue.Str("SECRET")
        )))!!
        assertEquals("/home/account/auth.json", info.path)
        assertEquals(4321L, info.sizeBytes)
        assertEquals(Instant.parse("2026-11-07T03:53:09Z"), info.lastRefresh)
        assertFalse(info.toString().contains("NEVER_DISPLAY")); assertFalse(info.toString().contains("SECRET"))
    }
    @Test fun oldBridgeAndExplicitStorageModesRemainDistinct() {
        assertNull(SettingsAuthFileInfo.decode(null))
        val info = SettingsAuthFileInfo.decode(JSONValue.Obj(mapOf("status" to JSONValue.Str("non_file"), "storage" to JSONValue.Str("keyring"))))!!
        assertEquals("non_file", info.status); assertEquals("keyring", info.storage); assertNull(info.path)
    }
    @Test fun preservesRemoteFailureStageCodeAndType() {
        val info = SettingsAuthFileInfo.decode(JSONValue.Obj(mapOf("status" to JSONValue.Str("error"), "error" to JSONValue.Obj(mapOf(
            "stage" to JSONValue.Str("parse"), "code" to JSONValue.Str("INVALID_AUTH_JSON"), "type" to JSONValue.Str("SyntaxError"), "message" to JSONValue.Str("Contents redacted")
        )))))!!
        assertEquals("stage=parse\ncode=INVALID_AUTH_JSON\ntype=SyntaxError\nmessage=Contents redacted", info.errorDetails)
    }
    @Test fun rejectsBadDatesWithOriginalCauseInsteadOfShowingUnknownSuccess() {
        val error = runCatching { SettingsAuthFileInfo.decode(JSONValue.Obj(mapOf("status" to JSONValue.Str("present"), "modifiedAt" to JSONValue.Str("not-a-date")))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException); assertTrue(error?.cause is java.time.format.DateTimeParseException)
        assertTrue(error?.message?.contains("modifiedAt") == true)
    }
}
