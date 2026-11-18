package com.remodex.mobile.ui.turn

/** Serves the downloaded document at one synthetic HTTPS URL, without a network round trip. */
internal class InteractivePageRequestRouter(document: String) {
    private val documentBytes = document.toByteArray(Charsets.UTF_8)

    data class Response(val status: Int, val reason: String, val mime: String, val bytes: ByteArray)

    fun intercept(url: String, isMainFrame: Boolean, method: String): Response? {
        if (method == "GET" && isMainFrame && url == InteractivePagePolicy.URL) {
            return Response(200, "OK", "text/html", documentBytes)
        }
        if (method == "GET" && !isMainFrame && InteractivePagePolicy.allowedResource(url)) return null
        return Response(403, "Blocked", "text/plain", byteArrayOf())
    }
}
