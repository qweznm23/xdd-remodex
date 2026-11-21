package com.remodex.mobile.ui.turn

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.remodex.mobile.R
import com.remodex.mobile.core.model.*
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.services.AiChangeSetRevertService
import kotlinx.coroutines.CancellationException

private class RevertChoice(val change: AIChangeSet, val cwd: String, val confirm: () -> Unit)

@Composable
internal fun rememberRevertPreview(repository: CodexRepository, device: String?, thread: String?):
    (AIChangeSet, String, () -> Unit) -> Unit = key(repository, device, thread) {
    var choice by remember { mutableStateOf<RevertChoice?>(null) }
    choice?.let { selected -> key(selected) {
        var result by remember { mutableStateOf<RevertPreviewResult?>(null) }
        var failure by remember { mutableStateOf<String?>(null) }
        var attempt by remember { mutableIntStateOf(0) }
        LaunchedEffect(attempt) {
            failure = null
            try { result = AiChangeSetRevertService(repository).previewSinglePatch(selected.change, selected.cwd) }
            catch (cancel: CancellationException) { throw cancel }
            catch (error: Exception) {
                android.util.Log.e("RemodexRevert", "stage=preview id=${selected.change.id}", error)
                failure = formatTurnSendError(error)
            }
        }
        RemodexAlertDialog(onDismissRequest = { choice = null }, title = { Text(stringResource(R.string.revert_preview_title)) },
            text = { Column(Modifier.verticalScroll(rememberScrollState())) {
                if (result == null && failure == null) CircularProgressIndicator()
                failure?.let { Text(it); TextButton(onClick = { attempt++ }) { Text(stringResource(R.string.goal_retry)) } }
                result?.let { preview ->
                    Text(stringResource(if (preview.canRevert) R.string.revert_preview_ready else R.string.revert_preview_blocked))
                    preview.affectedFiles.forEach { Text(it) }
                    preview.conflicts.forEach { Text("${it.path} — ${it.message}") }
                    preview.unsupportedReasons.forEach { Text(it) }
                    if (preview.stagedFiles.isNotEmpty()) Text(stringResource(R.string.revert_preview_staged, preview.stagedFiles.joinToString()))
                }
            } }, confirmButton = {
                TextButton(enabled = result?.canRevert == true, onClick = {
                    choice = null
                    if (repository.currentTrustedMacDeviceId.value == device && repository.isSessionReady.value) selected.confirm()
                }) { Text(stringResource(R.string.turn_usage_revert_action)) }
            }, dismissButton = { TextButton(onClick = { choice = null }) { Text(stringResource(android.R.string.cancel)) } })
    } }
    val open: (AIChangeSet, String, () -> Unit) -> Unit = { change, cwd, apply ->
        if (!change.workspaceCheckpoint?.restoreCheckpointRef.isNullOrBlank() ||
            (change.source == AIChangeSetSource.fileChangeFallback && change.fallbackPatchBatches.isNotEmpty())) apply()
        else choice = RevertChoice(change, cwd, apply)
    }
    open
}
