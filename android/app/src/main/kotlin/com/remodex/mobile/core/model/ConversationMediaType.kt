package com.remodex.mobile.core.model

import java.net.URI
import java.net.URLDecoder

/** Explicit MIME is authoritative; filename fallback never inspects URL query parameters. */
internal object ConversationMediaType {
    private val videoExtensions = setOf("mp4", "m4v", "mov", "webm", "mkv", "avi", "3gp", "3g2", "ogv", "mpeg", "mpg", "m2v")

    fun isVideo(source: String, mimeType: String? = null): Boolean {
        val mime = mimeType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
        if (mime.startsWith("video/")) return true
        if (mime.startsWith("image/") || mime.startsWith("audio/")) return false
        if (source.trim().startsWith("data:video/", ignoreCase = true)) return true
        return filename(source).substringAfterLast('.', "").lowercase() in videoExtensions
    }

    fun filename(source: String): String {
        val raw = source.trim()
        if (raw.startsWith("data:", true)) return ""
        val path = if (raw.startsWith("https://", true) || raw.startsWith("http://", true)) {
            try { URI(raw).rawPath.orEmpty() } catch (_: java.net.URISyntaxException) { raw.substringBefore('?').substringBefore('#') }
        } else raw.substringBefore('?').substringBefore('#')
        val decoded = try { URLDecoder.decode(path.replace("+", "%2B"), "UTF-8") } catch (_: IllegalArgumentException) { path }
        return decoded.replace('\\', '/').substringAfterLast('/')
    }

    fun videoSource(attachment: CodexImageAttachment): String? =
        listOfNotNull(attachment.sourceURL, attachment.payloadDataURL).firstOrNull { isVideo(it, attachment.mimeType) }
}
