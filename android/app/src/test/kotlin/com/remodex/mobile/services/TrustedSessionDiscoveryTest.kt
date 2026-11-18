package com.remodex.mobile.services

import com.remodex.mobile.core.model.CodexTrustedMacRecord
import com.remodex.mobile.core.model.CodexTrustedSessionResolveError
import com.remodex.mobile.core.model.CodexTrustedSessionResolveResponse
import com.remodex.mobile.core.persistence.RelaySessionSnapshot
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class TrustedSessionDiscoveryTest {
    private val trusted = CodexTrustedMacRecord("computer", "saved-key", Instant.EPOCH)
    private val response = CodexTrustedSessionResolveResponse(true, "computer", "saved-key", sessionId = "new-room")
    private fun validate(value: CodexTrustedSessionResolveResponse) =
        validateResolvedTrustedSession(value, trusted, "phone", "phone-key", "nonce", System.currentTimeMillis())

    @Test fun bundledRelayResponseDiscoversRoomWithoutInventedSignatureFields() {
        val decoded = Json.decodeFromString<CodexTrustedSessionResolveResponse>(
            """{"ok":true,"macDeviceId":"computer","macIdentityPublicKey":"saved-key","displayName":null,"sessionId":"new-room"}""",
        )
        validate(decoded)
        assertEquals("new-room", decoded.sessionId)
    }

    @Test fun discoveryCannotReplacePairedIdentity() {
        assertFailsWith<CodexTrustedSessionResolveError.InvalidResponse> { validate(response.copy(macDeviceId = "other")) }
        assertFailsWith<CodexTrustedSessionResolveError.InvalidResponse> { validate(response.copy(macIdentityPublicKey = "other-key")) }
    }

    @Test fun emptyRoomIsRejected() {
        assertFailsWith<CodexTrustedSessionResolveError.InvalidResponse> { validate(response.copy(sessionId = " ")) }
    }

    @Test fun partialSignedResponseCannotDowngradeToDiscovery() {
        assertFailsWith<CodexTrustedSessionResolveError.InvalidResponse> { validate(response.copy(responseTimestamp = System.currentTimeMillis())) }
        assertFailsWith<CodexTrustedSessionResolveError.InvalidResponse> { validate(response.copy(signature = "signature")) }
    }

    @Test fun expiredSignedResponseRemainsRejected() {
        assertFailsWith<CodexTrustedSessionResolveError.InvalidResponse> { validate(response.copy(responseTimestamp = 1, signature = "signature")) }
    }

    @Test fun wrappedNetworkFailureRetainsOriginalDiagnostics() {
        val source = java.io.IOException("reset", IllegalStateException("socket cause"))
        source.addSuppressed(IllegalArgumentException("cleanup"))
        val error = CodexTrustedSessionResolveError.Network("Unable to resolve", source)
        assertSame(source, error.cause)
        assertEquals("socket cause", error.cause?.cause?.message)
        assertEquals("cleanup", error.cause?.suppressed?.single()?.message)
    }

    @Test fun restartedBridgeResetsReplayCursorButSameSessionRetainsIt() {
        val previous = RelaySessionSnapshot(relaySessionId = "old-room", relayMacDeviceId = "computer", relayLastAppliedBridgeOutboundSeq = "900")
        assertEquals("0", resolvedTrustedRelaySnapshot(previous, response, "ws://relay/relay").relayLastAppliedBridgeOutboundSeq)
        assertEquals("900", resolvedTrustedRelaySnapshot(previous, response.copy(sessionId = "old-room"), "ws://relay/relay").relayLastAppliedBridgeOutboundSeq)
    }
}
