package com.remodex.mobile.ui.turn

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexImageAttachment

/** A single bounded row per message, regardless of how many photos the message contains. */
@Composable
internal fun ConversationImageAlbum(images: List<CodexImageAttachment>) {
    if (images.isEmpty()) return
    val visibleCount = minOf(3, images.size)
    Row(
        modifier = Modifier.widthIn(max = (visibleCount * 104 + (visibleCount - 1) * 4).dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        images.take(visibleCount).forEachIndexed { index, attachment ->
            key(com.remodex.mobile.data.albumImageKey(attachment)) {
            val image = rememberConversationImage(attachmentImageSource(attachment, false), 512)
            val open = rememberOpenConversationImage(attachment, images)
            val label = stringResource(R.string.conversation_album_image, index + 1, images.size)
            Box(
                modifier = Modifier.weight(1f).aspectRatio(1f).clip(MaterialTheme.shapes.medium)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .semantics(mergeDescendants = true) { contentDescription = label }
                    .clickable(onClick = open),
                contentAlignment = Alignment.Center,
            ) {
                when {
                    !image.loading && image.error == null && image.hasImage ->
                        Image(image.painter, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    image.loading -> CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    else -> Icon(
                        if (image.waitingForConnection && image.error == null) Icons.Default.CloudOff else Icons.Default.BrokenImage,
                        contentDescription = null,
                        tint = if (image.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (index == 2 && images.size > visibleCount) {
                    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.48f)), contentAlignment = Alignment.Center) {
                        Text("+${images.size - visibleCount}", color = Color.White, style = MaterialTheme.typography.headlineSmall)
                    }
                }
            }
            }
        }
    }
}
