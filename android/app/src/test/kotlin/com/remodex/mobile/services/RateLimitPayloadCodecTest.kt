package com.remodex.mobile.services

import com.remodex.mobile.core.model.accountWeeklyQuota
import com.remodex.mobile.core.model.CodexRateLimitBucket
import com.remodex.mobile.core.model.CodexRateLimitWindow
import com.remodex.mobile.core.model.JSONValue
import kotlin.test.assertEquals
import org.junit.Test

class RateLimitPayloadCodecTest {
    @Test fun accountWeekExcludesModelSpecificLimitsAndKeepsZeroDistinctFromUnknown() {
        val full = CodexRateLimitWindow(0, 10080)
        val spark = CodexRateLimitBucket("codex_bengalfox", secondary = full)
        val account = CodexRateLimitBucket("codex", secondary = full.copy(usedPercent = 100))
        assertEquals(0, accountWeeklyQuota(listOf(spark, account))?.remainingPercent)
        assertEquals(null, accountWeeklyQuota(listOf(spark)))
        assertEquals(null, accountWeeklyQuota(listOf(account.copy(secondary = full.copy(windowDurationMins = 300)))))
        assertEquals(100, accountWeeklyQuota(listOf(CodexRateLimitBucket("secondary", secondary = full)))?.remainingPercent)
    }

    @Test
    fun `decodes read response with rateLimitsByLimitId`() {
        val payload =
            mapOf(
                "rateLimitsByLimitId" to
                    JSONValue.Obj(
                        mapOf(
                            "codex_5h" to
                                JSONValue.Obj(
                                    mapOf(
                                        "limitId" to JSONValue.Str("codex_5h"),
                                        "limitName" to JSONValue.Str("5h limit"),
                                        "primary" to
                                            JSONValue.Obj(
                                                mapOf(
                                                    "usedPercent" to JSONValue.NumLong(25L),
                                                    "windowDurationMins" to JSONValue.NumLong(300L),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )
        val buckets = RateLimitPayloadCodec.decodeRateLimitBuckets(payload)
        assertEquals(1, buckets.size)
        assertEquals("codex_5h", buckets[0].limitId)
        assertEquals(25, buckets[0].primary?.clampedUsedPercent)
    }

    @Test
    fun `merges into visible rows like iOS updated notification`() {
        val obj =
            JSONValue.Obj(
                mapOf(
                    "rateLimits" to
                        JSONValue.Obj(
                            mapOf(
                                "limitId" to JSONValue.Str("codex"),
                                "limitName" to JSONValue.Str("Codex"),
                                "primary" to
                                    JSONValue.Obj(
                                        mapOf(
                                            "usedPercent" to JSONValue.NumLong(42L),
                                            "windowDurationMins" to JSONValue.NumLong(60L),
                                        ),
                                    ),
                            ),
                        ),
                ),
            )
        val m = obj.objectValue!!
        val buckets = RateLimitPayloadCodec.decodeRateLimitBuckets(m)
        assertEquals(1, buckets.size)
        val rows = CodexRateLimitBucket.visibleDisplayRows(buckets)
        assertEquals(1, rows.size)
        assertEquals(58, rows[0].window.remainingPercent)
    }

    @Test
    fun `merge updates existing limit by id`() {
        val existing =
            listOf(
                CodexRateLimitBucket(
                    limitId = "a",
                    limitName = "Old",
                    primary = CodexRateLimitWindow(usedPercent = 10, windowDurationMins = 60, resetsAt = null),
                    secondary = null,
                ),
            )
        val incoming =
            listOf(
                CodexRateLimitBucket(
                    limitId = "a",
                    limitName = null,
                    primary = CodexRateLimitWindow(usedPercent = 50, windowDurationMins = 60, resetsAt = null),
                    secondary = null,
                ),
            )
        val merged = RateLimitPayloadCodec.mergeRateLimitBuckets(existing, incoming)
        assertEquals(1, merged.size)
        assertEquals(50, merged[0].primary?.usedPercent)
        assertEquals("Old", merged[0].limitName)
    }

    @Test
    fun `decode uses stable fallback id when bridge omits limit id`() {
        val payload =
            mapOf(
                "rateLimitsByLimitId" to
                    JSONValue.Obj(
                        mapOf(
                            "" to
                                JSONValue.Obj(
                                    mapOf(
                                        "primary" to
                                            JSONValue.Obj(
                                                mapOf(
                                                    "usedPercent" to JSONValue.NumLong(12L),
                                                    "windowDurationMins" to JSONValue.NumLong(300L),
                                                ),
                                            ),
                                    ),
                                ),
                        ),
                    ),
            )

        val first = RateLimitPayloadCodec.decodeRateLimitBuckets(payload).single()
        val second = RateLimitPayloadCodec.decodeRateLimitBuckets(payload).single()

        assertEquals("unnamed-300-none", first.limitId)
        assertEquals(first.limitId, second.limitId)
    }
    @Test fun `rolling quota preserves bucket id so reset can decrease used percentage`() {
        val payload = mapOf("rateLimits" to JSONValue.Obj(mapOf("limitId" to JSONValue.Str("codex"),
            "primary" to JSONValue.Obj(mapOf("usedPercent" to JSONValue.NumLong(2), "windowDurationMins" to JSONValue.NumLong(300))))))
        val decoded = RateLimitPayloadCodec.decodeRateLimitBuckets(payload)
        assertEquals("codex", decoded.single().limitId)
        val previous = listOf(CodexRateLimitBucket("codex", primary = CodexRateLimitWindow(99, 300)))
        val merged = RateLimitPayloadCodec.mergeRateLimitBuckets(previous, decoded)
        assertEquals(1, merged.size)
        assertEquals(98, merged.single().primary!!.remainingPercent)
    }

    @Test fun `reset timestamps accept seconds milliseconds numeric strings and ISO`() {
        val instant = java.time.Instant.parse("2026-11-10T08:30:45Z")
        for (value in listOf(JSONValue.NumLong(instant.epochSecond), JSONValue.NumLong(instant.toEpochMilli()),
            JSONValue.Str(instant.epochSecond.toString()), JSONValue.Str(instant.toString()))) {
            val payload = mapOf("primary" to JSONValue.Obj(mapOf("usedPercent" to JSONValue.NumLong(71), "resetsAt" to value)))
            assertEquals(instant, RateLimitPayloadCodec.decodeRateLimitBuckets(payload).single().primary!!.resetsAt)
        }
    }

    @Test fun `missing percentage cannot become full remaining quota and malformed time retains cause`() {
        val missing = kotlin.test.assertFailsWith<com.remodex.mobile.core.error.CodexServiceError.InvalidResponse> {
            RateLimitPayloadCodec.decodeRateLimitBuckets(mapOf("primary" to JSONValue.Obj(emptyMap())))
        }
        kotlin.test.assertTrue(missing.message!!.contains("usedPercent"))
        val invalid = kotlin.test.assertFailsWith<com.remodex.mobile.core.error.CodexServiceError.InvalidResponse> {
            RateLimitPayloadCodec.decodeRateLimitBuckets(mapOf("primary" to JSONValue.Obj(mapOf("usedPercent" to JSONValue.NumLong(1),
                "resetsAt" to JSONValue.Str("not-a-date")))))
        }
        kotlin.test.assertNotNull(invalid.cause)
        kotlin.test.assertTrue(invalid.message!!.contains("resetsAt"))
    }

}
