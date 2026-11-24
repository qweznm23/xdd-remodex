package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.ui.theme.RemodexPreviewDialog
import androidx.compose.ui.window.DialogProperties
import com.mikepenz.markdown.compose.components.MarkdownComponentModel
import com.mikepenz.markdown.compose.elements.MarkdownParagraph
import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.ConversationMediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode

private val LocalOpenConversationImage = staticCompositionLocalOf<((CodexImageAttachment) -> Unit)?> { null }
private data class ImageGalleryRequest(val selected: CodexImageAttachment, val messages: List<CodexMessage>)

private fun ASTNode.imageDestination(): ASTNode? =
    if (type == MarkdownElementTypes.LINK_DESTINATION) this else children.firstNotNullOfOrNull { it.imageDestination() }

/** Build only when opening a preview, off the UI thread; streaming never reparses the gallery. */
internal fun conversationGalleryImages(messages: List<CodexMessage>, selected: CodexImageAttachment): List<CodexImageAttachment> {
    val result = linkedMapOf<String, CodexImageAttachment>()
    fun add(image: CodexImageAttachment) {
        if (ConversationMediaType.videoSource(image) == null) {
            val key = attachmentImageSource(image, true)
            if (key.isNotBlank()) result.putIfAbsent(key, image)
        }
    }
    val prepared = messages.map { com.remodex.mobile.data.prepareImageAlbum(it) }
    val albums = com.remodex.mobile.data.groupResponseImageAlbums(
        prepared.map { it.message },
        prepared.filter { it.explicitOrder.isNotEmpty() }.associate { it.message.id to it.explicitOrder },
    )
    albums.forEach { message ->
        message.attachments.forEach(::add)
    }
    add(selected)
    return result.values.toList()
}

@Composable
internal fun ConversationImageGalleryHost(threadId: String, messages: List<CodexMessage>, content: @Composable () -> Unit) {
    var request by remember(threadId) { mutableStateOf<ImageGalleryRequest?>(null) }
    val latestMessages by rememberUpdatedState(messages)
    val open = remember(threadId) { { image: CodexImageAttachment -> request = ImageGalleryRequest(image, latestMessages) } }
    CompositionLocalProvider(LocalOpenConversationImage provides open, content = content)
    request?.let { snapshot ->
        key(snapshot) {
            val images by produceState<List<CodexImageAttachment>?>(null, snapshot) {
                value = withContext(Dispatchers.Default) { conversationGalleryImages(snapshot.messages, snapshot.selected) }
            }
            // Open only after collecting a stable page list. Changing the pager count while
            // its old key provider is still installed can race a measure pass.
            images?.let { collected ->
                ConversationImageGalleryDialog(collected, attachmentImageSource(snapshot.selected, true), onDismiss = { request = null })
            }
        }
    }
}

@Composable
internal fun rememberOpenConversationImage(
    attachment: CodexImageAttachment,
    fallbackImages: List<CodexImageAttachment> = listOf(attachment),
): () -> Unit {
    val open = LocalOpenConversationImage.current
    var preview by remember(attachment) { mutableStateOf(false) }
    if (preview) ConversationImageGalleryDialog(
        fallbackImages.filter { ConversationMediaType.videoSource(it) == null },
        attachmentImageSource(attachment, true), { preview = false },
    )
    return { if (open != null) open(attachment) else preview = true }
}

@Composable
internal fun ConversationImageParagraph(model: MarkdownComponentModel) {
    val images = model.node.children.filter { it.type == MarkdownElementTypes.IMAGE }
    val onlyImages = images.isNotEmpty() && model.node.children.all {
        it.type == MarkdownElementTypes.IMAGE || model.content.substring(it.startOffset, it.endOffset).isBlank()
    }
    if (onlyImages) {
        val links = remember(model.node, model.content) {
            images.mapNotNull { it.imageDestination()?.getUnescapedTextInNode(model.content) }
        }
        val attachments = remember(links) {
            links.filterNot { ConversationMediaType.isVideo(it) }.distinct().map {
                CodexImageAttachment(id = it, thumbnailBase64JPEG = "", sourceURL = it)
            }
        }
        Column {
            ConversationImageAlbum(attachments)
            links.filter { ConversationMediaType.isVideo(it) }.forEach { ConversationVideoCard(it) }
        }
    } else {
        MarkdownParagraph(model.content, model.node, style = model.typography.paragraph)
    }
}

