package com.remodex.mobile.ui.turn

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.*
import java.net.URI

internal data class InteractivePageReference(val path: String, val title: String) {
    companion object {
        private val marker = Regex("""^\s*(?:\uE200visualize\uE202(\{.*\})\uE201|visualize\s+(\{.*\}))\s*$""")

        fun parse(line: String): InteractivePageReference? {
            val match = marker.matchEntire(line) ?: return null
            val json = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val obj = try { Json.parseToJsonElement(json) as? JsonObject }
            catch (_: SerializationException) { null } ?: return null // Incomplete live markers remain visible prose.
            val path = (obj["path"] as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
            if (!path.startsWith('/') || path.startsWith("//") || path.any { it.code < 32 } ||
                path.split('/').any { it == ".." } || !path.endsWith(".html", true)) return null
            val title = (obj["title"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
                ?.takeIf { it.isNotEmpty() } ?: path.substringAfterLast('/').removeSuffix(".html")
            return InteractivePageReference(path, title)
        }
    }
}

/** Static libraries only. No bridge endpoints, local device files, or host actions. */
internal object InteractivePagePolicy {
    const val URL = "https://remodex-preview.invalid/page"
    private val hosts = setOf("cdnjs.cloudflare.com", "esm.sh", "cdn.jsdelivr.net", "unpkg.com",
        "fonts.googleapis.com", "fonts.gstatic.com", "fonts.bunny.net")
    fun allowedResource(raw: String): Boolean {
        val uri = try { URI(raw) } catch (_: java.net.URISyntaxException) { return false }
        if (uri.scheme == "data") return true
        return uri.scheme == "https" && uri.userInfo == null && uri.port in setOf(-1, 443) &&
            (uri.host in hosts || (uri.host == "persistent.oaistatic.com" && uri.path.startsWith("/plugins/visualize/")))
    }

    val contentSecurityPolicy: String = run {
        val cdns = hosts.joinToString(" ") { "https://$it" } + " https://persistent.oaistatic.com/plugins/visualize/"
        "default-src 'none'; script-src 'unsafe-inline' $cdns; style-src 'unsafe-inline' $cdns; img-src data: blob: $cdns; font-src data: $cdns; media-src data: blob:; connect-src 'none'; frame-src 'none'; object-src 'none'; base-uri 'none'; form-action 'none'"
    }

    fun document(fragment: String): String {
        // Policy precedes user HTML and cannot be relaxed by a later meta tag.
        return """<!doctype html><html><head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <meta http-equiv="Content-Security-Policy" content="$contentSecurityPolicy">
            <style>:root{color-scheme:light;--background:#fff;--foreground:#202124;--card:#fff;--card-foreground:#202124;--primary:#215ea6;--primary-foreground:#fff;--secondary:#eef1f5;--secondary-foreground:#202124;--muted:#f3f4f6;--muted-foreground:#626873;--accent:#e9f0fb;--accent-foreground:#202124;--border:#dedfe3;--input:#dedfe3;--ring:#215ea6;--radius:12px;--font-sans:system-ui,sans-serif;--font-mono:monospace;--viz-series-1:#477dbb;--viz-series-2:#bb7750;--viz-series-3:#5e997d;--viz-series-4:#947bc3;--viz-series-5:#b79a49;--viz-series-6:#b56b87}*{box-sizing:border-box}body{margin:0;padding:16px;font:15px/1.5 system-ui,sans-serif;background:var(--background);color:var(--foreground)}button,input,select,textarea{font:inherit}button{cursor:pointer}img,svg,canvas{max-width:100%}.muted{color:var(--muted-foreground)}.card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:16px}</style>
            <script>window.openai={widgetState:null,setWidgetState:function(s){this.widgetState=s;}};</script>
            </head><body>""".trimIndent() + fragment + """
            <script id="remodex-preview-icons" async src="https://unpkg.com/lucide@1.17.0/dist/umd/lucide.js"></script>
            <script>document.getElementById('remodex-preview-icons').addEventListener('load',function(){globalThis.lucide?.createIcons({attrs:{width:16,height:16}});},{once:true});</script>
            </body></html>""".trimIndent()
    }
}
