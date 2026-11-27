package com.remodex.mobile.services

import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.data.CodexRepository

internal data class ResetCardReply(val status: String, val account: String, val accountId: String,
    val title: String, val remaining: Double, val ticket: String?)

internal suspend fun CodexRepository.resetCard(profile: String, revision: String?, creditId: String, ticket: String?): ResetCardReply {
    val params = buildMap {
        put("profile", JSONValue.Str(profile))
        put("creditId", JSONValue.Str(creditId))
        revision?.let { put("revision", JSONValue.Str(it)) }
        ticket?.let { put("ticket", JSONValue.Str(it)) }
    }
    val rpc = sendRequest("account/profiles/reset-card", JSONValue.Obj(params))
    rpc.error?.let { throw CodexServiceError.RpcFailure(it) }
    val result = rpc.result?.objectValue ?: throw CodexServiceError.InvalidResponse("Missing reset-card response")
    fun field(key: String) = result[key]?.stringValue ?: throw CodexServiceError.InvalidResponse("Missing reset-card field: $key")
    val status = field("status")
    val next = if (status in setOf("confirm", "confirmAgain")) field("ticket") else null
    return ResetCardReply(status, field("account"), field("accountId"), field("title"),
        result["remaining"]?.doubleValue ?: throw CodexServiceError.InvalidResponse("Missing remaining quota"), next)
}
