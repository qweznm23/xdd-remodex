package com.remodex.mobile.update

import kotlin.test.Test
import kotlin.test.assertEquals

class AppUpdateSourceTest {
    @Test fun followsRelaySchemeHostAndPortWithoutPairingSecrets() {
        assertEquals("http://183.221.20.26:10004/updates/update.json", updateUrlFromRelay("ws://183.221.20.26:10004/relay/session?token=secret"))
        assertEquals("https://relay.example/updates/update.json", updateUrlFromRelay("wss://relay.example/relay/session"))
        assertEquals("http://192.168.1.10:10004/updates/update.json", updateUrlFromRelay("ws://old.example:10004/relay", "192.168.1.10"))
        assertEquals("", updateUrlFromRelay(null))
    }
}
