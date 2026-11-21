package com.remodex.mobile.ui.turn

import android.content.ClipData
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.remodex.mobile.ui.theme.CodeFontFamily
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.remodex.mobile.R
import com.remodex.mobile.core.model.TurnTimelineCacheKey
import java.net.URI
import kotlinx.coroutines.launch

// Latin-only resources keep Chinese on Android's normal fallback and avoid per-token spans.
private val AssistantProseFamily = FontFamily(
    Font(R.font.noto_serif_latin_regular),
    Font(R.font.noto_serif_latin_bold, FontWeight.Bold),
    Font(R.font.noto_serif_latin_italic, style = FontStyle.Italic),
    Font(R.font.noto_serif_latin_bold_italic, FontWeight.Bold, FontStyle.Italic),
)

internal val LocalActivityDetail = androidx.compose.runtime.staticCompositionLocalOf { false }

/** Only a single, literal paragraph qualifies. All Markdown punctuation and links take the rich path. */
internal fun isPlainActivityParagraph(text: String): Boolean =
    text.isNotEmpty() && text.first().isLetterOrDigit() && text.last() != ' ' && !text.contains("  ") &&
        text.indices.all { index ->
            val c = text[index]
            c.isLetterOrDigit() || c in " ，。！？；：、（）,!?" || (c == '.' && index == text.lastIndex && text.first().isLetter())
        }

/**
 * Render prose and dedicated interactive blocks in their original order.
 */
