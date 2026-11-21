package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.remodex.mobile.R
import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.data.CodexRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun ThreadGoalDialog(repository: CodexRepository, threadId: String, onUnsupported: () -> Unit, close: () -> Unit) {
    key(threadId) {
        val scope = rememberCoroutineScope()
        var busy by remember { mutableStateOf(true) }
        var loaded by remember { mutableStateOf(false) }
        var problem by remember { mutableStateOf<String?>(null) }
        var goal by remember { mutableStateOf<Map<String, JSONValue>?>(null) }
        var objective by remember { mutableStateOf("") }
        var budget by remember { mutableStateOf("") }
        var dirty by remember { mutableStateOf(false) }
        fun display(row: Map<String, JSONValue>?) {
            goal = row
            if (!dirty) {
                objective = row?.get("objective")?.stringValue.orEmpty()
                budget = row?.get("tokenBudget")?.longValue?.toString().orEmpty()
            }
        }
        suspend fun request(method: String, patch: Map<String, JSONValue> = emptyMap()) {
            busy = true
            try {
                val params = patch + ("threadId" to JSONValue.Str(threadId))
                val body = repository.sendRequest(method, JSONValue.Obj(params)).result?.objectValue ?: error("$method missing result")
                val row = if (method == "thread/goal/clear") {
                    check(body["cleared"]?.boolValue != null) { "$method missing cleared" }
                    null
                } else {
                    val value = body["goal"] ?: error("$method missing goal")
                    if (value == JSONValue.Null && method == "thread/goal/get") null
                    else requireNotNull(value.objectValue?.takeIf { it["threadId"]?.stringValue == threadId }) { "$method invalid goal" }
                }
                dirty = false
                display(row)
                loaded = true
                problem = null
            } catch (cancel: CancellationException) { throw cancel }
            catch (failure: Exception) {
                val rpc = (failure as? com.remodex.mobile.core.error.CodexServiceError.RpcFailure)?.rpcError
                if (rpc?.code == -32601 || rpc?.message?.contains("goals feature is disabled", ignoreCase = true) == true) onUnsupported()
                android.util.Log.e("RemodexGoals", method, failure)
                problem = formatTurnSendError(failure)
            } finally { busy = false }
        }
        fun change(method: String, patch: Map<String, JSONValue> = emptyMap()) {
            if (busy) return
            busy = true
            scope.launch { request(method, patch) }
        }
        LaunchedEffect(repository, threadId) { request("thread/goal/get") }
        LaunchedEffect(repository, threadId) {
            repository.conversationEvents.collect { (method, params) ->
                if (params["threadId"]?.stringValue == threadId) when (method) {
                    "thread/goal/updated" -> display(params["goal"]?.objectValue)
                    "thread/goal/cleared" -> display(null)
                }
            }
        }
        RemodexAlertDialog(onDismissRequest = close, title = { Text(stringResource(R.string.thread_goal)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    if (busy) LinearProgressIndicator()
                    problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    if (!loaded && !busy) TextButton(onClick = { change("thread/goal/get") }) { Text(stringResource(R.string.goal_retry)) }
                    if (loaded) {
                        goal?.let { row ->
                            Text(stringResource(R.string.goal_status, row["status"]?.stringValue.orEmpty(), row["tokensUsed"]?.longValue ?: 0L))
                        }
                        OutlinedTextField(value = objective, onValueChange = { objective = it; dirty = true }, enabled = !busy,
                            label = { Text(stringResource(R.string.goal_objective)) })
                        OutlinedTextField(value = budget, onValueChange = { budget = it; dirty = true }, enabled = !busy, singleLine = true,
                            isError = budget.isNotBlank() && (budget.toLongOrNull() ?: 0L) <= 0,
                            label = { Text(stringResource(R.string.goal_budget)) })
                        if (goal != null) Row {
                            val paused = goal?.get("status")?.stringValue == "paused"
                            TextButton(enabled = !busy && !dirty, onClick = {
                                change("thread/goal/set", mapOf("status" to JSONValue.Str(if (paused) "active" else "paused")))
                            }) { Text(stringResource(if (paused) R.string.goal_resume else R.string.goal_pause)) }
                            TextButton(enabled = !busy, onClick = { change("thread/goal/clear") }) { Text(stringResource(R.string.goal_clear)) }
                        }
                    }
                }
            }, confirmButton = {
                TextButton(enabled = loaded && !busy && objective.isNotBlank() && dirty && (budget.isBlank() || (budget.toLongOrNull() ?: 0L) > 0),
                    onClick = { change("thread/goal/set", mapOf("objective" to JSONValue.Str(objective.trim()),
                        "tokenBudget" to (budget.toLongOrNull()?.let(JSONValue::NumLong) ?: JSONValue.Null))) }) { Text(stringResource(R.string.goal_save)) }
            }, dismissButton = { TextButton(onClick = close) { Text(stringResource(android.R.string.cancel)) } })
    }
}

/** Keep the status visible only while a goal exists; a live update wins over an older in-flight read. */
@Composable
internal fun ActiveGoalButton(repository: CodexRepository, threadId: String, open: () -> Unit, unsupported: () -> Unit) {
    var goal by remember(repository, threadId) { mutableStateOf<Map<String, JSONValue>?>(null) }
    LaunchedEffect(repository, threadId) {
        var revision = 0
        val listener = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            repository.conversationEvents.collect { (method, params) ->
                if (params["threadId"]?.stringValue == threadId) when (method) {
                    "thread/goal/updated" -> { revision++; goal = params["goal"]?.objectValue }
                    "thread/goal/cleared" -> { revision++; goal = null }
                }
            }
        }
        try {
            val value = repository.sendRequest("thread/goal/get", JSONValue.Obj(mapOf("threadId" to JSONValue.Str(threadId))))
                .result?.objectValue?.get("goal") ?: error("thread/goal/get missing goal")
            if (revision == 0) goal = if (value == JSONValue.Null) null else requireNotNull(value.objectValue)
        } catch (cancel: CancellationException) { throw cancel }
        catch (error: Exception) {
            val rpc = (error as? com.remodex.mobile.core.error.CodexServiceError.RpcFailure)?.rpcError
            if (rpc?.code == -32601 || rpc?.message?.contains("goals feature is disabled", ignoreCase = true) == true) unsupported()
            else android.util.Log.w("RemodexGoals", "read status thread=$threadId", error)
        }
        listener.join()
    }
    goal?.let { row ->
        val status = when (val raw = row["status"]?.stringValue) {
            "active" -> stringResource(R.string.goal_state_active)
            "paused" -> stringResource(R.string.goal_state_paused)
            "complete", "completed" -> stringResource(R.string.goal_state_done)
            else -> raw.orEmpty()
        }
        TextButton(onClick = open, contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 0.dp)) {
            Text(stringResource(R.string.goal_inline, status, row["objective"]?.stringValue.orEmpty()), maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }
    }
}
