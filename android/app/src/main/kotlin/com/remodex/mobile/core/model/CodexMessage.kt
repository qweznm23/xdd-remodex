package com.remodex.mobile.core.model

import java.time.Instant
import java.util.UUID
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class CodexMessageRole {
    user,
    assistant,
    system,
}

@Serializable
enum class CodexMessageDeliveryState {
    pending,
    confirmed,
    failed,
}

@Serializable
enum class CodexMessageKind {
    chat,
    thinking,
    @SerialName("fileChange")
    fileChange,
    @SerialName("commandExecution")
    commandExecution,
    @SerialName("subagentAction")
    subagentAction,
    plan,
    /** Structured question card; persisted metadata never grants authority to answer a stale RPC. */
    @SerialName("userInputPrompt")
    userInputPrompt,

    /** Ephemeral marker when the bridge asks for approval (J.7b); not persisted; dialog remains authoritative. */
    @SerialName("pendingApproval")
    pendingApproval,
}

@Serializable
data class CodexMessage(
    val id: String = UUID.randomUUID().toString(),
    val threadId: String,
    val role: CodexMessageRole,
    val kind: CodexMessageKind = CodexMessageKind.chat,
    val assistantPhase: String? = null,
    val text: String,
    @Serializable(Iso8601InstantSerializer::class)
    val createdAt: Instant,
    val turnId: String? = null,
    val itemId: String? = null,
    val isStreaming: Boolean = false,
    /** A complete assistant item was received; stopping animation alone does not imply this. */
    val isAssistantMessageComplete: Boolean = false,
    val deliveryState: CodexMessageDeliveryState = CodexMessageDeliveryState.confirmed,
    val attachments: List<CodexImageAttachment> = emptyList(),
    val planState: CodexPlanState? = null,
    val subagentAction: CodexSubagentAction? = null,
    val structuredUserInputRequest: CodexStructuredUserInputRequest? = null,
    /** Rollout user events and canonical history items have independent identifiers. */
    val userMirrorId: String? = null,
    val orderIndex: Int = CodexMessageOrderCounter.next(),
    /** Only explicitly public summary parts, indexed independently of raw reasoning and tool status. */
    val reasoningSummary: Map<Int, String> = emptyMap(),
    val reasoningSummaryFinal: Boolean = false,
    /** In-process presentation time, never part of history or the wire format. */
    @kotlinx.serialization.Transient val localSendNanos: Long? = null,
)

internal fun CodexMessage.hasConversationContent(): Boolean =
    structuredUserInputRequest?.questions?.isNotEmpty() == true || role != CodexMessageRole.system && kind == CodexMessageKind.chat &&
        (text.isNotBlank() || attachments.isNotEmpty())

internal fun CodexMessage.visibleSummaryParts(): List<String> =
    reasoningSummary.toSortedMap().values.flatMap { part ->
        part.split("<!-- -->").mapNotNull { section ->
            section.trim().removeSurrounding("**").trim().takeIf { it.isNotEmpty() }
        }
    }