@Composable
fun TurnMarkdownBody(
    markdown: String,
    contentColor: Color,
    modifier: Modifier = Modifier,
    keyPrefix: String = TurnTimelineCacheKey.textKey("markdown-body", markdown),
    linkColor: Color = contentColor,
    fitProse: Boolean = false,
    assistantProse: Boolean = false,
) {
    val displayMarkdown = TurnMarkdownRenderCache.visibleProse(markdown)
    if (LocalActivityDetail.current && remember(displayMarkdown) { isPlainActivityParagraph(displayMarkdown) }) {
        val typography = MaterialTheme.typography.bodyMedium
        val style = if (assistantProse) typography.copy(
            fontFamily = com.remodex.mobile.ui.theme.LocalReplyFont.current ?: AssistantProseFamily,
        ) else typography
        Text(displayMarkdown, modifier = modifier, style = style, color = contentColor)
        return
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val segments = TurnMarkdownRenderCache.fenceSegments(displayMarkdown)
    val dispatchRepoDiff = LocalOpenRepoDiffForMarkdownLink.current
    val threadProjectRoot = LocalThreadGitProjectRoot.current
    var videoPreview by remember(keyPrefix,threadProjectRoot) { mutableStateOf<String?>(null) }
    videoPreview?.let { ConversationVideoPlayer(it,threadProjectRoot,onDismiss = {videoPreview = null}) }
    var interactivePreview by remember(keyPrefix, threadProjectRoot) { mutableStateOf<InteractivePageReference?>(null) }
    interactivePreview?.let { InteractivePageDialog(it, threadProjectRoot) { interactivePreview = null } }
    val defaultUriHandler = LocalUriHandler.current
    val repoFileMarkdownUriHandler =
        remember(dispatchRepoDiff, defaultUriHandler, threadProjectRoot, keyPrefix) {
            object : UriHandler {
                override fun openUri(uri: String) {
                    val trimmed = uri.trim()
                    if (com.remodex.mobile.core.model.ConversationMediaType.isVideo(trimmed)) {
                        videoPreview = trimmed
                    } else if (RepoMarkdownFileLink.previewPath(trimmed) != null) {
                        dispatchRepoDiff(trimmed, threadProjectRoot)
                    } else if (MarkdownUrlPolicy.isAllowedLink(trimmed)) {
                        runCatching { defaultUriHandler.openUri(trimmed) }
                    }
                }
            }
        }

    Column(
        modifier = if (fitProse) modifier else modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            when (segment) {
                is MarkdownFenceSegment.Text -> {
                    val state = rememberCachedMarkdown(segment.markdown)
                    val typography = MaterialTheme.typography
                    val replyFont = com.remodex.mobile.ui.theme.LocalReplyFont.current ?: AssistantProseFamily
                    val bodyStyle = if (assistantProse) typography.bodyMedium.copy(fontFamily = replyFont) else typography.bodyMedium
                    val titleStyle = if (assistantProse) typography.titleMedium.copy(fontFamily = replyFont) else typography.titleMedium
                    CompositionLocalProvider(LocalUriHandler provides repoFileMarkdownUriHandler) {
                        Markdown(
                            state = state,
                            error = { Text(segment.markdown, style = bodyStyle, color = contentColor) },
                            imageTransformer = ConversationMarkdownImages,
                            modifier = if (fitProse) Modifier else Modifier.fillMaxWidth(),
                            colors = markdownColor(text = contentColor),
                            typography =
                                markdownTypography(
                                    h1 =
                                        titleStyle.copy(
                                            fontSize = 17.sp,
                                            lineHeight = 22.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    h2 =
                                        titleStyle.copy(
                                            fontSize = 16.sp,
                                            lineHeight = 21.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    h3 =
                                        bodyStyle.copy(
                                            fontSize = 15.sp,
                                            lineHeight = 20.sp,
                                            fontWeight = FontWeight.SemiBold,
                                        ),
                                    h4 = bodyStyle.copy(fontWeight = FontWeight.SemiBold),
                                    h5 = bodyStyle.copy(fontWeight = FontWeight.Medium),
                                    h6 = bodyStyle.copy(fontWeight = FontWeight.Medium),
                                    text = bodyStyle,
                                    paragraph = bodyStyle,
                                    ordered = bodyStyle,
                                    bullet = bodyStyle,
                                    list = bodyStyle,
                                    table = bodyStyle,
                                    code = MaterialTheme.typography.bodySmall.copy(fontFamily = CodeFontFamily),
                                    inlineCode = bodyStyle.copy(fontFamily = CodeFontFamily),
                                    quote = bodyStyle,
                                    textLink =
                                        TextLinkStyles(
                                            style =
                                                bodyStyle
                                                    .copy(
                                                        color = linkColor,
                                                        fontWeight = FontWeight.SemiBold,
                                                        textDecoration = TextDecoration.Underline,
                                                    )
                                                    .toSpanStyle(),
                                        ),
                                ),
                            components =
                                markdownComponents(
                                    paragraph = { model -> ConversationImageParagraph(model) },
                                    image = { model -> MarkdownImageAlbumCard(model) },
                                    table = { model -> ConversationMarkdownTable(model) },
                                    codeBlock = { model ->
                                        CodeBlockCard(
                                            raw = remember(model.node, model.content) {
                                                val block = model.content.substring(model.node.startOffset, model.node.endOffset)
                                                block.lineSequence().joinToString("\n") { line ->
                                                    if (line.startsWith("\t")) line.drop(1) else line.removePrefix("    ")
                                                }.trimEnd('\n', '\r')
                                            },
                                            language = null,
                                            expansionKey = "$keyPrefix-inline-code-$index",
                                            onCopy = { text ->
                                                scope.launch {
                                                    clipboard.setClipEntry(ClipData.newPlainText("code", text).toClipEntry())
                                                }
                                            },
                                        )
                                    },
                                ),
                        )
                    }
                }
                is MarkdownFenceSegment.Interactive -> {
                    InteractivePageCard(segment.reference) { interactivePreview = segment.reference }
                }
                is MarkdownFenceSegment.Code -> {
                    CodeBlockCard(
                        raw = segment.code,
                        language = segment.language,
                        expansionKey = "$keyPrefix-code-$index",
                        onCopy = { text ->
                            scope.launch {
                                clipboard.setClipEntry(ClipData.newPlainText("code", text).toClipEntry())
                            }
                        },
                    )
                }
            }
        }
    }
}

internal object MarkdownUrlPolicy {
    private val allowedLinkSchemes = setOf("http", "https", "mailto")

    fun isAllowedLink(raw: String): Boolean {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return false
        val scheme = runCatching { URI(trimmed).scheme?.lowercase() }.getOrNull()
        return scheme != null && scheme in allowedLinkSchemes
    }
}

internal sealed interface MarkdownFenceSegment {
    data class Text(val markdown: String) : MarkdownFenceSegment

    data class Interactive(val reference: InteractivePageReference) : MarkdownFenceSegment

    data class Code(
        val code: String,
        val language: String?,
    ) : MarkdownFenceSegment
}

internal object MarkdownFenceSegmentParser {
    fun parse(markdown: String): List<MarkdownFenceSegment> {
        if (markdown.isBlank()) return emptyList()

        val lines = markdown.split('\n')
        val segments = mutableListOf<MarkdownFenceSegment>()
        val textBuffer = mutableListOf<String>()
        val codeBuffer = mutableListOf<String>()
        var activeFence: MarkdownFence? = null

        fun flushText() {
            val text = textBuffer.joinToString("\n").trimEnd()
            if (text.isNotBlank()) {
                segments += MarkdownFenceSegment.Text(text)
            }
            textBuffer.clear()
        }

        fun flushCode(fence: MarkdownFence) {
            val code = codeBuffer.joinToString("\n")
            if (code.isNotBlank()) {
                segments += MarkdownFenceSegment.Code(code = code, language = fence.language)
            }
            codeBuffer.clear()
        }

        lines.forEach { line ->
            val trimmed = line.trimStart()
            val fence = activeFence
            if (fence == null) {
                val opening = parseOpeningFence(trimmed)
                if (opening == null) {
                    val page = InteractivePageReference.parse(line)
                    if (page != null) {
                        flushText()
                        segments += MarkdownFenceSegment.Interactive(page)
                    } else textBuffer += line
                } else {
                    flushText()
                    activeFence = opening
                }
            } else if (isClosingFence(trimmed, fence)) {
                flushCode(fence)
                activeFence = null
            } else {
                codeBuffer += line
            }
        }

        val danglingFence = activeFence
        if (danglingFence != null) {
            textBuffer += danglingFence.openingLine
            textBuffer += codeBuffer
            codeBuffer.clear()
        }
        flushText()

        return segments.ifEmpty { listOf(MarkdownFenceSegment.Text(markdown)) }
    }

    /** Keep the closing fence when the chat's prose line limit falls inside a complete block. */
    fun closingFenceAtLimit(text: String, limit: Int): Int? {
        var fence: MarkdownFence? = null
        var offset = 0
        for ((index, line) in text.lineSequence().withIndex()) {
            val current = fence
            if (current == null) {
                fence = parseOpeningFence(line.trimStart())
            } else if (isClosingFence(line.trimStart(), current)) {
                if (index >= limit) return offset + line.length
                fence = null
            }
            if (index + 1 >= limit && fence == null) return null
            offset += line.length
            if (text.startsWith("\r\n", offset)) offset += 2 else offset++
        }
        return null
    }

    private data class MarkdownFence(
        val markerChar: Char,
        val markerLength: Int,
        val language: String?,
        val openingLine: String,
    )

    private fun parseOpeningFence(trimmedLine: String): MarkdownFence? {
        val markerChar =
            when {
                trimmedLine.startsWith("```") -> '`'
                trimmedLine.startsWith("~~~") -> '~'
                else -> return null
            }
        val markerLength = trimmedLine.takeWhile { it == markerChar }.length
        if (markerLength < 3) return null
        val trailing = trimmedLine.drop(markerLength).trim()
        if (markerChar in trailing) return null
        val language =
            trailing
                .substringBefore(' ')
                .trim()
                .takeIf { it.isNotEmpty() }
        return MarkdownFence(
            markerChar = markerChar,
            markerLength = markerLength,
            language = language,
            openingLine = trimmedLine,
        )
    }

    private fun isClosingFence(
        trimmedLine: String,
        fence: MarkdownFence,
    ): Boolean {
        if (!trimmedLine.startsWith(fence.markerChar.toString().repeat(fence.markerLength))) return false
        val markerRun = trimmedLine.takeWhile { it == fence.markerChar }
        return markerRun.length >= fence.markerLength && trimmedLine.drop(markerRun.length).isBlank()
    }
}
