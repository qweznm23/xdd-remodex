package com.remodex.mobile.ui.turn

internal sealed interface ConversationImageSource {
    data class Remote(val url: String) : ConversationImageSource
    data class Inline(val dataUrl: String) : ConversationImageSource
    data class Workspace(val path: String) : ConversationImageSource {
        // Absolute image links use the same bounded directory as file and video previews.
        // Relative image links still belong to the conversation's working directory.
        fun previewCwd(threadCwd: String?): String? =
            RepoMarkdownFileLink.previewWorkspaceCwd(path) ?: threadCwd
    }
}

private val uploadedImagePath = Regex("""^(?:/|[A-Za-z]:[/\\]).*[/\\]remodex_uploads[/\\][0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}[/\\][^/\\]+$""")

internal fun conversationImageSource(raw: String): ConversationImageSource {
    // Uploaded paths are filesystem references and may contain literal URL delimiters.
    if (uploadedImagePath.matches(raw)) return ConversationImageSource.Workspace(raw)
    val source = raw.trim()
    require(source.isNotEmpty()) { "Image source is empty stage=image_source" }
    if (source.startsWith("https://", true) || source.startsWith("http://", true)) {
        return ConversationImageSource.Remote(source)
    }
    if (source.startsWith("data:image/", true)) return ConversationImageSource.Inline(source)
    require(!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(source) ||
        source.startsWith("file:", true) || source.startsWith("sandbox:", true) ||
        Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(source)) {
        "Unsupported image source scheme stage=image_source"
    }
    return ConversationImageSource.Workspace(
        RepoMarkdownFileLink.previewPath(source)
            ?: throw IllegalArgumentException("Invalid image path stage=image_source"),
    )
}
