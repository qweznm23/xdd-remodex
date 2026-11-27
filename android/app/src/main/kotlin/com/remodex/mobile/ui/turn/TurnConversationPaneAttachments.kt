package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexFileAttachment
import com.remodex.mobile.core.model.CodexImageAttachment

internal fun List<TurnComposerAttachment>.withLoadingAttachment(attachmentId: String): List<TurnComposerAttachment> =
    this + TurnComposerAttachment(attachmentId, TurnComposerAttachmentState.Loading)

internal fun List<TurnComposerAttachment>.withImageAttachmentResult(
    attachmentId: String,
    attachment: CodexImageAttachment?,
    failedMessage: String,
): List<TurnComposerAttachment> =
    map { existing ->
        if (existing.id != attachmentId) {
            existing
        } else if (attachment != null) {
            existing.copy(state = TurnComposerAttachmentState.ReadyImage(attachment))
        } else {
            existing.copy(state = TurnComposerAttachmentState.Failed(failedMessage))
        }
    }

internal fun List<TurnComposerAttachment>.withFileAttachmentResult(
    attachmentId: String,
    attachment: CodexFileAttachment?,
    failedMessage: String,
): List<TurnComposerAttachment> =
    map { existing ->
        if (existing.id != attachmentId) {
            existing
        } else if (attachment != null) {
            existing.copy(state = TurnComposerAttachmentState.ReadyFile(attachment))
        } else {
            existing.copy(state = TurnComposerAttachmentState.Failed(failedMessage))
        }
    }

internal fun appendFileAttachmentsToDraft(
    baseText: String,
    files: List<CodexFileAttachment>,
    binarySummary: String,
): String {
    if (files.isEmpty()) return baseText.trim()
    val blocks = files.map { file ->
        val remote = file.remotePath
        if (!remote.isNullOrBlank()) {
            uploadedFileLink(file.fileName, remote)
        } else {
            val content = file.textContent?.trim()
            val body = if (content.isNullOrEmpty()) binarySummary else
                "```${inferCodeFenceLanguage(file.fileName)}\n$content\n```"
            val metadata = listOfNotNull(formatBytes(file.sizeBytes), file.mimeType?.takeIf(String::isNotBlank))
            "${file.fileName} (${metadata.joinToString(", ")})\n$body"
        }
    }
    return (listOf(baseText.trimEnd()).filter(String::isNotEmpty) + blocks).joinToString("\n\n")
}

private fun uploadedFileLink(name: String, path: String): String {
    val label = buildString {
        for (c in name) {
            if (c in """\`*_{}[]<>~|&""") append('\\')
            append(c)
        }
    }
    // Use an absolute path so the model and desktop can open the same original file.
    val target = java.net.URI(null, null, path, null).rawPath
        .replace("+", "%2B").replace("(", "%28").replace(")", "%29").replace("&", "%26")
    return "[$label]($target)"
}

private const val OLD_FILE_HEADER = "Attached files:\n"
private const val OLD_FILE_PATH = "Complete file uploaded to computer: "
internal val uploadSuffix = Regex("""/remodex_uploads/[0-9a-fA-F]{8}(?:-[0-9a-fA-F]{4}){3}-[0-9a-fA-F]{12}/([^/]+)$""")

internal fun displayUserMessageBody(text: String): String =
    displayUploadedFiles(com.remodex.mobile.data.ConversationQuestionCodec.displayReply(text))

/** Reformat only a complete, recognizable trailer from earlier clients. Keep history intact. */
internal fun displayUploadedFiles(text: String): String {
    val start = if (text.startsWith(OLD_FILE_HEADER)) 0 else {
        val boundary = text.lastIndexOf("\n\n$OLD_FILE_HEADER")
        if (boundary < 0) return text
        boundary + 2
    }
    val rows = text.substring(start + OLD_FILE_HEADER.length).trimEnd().lines()
    if (rows.size % 2 != 0) return text
    val links = rows.chunked(2).mapIndexed { index, (info, location) ->
        if (!location.startsWith(OLD_FILE_PATH)) return text
        val path = try {
            kotlinx.serialization.json.Json.decodeFromString<String>(location.removePrefix(OLD_FILE_PATH))
        } catch (_: kotlinx.serialization.SerializationException) { return text }
        if (!path.startsWith('/') || path.startsWith("//") || path.any { it.isISOControl() }) return text
        val name = uploadSuffix.find(path)?.groupValues?.get(1) ?: return text
        if (name.any { it.isISOControl() } || !info.startsWith("${index + 1}. $name (") || !info.endsWith(')')) return text
        uploadedFileLink(name, path)
    }
    return text.take(start) + links.joinToString("\n\n")
}

internal fun inferCodeFenceLanguage(fileName: String): String =
    when (fileName.substringAfterLast('.', "").lowercase()) {
        "kt", "kts" -> "kotlin"
        "swift" -> "swift"
        "js", "jsx" -> "javascript"
        "ts", "tsx" -> "typescript"
        "py" -> "python"
        "java" -> "java"
        "json" -> "json"
        "xml" -> "xml"
        "yaml", "yml" -> "yaml"
        "md", "markdown" -> "markdown"
        "sql" -> "sql"
        "sh" -> "bash"
        "ps1" -> "powershell"
        "html" -> "html"
        "css" -> "css"
        else -> "text"
    }

internal fun formatBytes(bytes: Int): String {
    if (bytes < 1024) return "${bytes}B"
    val kb = bytes / 1024.0
    if (kb < 1024.0) return String.format("%.1fKB", kb)
    val mb = kb / 1024.0
    return String.format("%.1fMB", mb)
}
