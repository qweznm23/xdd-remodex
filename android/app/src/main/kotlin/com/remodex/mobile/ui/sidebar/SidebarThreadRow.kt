package com.remodex.mobile.ui.sidebar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.remodex.mobile.ui.turn.QuestionReplyIndicator
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

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SidebarThreadRow(
    thread: CodexThread,
    selected: Boolean,
    projectLabel: String? = null,
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
    colors: SidebarColorPalette = rememberSidebarColorPalette(),
) {
    val subtitle = when {
        thread.syncState == CodexThreadSyncState.archivedLocal ->
            SidebarMetadataToken(stringResource(R.string.sidebar_thread_archived_hint), colors.mutedText)
        !projectLabel.isNullOrBlank() -> SidebarMetadataToken(projectLabel, colors.mutedText)
        selected -> remember(thread, activeMetadata, colors) { thread.activeMetadataTokens(colors, activeMetadata).firstOrNull() }
        else -> null
    }
    val showQuestion = needsAnswer || hasQuestions
    val relativeTime = SidebarRelativeTimeFormatter.compactLabel(thread)
    ListItem(
        selected = selected,
        onClick = onSelect,
        onLongClick = onActionsRequest ?: onRenameRequest,
        onLongClickLabel = stringResource(R.string.sidebar_thread_actions_cd),
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent, contentColor = colors.primaryText,
            selectedContainerColor = colors.selectedRow, selectedContentColor = colors.primaryText,
            trailingContentColor = colors.mutedText, selectedTrailingContentColor = colors.mutedText),
        supportingContent = subtitle?.let {
            { Text(it.text, style = MaterialTheme.typography.labelSmall, color = it.color,
                maxLines = 1, overflow = TextOverflow.Ellipsis) }
        },
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(thread.displayTitle, modifier = Modifier.weight(1f).alignByBaseline(),
                style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (relativeTime != null || showQuestion || isRunning || isUnread) {
                // Align the whole status group using the time's first baseline, including on wrapped titles.
                Row(modifier = if (relativeTime != null) Modifier.alignByBaseline() else Modifier.align(Alignment.Top),
                    horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (showQuestion) QuestionReplyIndicator(onAnswerRequest ?: onSelect)
                    if (isRunning) SidebarThreadRunBadge(colors.secondaryText)
                    if (isUnread) {
                        val description = stringResource(R.string.sidebar_unread_badge)
                        Badge(modifier = Modifier.semantics { contentDescription = description }, containerColor = colors.unread)
                    }
                    relativeTime?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = colors.mutedText,
                            maxLines = 1, softWrap = false)
                    }
                }
            }
        }
    }
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
