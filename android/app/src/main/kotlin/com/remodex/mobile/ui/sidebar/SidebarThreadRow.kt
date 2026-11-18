package com.remodex.mobile.ui.sidebar

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.CodexThread
import com.remodex.mobile.core.model.CodexThreadSyncState

data class SidebarActiveChatMetadata(
    val branch: String? = null,
    val additions: Int? = null,
    val deletions: Int? = null,
    val model: String? = null,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SidebarThreadRow(
    thread: CodexThread,
    selected: Boolean,
    isRunning: Boolean,
    activeMetadata: SidebarActiveChatMetadata? = null,
    onSelect: () -> Unit,
    onRenameRequest: (() -> Unit)? = null,
    onDeleteLocalRequest: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    onActionsRequest: (() -> Unit)? = null,
    isUnread: Boolean = false,
    needsAnswer: Boolean = false,
    hasQuestions: Boolean = false,
    onAnswerRequest: (() -> Unit)? = null,
) {
    val colors = rememberSidebarColorPalette()
    val rowShape = RoundedCornerShape(12.dp)
    val metadataTokens =
        remember(thread, selected, activeMetadata, colors) {
            if (selected) thread.activeMetadataTokens(colors, activeMetadata) else emptyList()
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 2.dp),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 40.dp)
                    .clip(rowShape)
                    .background(if (selected) colors.selectedRow else Color.Transparent)
                    .combinedClickable(
                        onClick = onSelect,
                        onLongClick = { if (onActionsRequest != null) onActionsRequest() else onRenameRequest?.invoke() },
                    )
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Reserve the status slot so starting/stopping a turn never shifts the title.
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                SidebarThreadRunBadge(isRunning = isRunning)
            }
            Text(
                text = thread.displayTitle,
                style =
                    MaterialTheme.typography.bodyLarge.copy(
                        fontSize = 14.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = colors.primaryText,
                modifier = Modifier.weight(1f),
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (metadataTokens.isNotEmpty() && selected) {
                    Text(
                        text = metadataTokens.first().text,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.sp),
                        color = metadataTokens.first().color,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.widthIn(max = 60.dp),
                    )
                }
                val rel = SidebarRelativeTimeFormatter.compactLabel(thread)
                if (rel != null) {
                    Text(
                        text = rel,
                        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.sp),
                        color = colors.mutedText,
                        maxLines = 1,
                    )
                }
                if (isUnread) {
                    Text(
                        text = stringResource(R.string.sidebar_unread_badge),
                        style = MaterialTheme.typography.labelSmall.copy(
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        ),
                        color = colors.unread,
                        maxLines = 1,
                        modifier = Modifier
                            .background(colors.unreadBackground, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 3.dp),
                    )
                }
                if (needsAnswer || hasQuestions) {
                    com.remodex.mobile.ui.turn.QuestionReplyIndicator(onAnswerRequest ?: onSelect)
                }
            }
        }
        if (thread.syncState == CodexThreadSyncState.archivedLocal) {
            Text(
                text = stringResource(R.string.sidebar_thread_archived_hint),
                style = MaterialTheme.typography.labelSmall,
                color = colors.mutedText,
                maxLines = 1,
                modifier = Modifier.padding(start = 44.dp, top = 2.dp),
            )
        }
    }
}

@Composable
fun ActiveChatRow(
    thread: CodexThread,
    isRunning: Boolean,
    activeMetadata: SidebarActiveChatMetadata? = null,
    onSelect: () -> Unit,
    onRenameRequest: (() -> Unit)?,
    onDeleteLocalRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onActionsRequest: (() -> Unit)? = null,
    isUnread: Boolean = false,
    needsAnswer: Boolean = false,
    hasQuestions: Boolean = false,
    onAnswerRequest: (() -> Unit)? = null,
) {
    SidebarThreadRow(
        thread = thread,
        selected = true,
        isRunning = isRunning,
        activeMetadata = activeMetadata,
        onSelect = onSelect,
        onRenameRequest = onRenameRequest,
        onDeleteLocalRequest = onDeleteLocalRequest,
        modifier = modifier,
        onActionsRequest = onActionsRequest,
        isUnread = isUnread,
        needsAnswer = needsAnswer,
        hasQuestions = hasQuestions,
        onAnswerRequest = onAnswerRequest,
    )
}

