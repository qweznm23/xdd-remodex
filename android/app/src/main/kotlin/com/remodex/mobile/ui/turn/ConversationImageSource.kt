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

internal fun conversationImageSource(raw: String): ConversationImageSource {
    val source = raw.trim()
    require(source.isNotEmpty()) { "Image source is empty stage=image_source" }
    if (source.startsWith("https://", true) || source.startsWith("http://", true)) {
        return ConversationImageSource.Remote(source)
    }
    if (source.startsWith("data:image/", true)) return ConversationImageSource.Inline(source)
    val local = source.removePrefix("sandbox:")
    require(!Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(local) ||
        local.startsWith("file:", true) || Regex("^[a-zA-Z]:[/\\\\]").containsMatchIn(local)) {
        "Unsupported image source scheme stage=image_source"
    }
    return ConversationImageSource.Workspace(
        RepoMarkdownFileLink.previewPath(local)
            ?: throw IllegalArgumentException("Invalid image path stage=image_source"),
    )
}
