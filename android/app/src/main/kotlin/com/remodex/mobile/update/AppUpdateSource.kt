package com.remodex.mobile.update

import com.remodex.mobile.pairing.applyRelayHostOverride
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun updateUrlFromRelay(relay: String?, hostOverride: String = ""): String {
    if (relay.isNullOrBlank()) return ""
    val effective = applyRelayHostOverride(relay, hostOverride)
    val http = when {
        effective.startsWith("wss://", true) -> "https://" + effective.substring(6)
        effective.startsWith("ws://", true) -> "http://" + effective.substring(5)
        else -> effective
    }.toHttpUrlOrNull() ?: return ""
    return http.newBuilder().username("").password("").encodedPath("/updates/update.json")
        .query(null).fragment(null).build().toString()
}
