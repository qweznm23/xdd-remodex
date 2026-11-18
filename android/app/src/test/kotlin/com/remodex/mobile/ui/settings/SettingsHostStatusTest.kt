package com.remodex.mobile.ui.settings

import com.remodex.mobile.core.model.JSONValue
import org.junit.Assert.*
import org.junit.Test

class SettingsHostStatusTest {
    @Test fun decodesOnlyExplicitPublicFields() {
        val result = SettingsHostStatus.decode(JSONValue.Obj(mapOf(
            "status" to JSONValue.Str("authenticated"), "email" to JSONValue.Str(" user@example.test "),
            "bridgeVersion" to JSONValue.Str("1.2.3"), "hostPlatform" to JSONValue.Str("linux"),
            "authToken" to JSONValue.Str("secret-never-display"), "tokens" to JSONValue.Obj(mapOf("access_token" to JSONValue.Str("hidden")))
        )))
        assertEquals("user@example.test", result.email)
        assertEquals("1.2.3", result.bridgeVersion)
        assertEquals("linux", result.hostPlatform)
        assertNull(result.plan)
        assertFalse(result.toString().contains("secret-never-display"))
        assertFalse(result.toString().contains("hidden"))
    }
    @Test fun missingFieldsDoNotInventAccountOrVersion() {
        val status = SettingsHostStatus.decode(JSONValue.Obj(emptyMap()))
        assertNull(status.status); assertNull(status.bridgeVersion); assertNull(status.email)
    }
    @Test fun malformedResponsePreservesFieldAndOperationWithoutEchoingPayload() {
        val error = runCatching { SettingsHostStatus.decode(JSONValue.Obj(mapOf("status" to JSONValue.Obj(mapOf("token" to JSONValue.Str("hidden")))))) }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertEquals("account/status/read: status must be a string or null", error?.message)
        assertFalse(error.toString().contains("hidden"))
        assertTrue(runCatching { SettingsHostStatus.decode(JSONValue.Str("invalid")) }.exceptionOrNull() is IllegalArgumentException)
    }
}
