package com.remodex.mobile.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelayUrlPolicyTest {
    @Test
    fun localCleartextRelay_isAllowed() {
        val validation = assertNotNull(validateRelayUrl("ws://192.168.1.10:9000/relay"))
        assertTrue(validation.cleartext)
        assertEquals("192.168.1.10", validation.httpUrl.host)
    }

    @Test
    fun tailscaleCleartextRelay_isAllowed() {
        val ipValidation = assertNotNull(validateRelayUrl("ws://100.100.100.100:9000/relay"))
        assertTrue(ipValidation.cleartext)
        assertEquals("100.100.100.100", ipValidation.httpUrl.host)

        val magicDnsValidation = assertNotNull(validateRelayUrl("ws://macbook.tailnet-name.ts.net:9000/relay"))
        assertTrue(magicDnsValidation.cleartext)
        assertEquals("macbook.tailnet-name.ts.net", magicDnsValidation.httpUrl.host)
    }

    @Test
    fun publicCleartextRelay_preservesHostPortAndPath() {
        for (url in listOf("ws://183.221.20.26:10004/relay", "ws://relay.example.com:10004/relay")) {
            val validation = assertNotNull(validateRelayUrl(url, requireWebSocketScheme = true))
            assertTrue(validation.cleartext)
            assertEquals(url, validation.websocketUrl)
            assertEquals(10004, validation.httpUrl.port)
            assertEquals("/relay", validation.httpUrl.encodedPath)
        }
    }

    @Test
    fun pairingStillRequiresWebSocketSchemeAndNoCredentials() {
        assertNull(validateRelayUrl("http://183.221.20.26:10004/relay", requireWebSocketScheme = true))
        assertNull(validateRelayUrl("ws://user:pass@183.221.20.26:10004/relay"))
        assertNull(validateRelayUrl("ftp://183.221.20.26/relay"))
    }

    @Test
    fun publicSecureRelay_isAllowed() {
        val validation = assertNotNull(validateRelayUrl("wss://relay.example.com/relay"))
        assertEquals("https", validation.httpUrl.scheme)
        assertEquals("relay.example.com", validation.httpUrl.host)
    }

    @Test
    fun relayUrlWithUserInfo_isRejected() {
        assertNull(validateRelayUrl("wss://user:pass@relay.example.com/relay"))
    }
}