@Composable
internal fun MarkdownImageAlbumCard(model: MarkdownComponentModel) {
    val link = remember(model.node, model.content) {
        model.node.imageDestination()?.getUnescapedTextInNode(model.content)
    } ?: return
    if (ConversationMediaType.isVideo(link)) {
        ConversationVideoCard(link)
        return
    }
    val attachment = remember(link) { CodexImageAttachment(id = link, thumbnailBase64JPEG = "", sourceURL = link) }
    ConversationImageAlbum(listOf(attachment))
}

@Composable
internal fun ConversationImageGalleryDialog(
    images: List<CodexImageAttachment>, selectedSource: String, onDismiss: () -> Unit,
) {
    if (images.isEmpty()) return
    val pager = rememberPagerState(initialPage = images.indexOfFirst { attachmentImageSource(it, true) == selectedSource }.coerceAtLeast(0),
        pageCount = { images.size })
    var zoomedPage by remember { mutableStateOf<Int?>(null) }
    ImagePreviewDialog(
        title = if (images.size > 1) "${pager.currentPage + 1} / ${images.size}" else "",
        onDismiss = onDismiss,
        actions = {
            val current = images[pager.currentPage]
            val source = current.remotePath?.takeIf { it.isNotBlank() } ?: attachmentImageSource(current, true)
            MediaFileActions(source, LocalThreadGitProjectRoot.current, current.mimeType,
                thumbnailOnly = current.remotePath.isNullOrBlank() && current.sourceURL.isNullOrBlank() && current.payloadDataURL.isNullOrBlank())
        },
    ) {
        HorizontalPager(state = pager, userScrollEnabled = zoomedPage != pager.currentPage,
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize(), key = { attachmentImageSource(images[it], true) }) { index ->
            GalleryImagePage(images[index], onDismiss, pagingEnabled = images.size > 1,
                active = pager.currentPage == index,
                onZoomChanged = { zoomed ->
                    if (index == pager.currentPage) zoomedPage = if (zoomed) index else null
                })
        }
    }
}

@Composable
internal fun ImagePreviewDialog(
    title: String,
    onDismiss: () -> Unit,
    actions: @Composable () -> Unit,
    content: @Composable () -> Unit,
) {
    RemodexPreviewDialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                    actions()
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.turn_preview_close)) }
                }
                Box(Modifier.fillMaxSize().padding(bottom = 16.dp), contentAlignment = Alignment.Center) { content() }
            }
        }
    }
}

@Composable
private fun GalleryImagePage(attachment: CodexImageAttachment, onDismiss: () -> Unit, pagingEnabled: Boolean, active: Boolean, onZoomChanged: (Boolean) -> Unit) {
    val image = rememberConversationImage(attachmentImageSource(attachment, true),
        fallbackDataUrl = attachment.thumbnailBase64JPEG.takeIf { it.isNotBlank() }?.let { "data:image/jpeg;base64,$it" })
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (!image.loading && image.error == null && image.hasImage) {
            ZoomablePreviewImage(image.painter, attachmentImageSource(attachment, true), Modifier.fillMaxSize(),
                onDismiss = onDismiss, pagingEnabled = pagingEnabled, active = active, onZoomChanged = onZoomChanged)
        } else if (image.waitingForConnection && !image.loading && image.error == null) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(stringResource(R.string.conversation_image_waiting_connection))
                TextButton(onClick = image.retry) { Text(stringResource(R.string.conversation_image_reconnect)) }
            }
        } else if (image.loading) CircularProgressIndicator()
        else Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.conversation_image_failed), color = MaterialTheme.colorScheme.error)
            image.error?.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = image.retry) { Text(stringResource(R.string.workspace_text_preview_retry)) }
        }
    }
}
