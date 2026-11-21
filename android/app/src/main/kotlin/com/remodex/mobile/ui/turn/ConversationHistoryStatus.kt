package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.model.CodexMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

internal data class HistoryPresentationReceipt(val threadId: String, val revision: Long)
internal val LocalHistoryPresentationReceipt = compositionLocalOf<(HistoryPresentationReceipt) -> Unit> { {} }

/** A refresh acknowledges its history snapshot, not an ever-moving live token stream. */
internal fun hasPresentedHistory(expected: List<CodexMessage>, presented: List<CodexMessage>): Boolean {
    if (expected.isEmpty()) return true
    val byId = presented.associateBy { it.id }
    return expected.all { row ->
        val shown = byId[row.id]
        shown != null && (shown.text == row.text || shown.text.startsWith(row.text) ||
            (!row.isAssistantMessageComplete && shown.isAssistantMessageComplete))
    }
}

internal fun hasPresentedHistoryWindow(
    receivedTail: CodexMessage?,
    expectedWindow: List<CodexMessage>,
    sampledMessages: List<CodexMessage>,
    visibleMessages: List<CodexMessage>,
    presentedMessages: List<CodexMessage>,
): Boolean {
    // Sampling must first reach the received page. Merely displaying an old overlapping row
    // cannot acknowledge a refresh that added newer items.
    if (receivedTail != null) {
        val sampledTail = sampledMessages.lastOrNull { it.id == receivedTail.id } ?: return false
        if (!hasPresentedHistory(listOf(receivedTail), listOf(sampledTail))) return false
    }
    if (visibleMessages.firstOrNull()?.id != presentedMessages.firstOrNull()?.id) return false
    val visibleIds = visibleMessages.mapTo(hashSetOf()) { it.id }
    // New live rows can push old history out of the bounded render window. Do not wait forever
    // for those deliberately hidden rows to be rendered again.
    return hasPresentedHistory(expectedWindow.filter { it.id in visibleIds }, presentedMessages)
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
internal suspend fun awaitHistoryPresentation(
    receipts: Flow<HistoryPresentationReceipt?>,
    threadId: String,
    revision: Long,
    timeoutMillis: Long = 15_000,
    renderingEnabled: Flow<Boolean> = flowOf(true),
) {
    renderingEnabled.distinctUntilChanged().flatMapLatest { visible ->
        if (!visible) emptyFlow() else flow {
            val received = withTimeoutOrNull(timeoutMillis) {
                receipts.first { it?.threadId == threadId && it.revision >= revision }
                true
            }
            if (received == null) throw TimeoutException("History presentation timed out stage=history-presentation thread=$threadId revision=$revision timeoutMs=$timeoutMillis")
            emit(Unit)
        }
    }.first()
}

internal enum class HistoryLoadingPhase { Connecting, Loading, Preparing, Failed, Ready }

internal fun historyLoadingPhase(connected: Boolean, loading: Boolean, loaded: Boolean, presented: Boolean, error: String?): HistoryLoadingPhase = when {
    loading -> HistoryLoadingPhase.Loading
    !loaded && !error.isNullOrBlank() -> HistoryLoadingPhase.Failed
    !loaded && !connected -> HistoryLoadingPhase.Connecting
    !loaded -> HistoryLoadingPhase.Loading
    !presented -> HistoryLoadingPhase.Preparing
    else -> HistoryLoadingPhase.Ready
}

@Composable
internal fun ConversationHistoryStatus(phase: HistoryLoadingPhase, error: String?, canRetry: Boolean, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    if (phase == HistoryLoadingPhase.Ready) return
    var details by remember(error) { mutableStateOf(false) }
    Surface(modifier, shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (phase == HistoryLoadingPhase.Loading || phase == HistoryLoadingPhase.Preparing) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                Text(stringResource(when (phase) {
                    HistoryLoadingPhase.Connecting -> R.string.history_waiting_connection
                    HistoryLoadingPhase.Preparing -> R.string.history_preparing
                    HistoryLoadingPhase.Failed -> R.string.history_load_failed
                    else -> R.string.history_loading
                }), style = MaterialTheme.typography.bodyMedium)
            }
            if (phase == HistoryLoadingPhase.Failed) Row {
                TextButton(onClick = onRetry, enabled = canRetry) { Text(stringResource(R.string.history_retry)) }
                TextButton(onClick = { details = true }) { Text(stringResource(R.string.history_error_details)) }
            }
        }
    }
    if (details) RemodexAlertDialog(
        onDismissRequest = { details = false },
        title = { Text(stringResource(R.string.history_load_failed)) },
        text = { Text(error.orEmpty()) },
        confirmButton = { TextButton(onClick = { details = false }) { Text(stringResource(R.string.history_close)) } },
    )
}
