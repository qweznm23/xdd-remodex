package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.ConversationMediaType
@Composable
internal fun ConversationVideoCard(source: String) {
    val open = rememberOpenConversationVideo(source)
    Surface(onClick = open, shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.widthIn(min = 160.dp, max = 320.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Default.PlayCircle, stringResource(R.string.conversation_video_open), modifier = Modifier.size(40.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.conversation_video_title), style = MaterialTheme.typography.labelMedium)
                Text(ConversationMediaType.filename(source), maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
internal fun rememberOpenConversationVideo(source: String): () -> Unit {
    val repository = com.remodex.mobile.ui.LocalCodexRepository.current
    val device by repository.relayMacDeviceId.collectAsState()
    val cwd = LocalThreadGitProjectRoot.current
    var show by remember(source,cwd,device) { mutableStateOf(false) }
    if (show) ConversationVideoPlayer(source,cwd,onDismiss = {show = false})
    return {show = true}
}
