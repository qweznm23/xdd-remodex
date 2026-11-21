package com.remodex.mobile.ui.shell

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.mapSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.remodex.mobile.ui.shared.DialogConfirmButton
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.remodex.mobile.R
import com.remodex.mobile.core.model.PendingStructuredInputQuestion
import com.remodex.mobile.core.model.PendingStructuredInputRequest
import kotlinx.coroutines.launch

/** One form: all questions, choices and free text are directly available in this dialog. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun StructuredInputDialog(
    request: PendingStructuredInputRequest,
    onSubmit: suspend (Map<String, List<String>>) -> Unit,
    onDismiss: () -> Unit,
    onSkip: (suspend () -> Unit)? = null,
) {
    val scope = rememberCoroutineScope()
    var busy by remember(request.id) { mutableStateOf(false) }
    var submitted by remember(request.id) { mutableStateOf(false) }
    var failure by remember(request.id) { mutableStateOf<Exception?>(null) }
    var showDetails by remember(request.id) { mutableStateOf(false) }
    val saver = mapSaver<androidx.compose.runtime.snapshots.SnapshotStateMap<String, String>>(
        save = { it.toMap() }, restore = { mutableStateMapOf<String, String>().apply { it.forEach { (k,v) -> put(k,v as String) } } })
    val typed = if (request.questions.any(::shouldMaskStructuredInput)) remember(request.id) { mutableStateMapOf<String,String>() }
        else rememberSaveable(request.id, saver = saver) { mutableStateMapOf<String,String>() }
    val selected = rememberSaveable(request.id, saver = saver) { mutableStateMapOf<String,String>() }
    val payload = buildStructuredInputAnswersPayload(request.questions, typed, selected)
    fun perform(operation: String, action: suspend () -> Unit) {
        if (busy || submitted) return
        busy = true; failure = null
        scope.launch {
            try { action(); submitted = true }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (error: Exception) {
                failure = error
                android.util.Log.e("RemodexQuestions", "operation=$operation request=${request.id} thread=${request.threadId} stage=question_form_send attempt=manual", error)
            } finally { busy = false }
        }
    }
    RemodexAlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(dismissOnBackPress = !busy, dismissOnClickOutside = !busy),
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.questions_title))
                    if (request.questions.size > 1) {
                        Text(stringResource(R.string.questions_count, request.questions.size), style = MaterialTheme.typography.labelMedium)
                    }
                }
                IconButton(onClick = onDismiss, enabled = !busy) {
                    Icon(Icons.Outlined.Close, stringResource(R.string.questions_close))
                }
            }
        },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                request.questions.forEachIndexed { index, question ->
                    key(question.id) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text((if (request.questions.size > 1) "${index + 1}. " else "") + question.question.ifBlank { question.header },
                                style = MaterialTheme.typography.titleSmall)
                            if (question.options.isNotEmpty()) {
                                Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    question.options.forEachIndexed { index, option ->
                                        val chosen = selected[question.id] == option.label && typed[question.id].isNullOrBlank()
                                        SegmentedListItem(
                                            selected = chosen,
                                            onClick = {
                                                selected[question.id] = option.label
                                                typed[question.id] = ""
                                            },
                                            enabled = !busy,
                                            shapes = ListItemDefaults.segmentedShapes(index, question.options.size),
                                            supportingContent = option.description?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
                                            leadingContent = { RadioButton(selected = chosen, onClick = null, enabled = !busy) },
                                        ) { Text(option.label) }
                                    }
                                }
                            }
                            OutlinedTextField(value = typed[question.id].orEmpty(),
                                onValueChange = { typed[question.id] = it; selected.remove(question.id) },
                                enabled = !busy, modifier = Modifier.fillMaxWidth(),
                                placeholder = { Text(stringResource(R.string.questions_input_hint), style = MaterialTheme.typography.bodyMedium) },
                                minLines = 1, maxLines = 5,
                                visualTransformation = if (shouldMaskStructuredInput(question)) PasswordVisualTransformation() else VisualTransformation.None,
                                keyboardOptions = KeyboardOptions(capitalization = if (shouldMaskStructuredInput(question)) KeyboardCapitalization.None else KeyboardCapitalization.Sentences,
                                    autoCorrectEnabled = !shouldMaskStructuredInput(question)))
                        }
                    }
                }
                failure?.let { error ->
                    Column {
                        Text(stringResource(R.string.questions_send_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(R.string.questions_error_details)) }
                        if (showDetails) Text(error.message ?: error.javaClass.name, style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.heightIn(max = 100.dp).verticalScroll(rememberScrollState()))
                    }
                }
            }
        },
        dismissButton = onSkip?.let { skip ->
            { TextButton(enabled = !busy && !submitted, onClick = { perform("skip", skip) }) {
                Text(stringResource(R.string.questions_skip))
            } }
        },
        confirmButton = {
            DialogConfirmButton(enabled = !busy && !submitted && payload != null,
                onClick = { payload?.let { perform("submit") { onSubmit(it) } } }) {
                if (busy) CircularProgressIndicator(Modifier.size(18.dp), color = LocalContentColor.current, strokeWidth = 2.dp)
                else Text(stringResource(R.string.structured_input_send))
            }
        },
    )
}

internal fun buildStructuredInputAnswersPayload(
    questions: List<PendingStructuredInputQuestion>,
    typedAnswersByQuestionId: Map<String, String>,
    selectedOptionByQuestionId: Map<String, String>,
): Map<String, List<String>>? {
    if (questions.isEmpty()) {
        return emptyMap()
    }

    val payload = linkedMapOf<String, List<String>>()
    questions.forEach { question ->
        val answer =
            typedAnswersByQuestionId[question.id]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { listOf(it) }
                ?: selectedOptionByQuestionId[question.id]
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
                    ?.let { listOf(it) }

        if (answer == null) {
            return null
        }
        payload[question.id] = answer
    }
    return payload
}

internal fun hasStructuredInputAnswer(
    question: PendingStructuredInputQuestion,
    typedAnswersByQuestionId: Map<String, String>,
    selectedOptionByQuestionId: Map<String, String>,
): Boolean =
    typedAnswersByQuestionId[question.id]
        ?.trim()
        ?.isNotEmpty() == true ||
        selectedOptionByQuestionId[question.id]
            ?.trim()
            ?.isNotEmpty() == true

internal fun shouldMaskStructuredInput(question: PendingStructuredInputQuestion): Boolean {
    if (question.isSecret) return true
    val text = listOf(question.header, question.question, question.id).joinToString(" ").lowercase()
    return listOf(
        "password",
        "passcode",
        "api key",
        "token",
        "secret",
        "credential",
        "credentials",
    ).any { it in text }
}
