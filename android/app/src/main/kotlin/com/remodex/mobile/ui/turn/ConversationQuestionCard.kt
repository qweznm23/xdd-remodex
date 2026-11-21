package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.QuestionAnswer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.core.model.*
import com.remodex.mobile.data.ConversationQuestionCodec
import com.remodex.mobile.ui.LocalCodexRepository
import com.remodex.mobile.ui.shell.StructuredInputDialog

@Composable
private fun currentConversationQuestions(threadId: String): List<CodexStructuredUserInputRequest> {
    val repository = LocalCodexRepository.current
    val pending by repository.pendingStructuredInputRequests.collectAsStateWithLifecycle()
    val indexed by repository.conversationQuestionsByThread.collectAsStateWithLifecycle()
    return remember(threadId, pending, indexed[threadId]) {
        (pending.values.filter { it.threadId == threadId }.map(ConversationQuestionCodec::fromPending) +
            indexed[threadId].orEmpty().filter { it.isAnswerableAsync }).distinctBy { it.identity }
    }
}

/** Shared direct-to-form entry for the conversation, inline row and sidebar. */
@Composable
internal fun ConversationQuestionDialog(threadId: String, open: Boolean, onDismiss: () -> Unit,
    selected: CodexStructuredUserInputRequest? = null, allowHistorical: Boolean = false) {
    val repository = LocalCodexRepository.current
    val requests = currentConversationQuestions(threadId)
    val indexed by repository.conversationQuestionsByThread.collectAsStateWithLifecycle()
    val openedRequest = remember(threadId, open, selected?.identity) {
        if (open) selected ?: requests.firstOrNull() else null
    }
    val live = requests.firstOrNull {
        openedRequest != null && it.identity == openedRequest.identity && it.questions == openedRequest.questions
    }
    val request = live ?: if (allowHistorical) indexed[threadId].orEmpty().firstOrNull {
        it.canReplyFromHistory && openedRequest != null && it.identity == openedRequest.identity && it.questions == openedRequest.questions
    } else null
    val historical = live == null
    val stateHolder = rememberSaveableStateHolder()
    LaunchedEffect(open, request?.identity) { if (open && request == null) onDismiss() }
    if (!open || request == null) return
    val formKey = "$threadId:${request.identity}"
    stateHolder.SaveableStateProvider(formKey) {
        StructuredInputDialog(
            request = ConversationQuestionCodec.asPending(threadId, request.copy(questions = request.questions.filter { it.id !in request.answers })),
            onSubmit = { repository.answerConversationQuestions(threadId, request, it, allowHistorical = historical); stateHolder.removeState(formKey); onDismiss() },
            onSkip = if (historical) null else ({ repository.skipConversationQuestions(threadId, request); stateHolder.removeState(formKey); onDismiss() }),
            onDismiss = onDismiss,
        )
    }
}

/** Old unanswered questions open only on an explicit tap; completed records remain read-only. */
@Composable
internal fun ConversationQuestionCard(threadId: String, request: CodexStructuredUserInputRequest, modifier: Modifier = Modifier) {
    val requests = currentConversationQuestions(threadId)
    val active = requests.any { it.identity == request.identity && it.questions == request.questions }
    var expanded by rememberSaveable(threadId, request.identity) { mutableStateOf(false) }
    var open by rememberSaveable(threadId, request.identity) { mutableStateOf(false) }
    val label = when {
        active -> R.string.questions_answer
        ConversationQuestionCodec.isSkipped(request) -> R.string.questions_skipped
        request.submitted -> R.string.questions_answered
        else -> R.string.questions_history
    }
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        if (active) {
            val next = request.questions.firstOrNull { it.id !in request.answers }
            next?.let {
                Text(it.question.ifBlank { it.header }, modifier = Modifier.fillMaxWidth(),
                    style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        TextButton(
            onClick = { if (active || request.canReplyFromHistory) open = true else expanded = !expanded },
            colors = ButtonDefaults.textButtonColors(
                contentColor = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        ) {
            Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
            Icon(if (expanded && !active) Icons.Outlined.ExpandMore else Icons.Outlined.ChevronRight, null,
                modifier = Modifier.size(16.dp))
        }
        if (expanded && !active) request.questions.forEach { question ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(question.question.ifBlank { question.header }, style = MaterialTheme.typography.bodyMedium)
                question.options.forEach { Text(it.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                request.answers[question.id]?.let { answers ->
                    Text(if (question.isSecret) "••••" else answers.joinToString("\n"),
                        modifier = Modifier.align(Alignment.End), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    ConversationQuestionDialog(threadId, open, { open = false }, selected = request, allowHistorical = true)
}

@Composable
internal fun ConversationQuestionAction(threadId: String?, fallback: @Composable () -> Unit) {
    if (threadId == null) { fallback(); return }
    val requests = currentConversationQuestions(threadId)
    var open by rememberSaveable(threadId) { mutableStateOf(false) }
    if (requests.isEmpty()) {
        fallback()
    } else {
        val count = requests.sumOf { request -> request.questions.count { it.id !in request.answers } }
        val label = stringResource(R.string.questions_action)
        val description = if (count > 1) "$label · " + stringResource(R.string.questions_count, count) else label
        IconButton(onClick = { open = true }) {
            BadgedBox(badge = { if (count > 1) Badge { Text(count.toString()) } else Badge() }) {
                Icon(Icons.Outlined.QuestionAnswer, description)
            }
        }
    }
    ConversationQuestionDialog(threadId, open, { open = false })
}

@Composable
internal fun QuestionReplyIndicator(onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(Icons.Outlined.QuestionAnswer, stringResource(R.string.questions_action), Modifier.size(16.dp))
    }
}
