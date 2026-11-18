package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexCollaborationModeKind
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexTurnMention
import com.remodex.mobile.core.model.CodexTurnSkillMention

/** Keep the complete wire payload; retry uses the current connection and thread runtime state. */
internal data class FailedTurnSend(
    val text: String,
    val attachments: List<CodexImageAttachment>,
    val skillMentions: List<CodexTurnSkillMention>,
    val fileMentions: List<CodexTurnMention>,
    val collaborationMode: CodexCollaborationModeKind?,
    val requestId: String,
    val attempt: Int,
)
