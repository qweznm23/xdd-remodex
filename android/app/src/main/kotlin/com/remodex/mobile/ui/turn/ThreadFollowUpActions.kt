package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.ThreadFollowUp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@Composable
internal fun ThreadFollowUpActions(repository: CodexRepository, threadId: String, onError: (String) -> Unit) {
    val flow = remember(repository, threadId) { repository.threadFollowUps.map { it[threadId].orEmpty() }.distinctUntilChanged() }
    val rows by flow.collectAsStateWithLifecycle(emptyList())
    var selected by remember(threadId) { mutableStateOf<String?>(null) }
    var busy by remember(threadId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    fun perform(row: ThreadFollowUp) {
        if (busy) return
        busy = true
        scope.launch {
            try { repository.performFollowUp(threadId, row); selected = null }
            catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                android.util.Log.e("RemodexFollowUp", if (row.review) "guardian retry" else "stream continuation", failure)
                onError(formatTurnSendError(failure))
            } finally { busy = false }
        }
    }
    val visible = rows.filter { it.status != "approved" && (it.review || it.canAct) }
    for (row in visible) {
        val title = stringResource(when (row.status) {
            "denied" -> R.string.review_denied
            "inProgress" -> R.string.review_running
            "timedOut", "aborted" -> R.string.review_stopped
            "failed" -> R.string.stream_interrupted
            else -> R.string.review_details
        })
        TextButton(enabled = !busy, onClick = { selected = row.id }) { Text(title) }
        if (selected == row.id) RemodexAlertDialog(onDismissRequest = { selected = null },
            title = { Text(title) }, text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (row.actionText.isNotEmpty()) Text(row.actionText)
                    if (row.detail.isNotEmpty()) Text(row.detail)
                }
            }, confirmButton = {
                if (row.canAct) TextButton(enabled = !busy, onClick = { perform(row) }) {
                    Text(stringResource(if (row.review) R.string.review_allow_retry else R.string.stream_continue))
                }
            }, dismissButton = { TextButton(onClick = { selected = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
