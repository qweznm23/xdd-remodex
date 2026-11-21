package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageRole

private const val Open = "<oai-mem-citation>"
private const val Close = "</oai-mem-citation>"

internal fun CodexMessage.visibleAssistantText(): String =
    if (role == CodexMessageRole.assistant) filterMemoryCitation(text, isStreaming) else text

internal fun filterMemoryCitation(text: String, streaming: Boolean): String {
    if (!text.contains('<') || (!streaming && !text.contains(Open))) return text
    if (!text.contains(Open) && !Open.startsWith(text.substringAfterLast('\n').trim())) return text
    var inside = false
    var fence = ""
    var backticks = 0
    var changed = false
    val lines = mutableListOf<String>()
    for (line in text.lineSequence()) {
        val trimmed = line.trimStart()
        if (inside) {
            if (Close in line) {
                inside = false
                line.substringAfter(Close).takeIf { it.isNotBlank() }?.let(lines::add)
            }
            continue
        }
        val first = trimmed.firstOrNull()
        val marker = if (first == '`' || first == '~') trimmed.takeWhile { it == first } else ""
        if (fence.isNotEmpty()) {
            if (marker.startsWith(fence) && trimmed.drop(marker.length).isBlank()) fence = ""
        } else if (backticks == 0 && !line.startsWith("    ") && !line.startsWith("\t") && marker.length >= 3) {
            fence = marker
        } else if (backticks == 0 && !line.startsWith("    ") && !line.startsWith("\t") && trimmed.startsWith(Open)) {
            changed = true
            inside = Close !in trimmed
            if (!inside) trimmed.substringAfter(Close).takeIf { it.isNotBlank() }?.let(lines::add)
            continue
        } else {
            if (streaming && backticks == 0 && trimmed.startsWith("<") && Open.startsWith(trimmed) && text.endsWith(line)
                && line.length - trimmed.length < 4 && !line.startsWith("\t")) {
                changed = true
                continue
            }
            var i = 0
            while (i < line.length) {
                when (line[i]) {
                    '\\' -> i += 2
                    '`' -> {
                        val start = i
                        while (i < line.length && line[i] == '`') i++
                        val width = i - start
                        if (backticks == 0) backticks = width else if (width == backticks) backticks = 0
                    }
                    else -> i++
                }
            }
        }
        lines.add(line)
    }
    return if (changed) lines.joinToString("\n").trimEnd() else text
}
