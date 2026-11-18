package com.remodex.mobile.core.transport

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

private val supportedRelaySchemes = setOf("ws", "wss", "http", "https")

data class RelayUrlValidation(
    val httpUrl: HttpUrl,
    val websocketUrl: String,
    val cleartext: Boolean,
)

fun validateRelayUrl(
    rawRelayUrl: String,
    requireWebSocketScheme: Boolean = false,
): RelayUrlValidation? {
    val trimmed = rawRelayUrl.trim().trimEnd('/')
    if (trimmed.isEmpty()) return null
    val scheme = trimmed.substringBefore("://", missingDelimiterValue = "").lowercase()
    if (scheme !in supportedRelaySchemes) return null
    if (requireWebSocketScheme && scheme !in setOf("ws", "wss")) return null

    val httpish =
        when (scheme) {
            "ws" -> "http://${trimmed.substring(5)}"
            "wss" -> "https://${trimmed.substring(6)}"
            else -> trimmed
        }
    val httpUrl = httpish.toHttpUrlOrNull() ?: return null
    if (httpUrl.username.isNotEmpty() || httpUrl.password.isNotEmpty()) return null
    val cleartext = scheme == "ws" || scheme == "http"
    // Self-hosted build: public ws:// routes are allowed; pairing authentication remains required.

    val websocketUrl =
        when (scheme) {
            "https" -> httpUrl.toString().replaceFirst("https://", "wss://", ignoreCase = true)
            "http" -> httpUrl.toString().replaceFirst("http://", "ws://", ignoreCase = true)
            else -> trimmed
        }
    return RelayUrlValidation(httpUrl = httpUrl, websocketUrl = websocketUrl, cleartext = cleartext)
}
