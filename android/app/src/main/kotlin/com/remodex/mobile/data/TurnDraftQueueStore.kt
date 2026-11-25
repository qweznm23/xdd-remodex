package com.remodex.mobile.data

import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexFileAttachment
import com.remodex.mobile.core.model.CodexTurnMention
import com.remodex.mobile.core.model.CodexTurnSkillMention

/**
 * Original editable content retained while synchronizing with the host queue.
 */
@kotlinx.serialization.Serializable
data class QueuedTurnDraft(
    val id: String,
    val text: String,
    val attachments: List<CodexImageAttachment>,
    val skillMentions: List<CodexTurnSkillMention>,
    val fileMentions: List<CodexTurnMention>,
    val collaborationMode: CodexCollaborationModeKind?,
    val composerText: String? = null,
    val files: List<CodexFileAttachment> = emptyList(),
    val editable: Boolean = true,
)

data class QueuedTurnDraftPreview(
    val id: String,
    val text: String,
    val attachmentCount: Int,
    val collaborationMode: CodexCollaborationModeKind?,
    val waiting: Boolean = false,
    val recovery: Boolean = false,
    val editable: Boolean = true,
)