@Composable
fun ChatRow(
    thread: CodexThread,
    isRunning: Boolean,
    onSelect: () -> Unit,
    onRenameRequest: (() -> Unit)?,
    onDeleteLocalRequest: (() -> Unit)?,
    modifier: Modifier = Modifier,
    onActionsRequest: (() -> Unit)? = null,
    isUnread: Boolean = false,
    needsAnswer: Boolean = false,
    hasQuestions: Boolean = false,
    onAnswerRequest: (() -> Unit)? = null,
) {
    SidebarThreadRow(
        thread = thread,
        selected = false,
        isRunning = isRunning,
        activeMetadata = null,
        onSelect = onSelect,
        onRenameRequest = onRenameRequest,
        onDeleteLocalRequest = onDeleteLocalRequest,
        modifier = modifier,
        onActionsRequest = onActionsRequest,
        isUnread = isUnread,
        needsAnswer = needsAnswer,
        hasQuestions = hasQuestions,
        onAnswerRequest = onAnswerRequest,
    )
}

private data class SidebarMetadataToken(
    val text: String,
    val color: Color,
)

private fun CodexThread.activeMetadataTokens(
    colors: SidebarColorPalette,
    activeMetadata: SidebarActiveChatMetadata?,
): List<SidebarMetadataToken> {
    val tokens = mutableListOf<SidebarMetadataToken>()
    (
        activeMetadata?.branch?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstMetadataString(
                "branch",
                "current",
                "currentBranch",
                "current_branch",
                "gitBranch",
                "git_branch",
                "headBranch",
                "head_branch",
            )
    )
        ?.let { tokens += SidebarMetadataToken(it, colors.secondaryText) }
    (
        activeMetadata?.additions
            ?: firstMetadataInt("additions", "added", "linesAdded", "lines_added", "insertions")
    )
        ?.takeIf { it > 0 }
        ?.let { tokens += SidebarMetadataToken("+$it", colors.green) }
    (
        activeMetadata?.deletions
            ?: firstMetadataInt("deletions", "deleted", "linesDeleted", "lines_deleted", "removals")
    )
        ?.takeIf { it > 0 }
        ?.let { tokens += SidebarMetadataToken("-$it", colors.red) }
    (
        activeMetadata?.model?.trim()?.takeIf { it.isNotEmpty() }
            ?: model?.trim()?.takeIf { it.isNotEmpty() }
            ?: firstMetadataString("model", "modelName", "model_name", "selectedModel", "selected_model")
    )?.let { tokens += SidebarMetadataToken(it.compactModelLabel(), colors.secondaryText) }
    return tokens
}

private fun CodexThread.firstMetadataString(vararg keys: String): String? {
    val meta = metadata ?: return null
    return keys.firstNotNullOfOrNull { key ->
        meta[key]?.stringValue?.trim()?.takeIf { it.isNotEmpty() }
    }
}

private fun CodexThread.firstMetadataInt(vararg keys: String): Int? {
    val meta = metadata ?: return null
    return keys.firstNotNullOfOrNull { key ->
        when (val value = meta[key]) {
            is JSONValue.NumLong -> value.value.toInt()
            is JSONValue.NumDouble -> value.value.toInt()
            is JSONValue.Str -> value.value.trim().toIntOrNull()
            else -> null
        }
    }
}

private fun String.compactModelLabel(): String {
    val cleaned =
        trim()
            .removePrefix("gpt-")
            .replace("reasoning", "", ignoreCase = true)
            .trim('-', ' ')
    return cleaned.ifEmpty { trim() }
}
