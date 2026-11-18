package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.ui.turn.formatAccountUsageTime
import java.time.Instant
import java.time.ZoneId
import kotlin.test.*

class AccountUsageDetailsCodecTest {
    private fun obj(vararg fields: Pair<String, JSONValue>) = JSONValue.Obj(mapOf(*fields))
    private fun count(n: Long, details: JSONValue = JSONValue.Null) = mapOf("rateLimitResetCredits" to obj("availableCount" to JSONValue.NumLong(n), "credits" to details))

    @Test fun distinguishesUnknownZeroCountOnlyAndPartialCardDetails() {
        assertNull(AccountUsageDetailsCodec.decode(emptyMap()).resetCredits)
        assertNull(AccountUsageDetailsCodec.decode(mapOf("rateLimitResetCredits" to JSONValue.Null)).resetCredits)
        val zero = AccountUsageDetailsCodec.decode(count(0, JSONValue.Arr(emptyList()))).resetCredits!!
        assertEquals(0L, zero.availableCount)
        assertEquals(emptyList(), zero.credits)
        val countOnly = AccountUsageDetailsCodec.decode(count(3)).resetCredits!!
        assertEquals(3L, countOnly.availableCount)
        assertNull(countOnly.credits)
        val partial = AccountUsageDetailsCodec.decode(count(3, JSONValue.Arr(listOf(obj(
            "id" to JSONValue.Str("card"), "status" to JSONValue.Str("available"),
            "title" to JSONValue.Str("Quota reset"), "description" to JSONValue.Str("Reset included usage"),
            "expiresAt" to JSONValue.NumLong(1796069563),
        ))))).resetCredits!!
        assertEquals(3L, partial.availableCount)
        assertEquals(1, partial.credits!!.size)
        assertEquals(Instant.ofEpochSecond(1796069563), partial.credits.single().expiresAt)
        assertEquals("available", partial.credits.single().status)
    }

    @Test fun missingExpiryIsNotConfusedWithANonExpiringCard() {
        val cards = listOf(obj("id" to JSONValue.Str("unknown")), obj("id" to JSONValue.Str("never"), "expiresAt" to JSONValue.Null))
        val decoded = AccountUsageDetailsCodec.decode(count(2, JSONValue.Arr(cards))).resetCredits!!.credits!!
        assertFalse(decoded[0].expiryProvided)
        assertTrue(decoded[1].expiryProvided)
        assertTrue(decoded.all { it.expiresAt == null })
    }

    @Test fun balanceIsExactAndUnlimitedDoesNotBecomeZero() {
        val credits = obj("hasCredits" to JSONValue.Bool(true), "unlimited" to JSONValue.Bool(false), "balance" to JSONValue.Str("123456789.123456789"))
        val bucket = obj("limitName" to JSONValue.Str("Codex"), "credits" to credits)
        val payload = mapOf("rateLimitsByLimitId" to obj("codex" to bucket), "rateLimits" to bucket)
        val result = AccountUsageDetailsCodec.decode(payload)
        assertEquals(1, result.balances.size)
        assertEquals("123456789.123456789", result.balances.single().balance)
        assertNull(result.lastSuccessfulReadAt, "Parsing alone must not invent a successful query time")
        val unlimited = AccountUsageDetailsCodec.decode(mapOf("rateLimits" to obj("credits" to obj("hasCredits" to JSONValue.Bool(true), "unlimited" to JSONValue.Bool(true), "balance" to JSONValue.Null))))
        assertTrue(unlimited.balances.single().unlimited)
        assertNull(unlimited.balances.single().balance)
    }

    @Test fun supportsEmptyMultiBucketFallbackAndWrappedLegacyPayload() {
        val direct = obj("credits" to obj("hasCredits" to JSONValue.Bool(false), "unlimited" to JSONValue.Bool(false), "balance" to JSONValue.Str("0")))
        val payload = mapOf("rateLimitsByLimitId" to obj(), "rateLimits" to direct)
        assertEquals("0", AccountUsageDetailsCodec.decode(mapOf("result" to JSONValue.Obj(payload))).balances.single().balance)
    }

    @Test fun malformedDataHasFieldAndCauseAndNeverTurnsIntoZeroCards() {
        val failure = assertFailsWith<CodexServiceError.InvalidResponse> { AccountUsageDetailsCodec.decode(count(-1)) }
        assertTrue(failure.message!!.contains("availableCount"))
        assertTrue(failure.stackTrace.isNotEmpty())
        val dateFailure = assertFailsWith<CodexServiceError.InvalidResponse> {
            AccountUsageDetailsCodec.decode(count(1, JSONValue.Arr(listOf(obj("id" to JSONValue.Str("card"), "expiresAt" to JSONValue.NumLong(Long.MAX_VALUE))))))
        }
        assertNotNull(dateFailure.cause)
        assertTrue(dateFailure.message!!.contains("expiresAt"))
    }

    @Test fun localTimeIncludesDateSecondsAndZone() {
        val text = formatAccountUsageTime(Instant.parse("2026-11-10T08:30:45Z"), ZoneId.of("Asia/Shanghai"))
        assertTrue(text.startsWith("2026-11-10 16:30:45 "), text)
    }
}
