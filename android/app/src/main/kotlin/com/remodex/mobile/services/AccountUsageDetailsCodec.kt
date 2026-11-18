package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.*
import java.time.Instant

/** Decode read-only account details. Null/unavailable is deliberately different from zero. */
internal object AccountUsageDetailsCodec {
    fun decode(payload: Map<String, JSONValue>): AccountUsageDetails {
        val p = payload["result"]?.objectValue ?: payload
        val byId = (p["rateLimitsByLimitId"] ?: p["rate_limits_by_limit_id"])?.objectValue
        val buckets = if (!byId.isNullOrEmpty()) byId.entries.map { (id, value) -> id to requireObject(value, "rateLimitsByLimitId.$id") }
            else listOfNotNull(((p["rateLimits"] ?: p["rate_limits"])?.objectValue
                ?: p.takeIf { it.containsKey("primary") || it.containsKey("secondary") || it.containsKey("credits") })
                ?.let { (it["limitId"]?.stringValue ?: "account") to it })
        val balances = buckets.mapNotNull { (id, bucket) ->
            val value = bucket["credits"]
            if (value == null || value == JSONValue.Null) return@mapNotNull null
            val credits = requireObject(value, "credits")
            AccountCreditBalance(id, bucket["limitName"]?.stringValue ?: id,
                credits["hasCredits"]?.boolValue ?: invalid("credits.hasCredits"),
                credits["unlimited"]?.boolValue ?: invalid("credits.unlimited"),
                optionalString(credits, "balance"))
        }
        val reset = p["rateLimitResetCredits"] ?: p["rate_limit_reset_credits"]
        val cards = if (reset == null || reset == JSONValue.Null) null else {
            val o = requireObject(reset, "rateLimitResetCredits")
            val count = o["availableCount"]?.longValue ?: invalid("rateLimitResetCredits.availableCount")
            if (count < 0) invalid("rateLimitResetCredits.availableCount must be nonnegative")
            val details = o["credits"]
            AccountResetCredits(count, if (details == null || details == JSONValue.Null) null else {
                val entries = details.arrayValue ?: invalid("rateLimitResetCredits.credits")
                entries.mapIndexed { index, value ->
                    val c = requireObject(value, "rateLimitResetCredits.credits[$index]")
                    AccountResetCredit(
                        c["id"]?.stringValue ?: invalid("reset credit id"), optionalString(c, "title"),
                        optionalString(c, "description"), c["status"]?.stringValue ?: "unknown",
                        optionalInstant(c["expiresAt"], "reset credit expiresAt"), c.containsKey("expiresAt"),
                    )
                }
            })
        }
        return AccountUsageDetails(balances = balances, resetCredits = cards)
    }

    private fun requireObject(value: JSONValue, field: String): Map<String, JSONValue> = value.objectValue ?: invalid(field)
    private fun optionalString(o: Map<String, JSONValue>, key: String): String? {
        val value = o[key]
        return if (value == null || value == JSONValue.Null) null else value.stringValue ?: invalid(key)
    }
    private fun optionalInstant(value: JSONValue?, field: String): Instant? {
        if (value == null || value == JSONValue.Null) return null
        val seconds = value.longValue ?: invalid(field)
        return try { Instant.ofEpochSecond(seconds) }
        catch (failure: Exception) { throw CodexServiceError.InvalidResponse("Invalid account usage field: $field").apply { initCause(failure) } }
    }
    private fun invalid(field: String): Nothing = throw CodexServiceError.InvalidResponse("Invalid account usage field: $field")
}
