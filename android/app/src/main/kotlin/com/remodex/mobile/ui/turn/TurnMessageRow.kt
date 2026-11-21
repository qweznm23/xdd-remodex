package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.chatLayers

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentWidth
import com.remodex.mobile.ui.theme.conversationColors
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.visibleSummaryParts
import com.remodex.mobile.core.model.CodexMessageDeliveryState
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.CommandExecutionDetails
import com.remodex.mobile.core.model.TurnThinkingDisclosureHints
import com.remodex.mobile.core.model.UserBubbleColor
import com.remodex.mobile.ui.LocalUserBubbleColor
import com.remodex.mobile.ui.agent.FileEditRow
import com.remodex.mobile.ui.agent.ToolCallRow
import com.remodex.mobile.ui.theme.readableLinkOn
import com.remodex.mobile.ui.theme.bubbleBackground
import com.remodex.mobile.ui.theme.bubbleForeground
import com.remodex.mobile.ui.agent.containsOnlyQuestionPrompt
import com.remodex.mobile.ui.theme.isAgentLightChrome
import com.remodex.mobile.ui.theme.mentionForeground
import kotlinx.coroutines.launch

/**
 * Timeline turn row: chat + dedicated layouts per [CodexMessageKind] (J.4/J.6).
 */
@Composable
fun TurnMessageRow(
    message: CodexMessage,
    modifier: Modifier = Modifier,
    commandExecutionDetails: CommandExecutionDetails? = null,
    onOpenFullMessage: ((CodexMessage) -> Unit)? = null,
    onOpenPlanDetails: ((CodexMessage) -> Unit)? = null,
    onApplyPlan: ((CodexMessage) -> Unit)? = null,
    canApplyPlan: Boolean = true,
) {
    if (message.kind == CodexMessageKind.thinking) {
        val parts = remember(message.reasoningSummary) { message.visibleSummaryParts() }
        if (parts.isNotEmpty()) ReasoningSummaryRow(parts, modifier)
        return
    }
    if (message.structuredUserInputRequest != null && remember(message) { message.containsOnlyQuestionPrompt() }) {
        ConversationQuestionCard(message.threadId, message.structuredUserInputRequest, modifier)
        return
    }
    val missionScope = rememberCoroutineScope()
    val isTimelineToolRow =
        message.role == CodexMessageRole.system &&
            (message.kind == CodexMessageKind.commandExecution ||
                message.kind == CodexMessageKind.fileChange)
    val horizontalAlignment =
        when (message.role) {
            CodexMessageRole.user -> Alignment.End
            CodexMessageRole.assistant -> Alignment.Start
            CodexMessageRole.system ->
                if (isTimelineToolRow) {
                    Alignment.Start
                } else {
                    Alignment.CenterHorizontally
                }
        }
    val colors = MaterialTheme.colorScheme
    val isLightChrome = isAgentLightChrome()
    val userBubbleColor = LocalUserBubbleColor.current
    val bubbleColor =
        when (message.role) {
            CodexMessageRole.user -> userBubbleColor.bubbleBackground(colors, isLightChrome)
            CodexMessageRole.assistant -> colors.surface.copy(alpha = 0.0f)
            CodexMessageRole.system ->
                when (message.kind) {
                    CodexMessageKind.thinking -> Color.Transparent
                    CodexMessageKind.fileChange -> Color.Transparent
                    CodexMessageKind.commandExecution -> Color.Transparent
                    CodexMessageKind.subagentAction -> Color.Transparent
                    CodexMessageKind.plan -> Color.Transparent
                    CodexMessageKind.pendingApproval,
                    CodexMessageKind.userInputPrompt,
                    ->
                        colors.secondaryContainer.copy(alpha = 0.35f)
                    else -> colors.secondaryContainer.copy(alpha = 0.25f)
                }
        }
    val onBubble =
        when (message.role) {
            CodexMessageRole.user -> userBubbleColor.bubbleForeground(colors, isLightChrome)
            CodexMessageRole.assistant -> colors.onBackground
            CodexMessageRole.system -> colors.onSurfaceVariant
        }
    val markdownLinkColor =
        if (message.role == CodexMessageRole.user) {
            userBubbleColor.mentionForeground(colors, isLightChrome, onBubble)
        } else {
            remember(colors) { colors.readableLinkOn(colors.conversationColors().reading) }
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment,
    ) {
        Surface(
            shape = if (message.role == CodexMessageRole.user)
                androidx.compose.foundation.shape.RoundedCornerShape(16.dp, 16.dp, 6.dp, 16.dp)
            else MaterialTheme.shapes.medium,
            color = bubbleColor,
            border = if (message.role == CodexMessageRole.user && userBubbleColor == UserBubbleColor.default) {
                androidx.compose.foundation.BorderStroke(0.5.dp, MaterialTheme.colorScheme.chatLayers(isLightChrome).outgoingEdge)
            } else null,
            modifier =
                if (isTimelineToolRow) {
                    Modifier
                        .fillMaxWidth(0.96f)
                        .widthIn(max = 620.dp)
                } else if (message.role == CodexMessageRole.user) {
                    Modifier.widthIn(max = 620.dp).fillMaxWidth(0.86f).wrapContentWidth(Alignment.End)
                        .then(outgoingBubbleMotion(message.localSendNanos))
                } else {
                    Modifier.widthIn(max = 620.dp)
                        .fillMaxWidth(if (message.role == CodexMessageRole.assistant) 1f else 0.96f)
                },
        ) {
            Column(
                modifier =
                    Modifier.padding(
                        horizontal =
                            when (message.role) {
                                CodexMessageRole.user -> 12.dp
                                CodexMessageRole.assistant -> 0.dp
                                CodexMessageRole.system -> 12.dp
                            },
                        vertical =
                            when (message.role) {
                                CodexMessageRole.user -> 10.dp
                                CodexMessageRole.assistant -> 8.dp
                                CodexMessageRole.system -> 8.dp
                            },
                    ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val kindLabel = systemKindLabel(message)
                if (kindLabel != null) {
                    Text(
                        text = kindLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = onBubble.copy(alpha = 0.88f),
                    )
                }
                if (message.role != CodexMessageRole.system && message.attachments.isNotEmpty()) {
                    MessageAttachmentStrip(attachments = message.attachments)
                }
                val directiveOutcome =
                    TurnDirectiveRenderCache.parseCodeCommentDirectives(message.id, message.visibleAssistantText())
                val bodyMarkdown =
                    timelineBodyText(
                        message = message,
                        skipThinkingHeadline = kindLabel != null,
                        cleanedDirectiveText = directiveOutcome.cleanedText,
                        hasDirectiveFindings = directiveOutcome.hasFindings,
                    ).let { if (message.role == CodexMessageRole.user) com.remodex.mobile.data.ConversationQuestionCodec.displayReply(it) else it }
                val preparedBody = remember(message, bodyMarkdown) { prepareTimelineBody(message, bodyMarkdown) }
                val proposedPlanExtraction = preparedBody.first
                val cappedBody = preparedBody.second
                val fitProse = message.role == CodexMessageRole.user && message.kind == CodexMessageKind.chat
                val bodyModifier = if (fitProse) Modifier else Modifier.fillMaxWidth()
                when (message.kind) {
                    CodexMessageKind.fileChange -> {
                        FileEditRow(
                            message = message,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CodexMessageKind.commandExecution -> {
                        ToolCallRow(
                            message = message,
                            contentColor = onBubble,
                            details = commandExecutionDetails,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CodexMessageKind.subagentAction -> {
                        TurnSubagentActionCard(
                            message = message,
                            contentColor = onBubble,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    CodexMessageKind.plan -> {
                        PlanMarkdownPreview(
                            message = message,
                            contentColor = onBubble,
                            onSeeMore = onOpenPlanDetails?.let { open -> { open(message) } },
                            onApplyPlan = onApplyPlan?.let { apply -> { apply(message) } },
                            canApplyPlan = canApplyPlan,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    else -> {
                        if (cappedBody.text.isNotEmpty()) {
                            if (shouldRenderMarkdownBody(message)) {
                                TurnRichMarkdownBody(
                                    markdown = cappedBody.text,
                                    contentColor = onBubble,
                                    modifier = bodyModifier,
                                    keyPrefix = message.id,
                                    linkColor = markdownLinkColor,
                                    fitProse = fitProse,
                                    assistantProse = message.role == CodexMessageRole.assistant && message.kind == CodexMessageKind.chat,
                                )
                            } else {
                                Text(
                                    text = cappedBody.text,
                                    style =
                                        if (message.role == CodexMessageRole.system && message.kind == CodexMessageKind.thinking) {
                                            MaterialTheme.typography.bodySmall
                                        } else {
                                            MaterialTheme.typography.bodyMedium
                                        },
                                    color = onBubble,
                                    modifier = bodyModifier,
                                )
                            }
                            if (cappedBody.truncated && onOpenFullMessage != null) {
                                TextButton(onClick = { onOpenFullMessage(message) }) {
                                    Text(stringResource(R.string.turn_message_see_more))
                                }
                            }
                        }
                        proposedPlanExtraction.plans.forEachIndexed { index, plan ->
                            val planMessage =
                                remember(message.id, message.threadId, message.createdAt, plan.markdown, index) {
                                    message.copy(
                                        id = "${message.id}-proposed-plan-$index",
                                        role = CodexMessageRole.system,
                                        kind = CodexMessageKind.plan,
                                        text = plan.markdown,
                                        isStreaming = false,
                                        planState = null,
                                    )
                                }
                            PlanMarkdownPreview(
                                message = planMessage,
                                contentColor = onBubble,
                                onSeeMore = onOpenPlanDetails?.let { open -> { open(planMessage) } },
                                onApplyPlan = onApplyPlan?.let { apply -> { apply(planMessage) } },
                                canApplyPlan = canApplyPlan,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        if (message.role == CodexMessageRole.assistant && message.kind == CodexMessageKind.chat) {
                            TurnSearchCitationAccessory(
                                markdown = bodyMarkdown,
                                isStreaming = message.isStreaming,
                                modifier = Modifier.fillMaxWidth(),
                                onExpand = {
                                    missionScope.launch {
                                        AppContainer.betaEngagementRepository.recordMissionEvent(
                                            eventType = "searches_expanded",
                                            screen = "conversation",
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                if (directiveOutcome.findings.isNotEmpty()) {
                    TurnCodeCommentDirectiveCards(
                        findings = directiveOutcome.findings,
                        contentColor = onBubble,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (message.kind == CodexMessageKind.pendingApproval) {
                    Text(
                        text = stringResource(R.string.turn_timeline_pending_approval_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = onBubble.copy(alpha = 0.72f),
                    )
                }
                message.structuredUserInputRequest?.let { request ->
                    ConversationQuestionCard(message.threadId, request)
                }
                if (message.kind == CodexMessageKind.userInputPrompt && message.structuredUserInputRequest == null) {
                    Text(
                        text = stringResource(R.string.turn_timeline_structured_input_hint),
                        style = MaterialTheme.typography.labelSmall,
                        color = onBubble.copy(alpha = 0.72f),
                    )
                }
                userDeliveryCaption(message)?.let { cap ->
                    Text(
                        text = cap,
                        style = MaterialTheme.typography.labelSmall,
                        color =
                            when (message.deliveryState) {
                                CodexMessageDeliveryState.failed -> MaterialTheme.colorScheme.error
                                else -> onBubble.copy(alpha = 0.75f)
                            },
                    )
                }
            }
        }
    }
}

private const val USER_MESSAGE_INLINE_MAX_CHARS = 1_200
private const val USER_MESSAGE_INLINE_MAX_LINES = 12
private const val ASSISTANT_MESSAGE_INLINE_MAX_LINES = 18
private const val PLAN_MESSAGE_INLINE_MAX_CHARS = 720
private const val PLAN_MESSAGE_INLINE_MAX_LINES = 5

private data class CappedTimelineBody(
    val text: String,
    val truncated: Boolean,
)

private fun prepareTimelineBody(message: CodexMessage, body: String): Pair<ProposedPlanExtraction, CappedTimelineBody> {
    val plans = if (message.role == CodexMessageRole.assistant && message.kind == CodexMessageKind.chat)
        ProposedPlanBlockParser.extract(body) else ProposedPlanExtraction(visibleMarkdown = body, plans = emptyList())
    val rendered = if (plans.plans.isNotEmpty()) plans.visibleMarkdown else body
    val displayed = if (message.structuredUserInputRequest?.questions?.any { it.question.trim() == rendered.trim() } == true ||
        message.kind == CodexMessageKind.userInputPrompt && message.structuredUserInputRequest != null) "" else rendered
    return plans to capTimelineBody(message, displayed)
}

/** Same body transformation as the row, for background preparation of settled chat history. */
internal fun settledTimelineMarkdown(message: CodexMessage): String? {
    if (message.isStreaming || message.kind != CodexMessageKind.chat || message.role == CodexMessageRole.system) return null
    val raw = TurnDirectiveRenderCache.parseCodeCommentDirectives(message.id, message.visibleAssistantText()).cleanedText.trim()
    if (raw.isEmpty()) return null // Localized empty/streaming placeholders are tiny and stay in the UI.
    val body = if (message.role == CodexMessageRole.user) com.remodex.mobile.data.ConversationQuestionCodec.displayReply(raw) else raw
    return prepareTimelineBody(message, body).second.text
}

private fun capTimelineBody(
    message: CodexMessage,
    text: String,
): CappedTimelineBody {
    val (maxChars, maxLines) =
        when {
            message.role == CodexMessageRole.user && message.kind == CodexMessageKind.chat ->
                USER_MESSAGE_INLINE_MAX_CHARS to USER_MESSAGE_INLINE_MAX_LINES
            message.role == CodexMessageRole.assistant && message.kind == CodexMessageKind.chat ->
                Int.MAX_VALUE to ASSISTANT_MESSAGE_INLINE_MAX_LINES
            message.kind == CodexMessageKind.plan ->
                PLAN_MESSAGE_INLINE_MAX_CHARS to PLAN_MESSAGE_INLINE_MAX_LINES
            else -> return CappedTimelineBody(text = text, truncated = false)
        }
    if (message.role == CodexMessageRole.assistant && message.kind == CodexMessageKind.chat &&
        (text.contains("```") || text.contains("~~~"))
    ) {
        val end = MarkdownFenceSegmentParser.closingFenceAtLimit(text, maxLines)
        if (end != null) return CappedTimelineBody(text.take(end), truncated = end < text.trimEnd().length)
    }
    return capTextForTimeline(text, maxChars = maxChars, maxLines = maxLines)
}

private fun capTextForTimeline(
    text: String,
    maxChars: Int,
    maxLines: Int,
): CappedTimelineBody {
    if (text.isBlank()) return CappedTimelineBody(text = text, truncated = false)
    val lineCount = text.count { it == '\n' } + 1
    val shouldTruncate = text.length > maxChars || lineCount > maxLines
    if (!shouldTruncate) return CappedTimelineBody(text = text, truncated = false)
    val byLines = text.lineSequence().take(maxLines).joinToString("\n")
    val clipped =
        if (byLines.length > maxChars) {
            byLines.take(maxChars)
        } else {
            byLines
        }
    return CappedTimelineBody(text = clipped.trimEnd() + "\n...", truncated = true)
}

@Composable
private fun PlanMarkdownPreview(
    message: CodexMessage,
    contentColor: androidx.compose.ui.graphics.Color,
    onSeeMore: (() -> Unit)?,
    onApplyPlan: (() -> Unit)? = null,
    canApplyPlan: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val lightChrome = isAgentLightChrome()
    val accent =
        if (lightChrome) {
            Color(0xFFE4C25F)
        } else {
            MaterialTheme.colorScheme.tertiary
        }
    val markdown = planMarkdownFromMessage(message)
    val preview = capTextForTimeline(markdown, maxChars = PLAN_MESSAGE_INLINE_MAX_CHARS, maxLines = PLAN_MESSAGE_INLINE_MAX_LINES)
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color =
            if (lightChrome) {
                Color(0xFF5A4312).copy(alpha = 0.12f)
            } else {
                Color(0xFF5A4312).copy(alpha = 0.24f)
            },
        border = BorderStroke(0.5.dp, accent.copy(alpha = 0.34f)),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Text(
                text = stringResource(R.string.turn_timeline_kind_plan),
                style = MaterialTheme.typography.labelMedium,
                color = accent,
            )
            TurnRichMarkdownBody(
                markdown = preview.text,
                contentColor = contentColor,
                modifier = Modifier.fillMaxWidth(),
                keyPrefix = "${message.id}-plan-preview",
            )
            if (onSeeMore != null || onApplyPlan != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if ((preview.truncated || message.planState?.steps.orEmpty().size > 0) && onSeeMore != null) {
                        TextButton(onClick = onSeeMore) {
                            Text(
                                text = stringResource(R.string.turn_message_see_more),
                                color = accent,
                            )
                        }
                    }
                    if (onApplyPlan != null) {
                        TextButton(
                            onClick = onApplyPlan,
                            enabled = canApplyPlan,
                        ) {
                            Text(
                                text = stringResource(R.string.turn_plan_apply_action),
                                color = accent,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Chat utente/assistant, plan e reasoning: markdown; diff/path comando restano testo semplice. */
private fun shouldRenderMarkdownBody(message: CodexMessage): Boolean {
    return when (message.kind) {
        CodexMessageKind.chat ->
            message.role == CodexMessageRole.user || message.role == CodexMessageRole.assistant
        CodexMessageKind.plan,
        CodexMessageKind.thinking,
        -> true
        CodexMessageKind.fileChange,
        CodexMessageKind.commandExecution,
        CodexMessageKind.subagentAction,
        CodexMessageKind.userInputPrompt,
        CodexMessageKind.pendingApproval,
        -> false
    }
}

@Composable
private fun systemKindLabel(message: CodexMessage): String? {
    if (message.role != CodexMessageRole.system) return null
    return when (message.kind) {
        CodexMessageKind.chat -> null
        CodexMessageKind.thinking -> null
        CodexMessageKind.fileChange -> null
        CodexMessageKind.commandExecution -> null
        CodexMessageKind.plan -> null
        CodexMessageKind.subagentAction -> stringResource(R.string.turn_timeline_kind_subagent)
        CodexMessageKind.userInputPrompt -> stringResource(R.string.turn_timeline_kind_prompt)
        CodexMessageKind.pendingApproval -> stringResource(R.string.turn_timeline_kind_pending_approval)
    }
}

@Composable
private fun userDeliveryCaption(message: CodexMessage): String? {
    if (message.role != CodexMessageRole.user) return null
    return when (message.deliveryState) {
        CodexMessageDeliveryState.pending -> stringResource(R.string.turn_delivery_sending)
        CodexMessageDeliveryState.failed -> stringResource(R.string.turn_delivery_failed)
        else -> null
    }
}

@Composable
private fun timelineBodyText(
    message: CodexMessage,
    skipThinkingHeadline: Boolean,
    cleanedDirectiveText: String,
    hasDirectiveFindings: Boolean,
): String {
    val raw =
        when (message.kind) {
            CodexMessageKind.thinking -> TurnThinkingDisclosureHints.stripSimpleThinkingTags(cleanedDirectiveText).trim()
            else -> cleanedDirectiveText.trim()
        }
    if (raw.isEmpty() && hasDirectiveFindings) return ""
    return when (message.kind) {
        CodexMessageKind.chat ->
            when {
                raw.isEmpty() && (message.attachments.isNotEmpty() || message.structuredUserInputRequest != null) -> ""
                raw.isNotEmpty() ->
                    if (message.isStreaming) {
                        "$raw ..."
                    } else {
                        raw
                    }
                message.isStreaming -> stringResource(R.string.turn_message_streaming_placeholder)
                else -> stringResource(R.string.turn_message_empty_placeholder)
            }
        CodexMessageKind.thinking ->
            when {
                skipThinkingHeadline && raw.isNotEmpty() ->
                    if (message.isStreaming) {
                        "$raw ..."
                    } else {
                        raw
                    }
                skipThinkingHeadline && message.isStreaming ->
                    stringResource(R.string.turn_message_streaming_placeholder)
                skipThinkingHeadline -> stringResource(R.string.turn_message_empty_placeholder)
                raw.isNotEmpty() -> stringResource(R.string.turn_message_thinking_with_text, raw)
                else -> stringResource(R.string.turn_message_thinking)
            }
        CodexMessageKind.fileChange,
        CodexMessageKind.commandExecution,
        CodexMessageKind.plan,
        ->
            when {
                raw.isNotEmpty() ->
                    if (message.isStreaming) {
                        "$raw ..."
                    } else {
                        raw
                    }
                message.isStreaming -> stringResource(R.string.turn_message_streaming_placeholder)
                else -> stringResource(R.string.turn_message_empty_placeholder)
            }
        CodexMessageKind.subagentAction,
        CodexMessageKind.userInputPrompt,
        ->
            if (raw.isNotEmpty()) {
                raw
            } else {
                stringResource(R.string.turn_timeline_kind_other)
            }
        CodexMessageKind.pendingApproval ->
            if (raw.isNotEmpty()) {
                raw
            } else {
                stringResource(R.string.turn_timeline_kind_pending_approval)
            }
    }
}
