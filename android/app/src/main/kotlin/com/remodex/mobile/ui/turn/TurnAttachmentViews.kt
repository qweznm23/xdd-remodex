package com.remodex.mobile.ui.turn

import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.ConversationMediaType
import com.remodex.mobile.core.model.CodexFileAttachment
import com.remodex.mobile.core.model.CodexImageAttachment
import java.util.UUID

internal data class TurnComposerAttachment(
    val id: String = UUID.randomUUID().toString(),
    val state: TurnComposerAttachmentState,
)

internal sealed interface TurnComposerAttachmentState {
    data object Loading : TurnComposerAttachmentState
    data class Uploading(val bytes: Long, val total: Long?) : TurnComposerAttachmentState

    data class ReadyImage(
        val attachment: CodexImageAttachment,
    ) : TurnComposerAttachmentState

    data class ReadyFile(
        val attachment: CodexFileAttachment,
    ) : TurnComposerAttachmentState

    data class Failed(
        val message: String,
        val canRetry: Boolean = false,
    ) : TurnComposerAttachmentState
}

@Composable
internal fun ComposerAttachmentStrip(
    attachments: List<TurnComposerAttachment>,
    onRemove: (String) -> Unit,
    onRetry: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            ComposerAttachmentTile(
                attachment = attachment,
                onRemove = { onRemove(attachment.id) },
                onRetry = { onRetry(attachment.id) },
            )
        }
    }
}

@Composable
internal fun MessageAttachmentStrip(
    attachments: List<CodexImageAttachment>,
    modifier: Modifier = Modifier,
) {
    if (attachments.isEmpty()) return
    val images = remember(attachments) { attachments.filter { ConversationMediaType.videoSource(it) == null }.distinctBy { attachmentImageSource(it, true) } }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ConversationImageAlbum(images)
        attachments.mapNotNull { ConversationMediaType.videoSource(it) }.forEach { ConversationVideoCard(it) }
    }
}

@Composable
private fun ComposerAttachmentTile(
    attachment: TurnComposerAttachment,
    onRemove: () -> Unit,
    onRetry: () -> Unit,
) {
    Box {
        when (val state = attachment.state) {
            TurnComposerAttachmentState.Loading -> PlaceholderAttachmentThumbnail(loading = true)
            is TurnComposerAttachmentState.Uploading -> Column(
                modifier = Modifier.size(70.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                Text(
                    text = if (state.total != null && state.total > 0) "${state.bytes * 100 / state.total}%" else "${state.bytes / 1024} KB",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            is TurnComposerAttachmentState.Failed -> Column(
                modifier = Modifier.widthIn(min = 100.dp, max = 190.dp).padding(top = 24.dp),
            ) {
                Text(state.message, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (state.canRetry) TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.attachment_retry_upload))
                }
            }
            is TurnComposerAttachmentState.ReadyImage -> {
                var showPreview by remember(state.attachment) { mutableStateOf(false) }
                ReadyAttachmentThumbnail(
                    attachment = state.attachment,
                    onClick = { showPreview = true },
                )
                if (showPreview) {
                    AttachmentImagePreviewDialog(
                        attachment = state.attachment,
                        onDismiss = { showPreview = false },
                    )
                }
            }
            is TurnComposerAttachmentState.ReadyFile -> ReadyFileAttachmentThumbnail(attachment = state.attachment)
        }
        IconButton(
            onClick = onRemove,
            modifier =
                Modifier
                    .align(Alignment.TopEnd)
                    .size(24.dp),
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.turn_remove_attachment_cd),
            )
        }
    }
}

@Composable
private fun ReadyAttachmentThumbnail(
    attachment: CodexImageAttachment,
    onClick: (() -> Unit)? = null,
    size: Dp = 70.dp,
) {
    val image = rememberConversationImage(attachmentImageSource(attachment, preferPayload = false), 512)
    val shape = MaterialTheme.shapes.medium
    val clickModifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
    Box(
        modifier =
            Modifier
.size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(clickModifier),
        contentAlignment = Alignment.Center,
    ) {
        if (!image.loading && image.error == null && image.hasImage) {
            Image(
                painter = image.painter,
                contentDescription = stringResource(R.string.conversation_image_preview),
                contentScale = if (size > 70.dp) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else if (image.loading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        } else {
            Icon(
                imageVector = if (image.waitingForConnection && image.error == null) Icons.Default.CloudOff else Icons.Default.BrokenImage,
                contentDescription = stringResource(if (image.waitingForConnection && image.error == null) R.string.conversation_image_waiting_connection else R.string.conversation_image_failed),
                tint = if (image.waitingForConnection && image.error == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
            )
        }
    }
}

@Composable
private fun ReadyFileAttachmentThumbnail(attachment: CodexFileAttachment) {
    val shape = MaterialTheme.shapes.medium
    Box(
        modifier =
            Modifier
                .size(70.dp)
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.82f)),
    ) {
        Icon(
            imageVector = Icons.Default.Description,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.TopStart).size(18.dp),
        )
        Text(
            text = attachment.fileName,
            style = MaterialTheme.typography.labelSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
internal fun AttachmentImagePreviewDialog(
    attachment: CodexImageAttachment,
    onDismiss: () -> Unit,
) {
    ConversationImageGalleryDialog(listOf(attachment), attachmentImageSource(attachment, true), onDismiss)
}

@Composable
private fun PlaceholderAttachmentThumbnail(loading: Boolean) {
    Box(
        modifier =
            Modifier
                .size(70.dp)
                .clip(MaterialTheme.shapes.medium)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
        } else {
            Icon(
                imageVector = Icons.Default.BrokenImage,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        }
    }
}

internal fun String.isGifReference(): Boolean = if (startsWith("data:", ignoreCase = true)) {
    startsWith("data:image/gif;", ignoreCase = true)
} else substringBefore('?').substringBefore('#').endsWith(".gif", ignoreCase = true)

internal fun attachmentImageSource(attachment: CodexImageAttachment, preferPayload: Boolean): String {
    if (preferPayload) {
        listOfNotNull(attachment.payloadDataURL, attachment.remotePath, attachment.sourceURL)
            .firstOrNull { it.isGifReference() }?.let { return it }
        if (attachment.mimeType.equals("image/gif", true)) {
            (attachment.remotePath ?: attachment.sourceURL)?.takeIf { it.isNotBlank() }?.let { return it }
        }
    }
    return when {
        preferPayload && !attachment.payloadDataURL.isNullOrBlank() -> attachment.payloadDataURL!!
        !preferPayload && attachment.thumbnailBase64JPEG.isNotBlank() -> "data:image/jpeg;base64,${attachment.thumbnailBase64JPEG}"
        !attachment.payloadDataURL.isNullOrBlank() -> attachment.payloadDataURL!!
        !attachment.sourceURL.isNullOrBlank() -> attachment.sourceURL!!
        attachment.thumbnailBase64JPEG.isNotBlank() -> "data:image/jpeg;base64,${attachment.thumbnailBase64JPEG}"
        else -> ""
    }
}
