package com.remodex.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import com.composables.icons.lucide.R as LucideR
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.remodex.mobile.R
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.services.FileTransferFailure

internal fun authReportFacts(report: Map<String, com.remodex.mobile.core.model.JSONValue>): List<String> {
    fun scalar(key: String) = report[key]?.let { it.stringValue ?: it.longValue?.toString() }
    val upstream = report["rpc"]?.objectValue
    val statuses = report["runtime"]?.objectValue?.get("httpStatuses")?.arrayValue.orEmpty()
        .mapNotNull { it.stringValue ?: it.longValue?.toString() }.distinct().map { "HTTP $it" }
    val cause = (report["cause"] ?: report["context"])?.objectValue
    return listOfNotNull(upstream?.get("message")?.stringValue ?: scalar("message"), cause?.get("message")?.stringValue,
        scalar("stage"), scalar("code"), (upstream?.get("code")?.longValue?.toString() ?: scalar("rpcCode"))?.let { "RPC $it" }) + statuses
}

internal fun authFailureSummary(error: Exception): String {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    val rpc = causes.filterIsInstance<CodexServiceError.RpcFailure>().firstOrNull()?.rpcError
    val data = rpc?.data?.objectValue
    val report = data?.get("diagnostic")?.objectValue.orEmpty()
    val transfer = causes.filterIsInstance<FileTransferFailure>().firstOrNull()
    val facts = authReportFacts(report)
    val reason = when {
        rpc == null -> causes.mapNotNull { it.localizedMessage }
        else -> listOfNotNull(error.localizedMessage.takeIf { error !is CodexServiceError.RpcFailure },
            if (report["message"] == null && report["rpc"]?.objectValue?.get("message") == null) rpc.message else null)
    }
    val codes = listOfNotNull(data?.get("errorCode")?.stringValue, transfer?.stage, transfer?.code, rpc?.let { "RPC ${it.code}" })
    val summary = (reason + facts + codes).distinct().joinToString(" · ").ifBlank { error.javaClass.simpleName }
    return if (summary.length <= 1200) summary else summary.take(1200) + "…"
}

@Composable
internal fun AuthActionFeedback(state: AuthProfilesState, slot: String, connected: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
    val busy = state.busy?.takeIf { authActionSlot(it) == slot }
    val result = state.result(slot)
    val operation = (busy ?: result?.operation ?: return).substringBefore(':')
    val actionLabel = when (operation) {
        "refreshToken" -> R.string.auth_token_refresh
        "query", "runtime-quota" -> R.string.auth_profiles_query
        "switch" -> R.string.auth_profiles_switch
        "restart" -> R.string.auth_restart_action
        "check" -> R.string.auth_manage_check
        "export" -> R.string.auth_manage_export
        "import" -> R.string.auth_library_replace
        "add", "file_selection" -> R.string.auth_manage_import
        "remove" -> R.string.auth_library_remove
        "rename" -> R.string.auth_library_rename
        else -> R.string.auth_profiles_refresh
    }
    if (busy != null) {
        if (operation !in setOf("switch", "list", "runtime-quota", "restart-progress")) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            if (slot.startsWith("file:") || slot == "add") Text(stringResource(actionLabel), style = MaterialTheme.typography.labelMedium)
        }
        return
    }
    val error = result?.error
    if (error != null) {
        var expanded by remember(error) { mutableStateOf(false) }
        val recovery = remember(operation, error) { authRecovery(operation, error) }
        val id = result.operation.substringAfter(':', "")
        val profile = if (id == "current") state.currentProfile ?: state.management?.current
            else state.profiles.firstOrNull { it.id == id } ?: state.currentLibraryProfiles.firstOrNull { it.id == id }
        val action = recovery.action
        val colors = MaterialTheme.colorScheme
        Surface(Modifier.fillMaxWidth().padding(vertical = 4.dp), shape = MaterialTheme.shapes.medium,
            color = colors.surfaceContainerLow, contentColor = colors.onSurface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val switchError = error as? SwitchVerificationFailure
                val summary = when {
                    switchError?.unmanaged == true -> stringResource(R.string.auth_unmanaged_restart)
                    switchError?.record != null -> listOfNotNull(switchError.field("stderr")?.trim()?.takeIf { it.isNotEmpty() },
                        switchError.field("message"), switchError.field("stage")).firstOrNull() ?: stringResource(R.string.questions_error_details)
                    else -> remember(error) { authFailureSummary(error) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_triangle_alert), null, Modifier.size(16.dp), tint = colors.error)
                    Text(stringResource(R.string.auth_action_reason, stringResource(actionLabel), summary),
                        maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleSmall)
                }
                Text(stringResource(recovery.hint), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (action != null && (action == AuthRecoveryAction.Reload || profile != null)) {
                        TextButton(enabled = connected && state.busy == null && !state.switching, onClick = {
                            scope.launch {
                                when (action) {
                                    AuthRecoveryAction.Reload -> state.refresh()
                                    AuthRecoveryAction.Query -> profile?.let { state.query(it) }
                                    AuthRecoveryAction.Check -> profile?.let { state.checkFile(it) }
                                }
                            }
                        }) { Text(stringResource(when (action) {
                            AuthRecoveryAction.Reload -> R.string.auth_profiles_refresh
                            AuthRecoveryAction.Query -> R.string.auth_recovery_query
                            AuthRecoveryAction.Check -> R.string.auth_manage_check
                        })) }
                    }
                    if (switchError != null) TextButton(onClick = { state.dismissSwitchFailure(slot, switchError) }) {
                        Text(stringResource(R.string.auth_dismiss_status))
                    }
                    TextButton(onClick = { expanded = true }) { Text(stringResource(R.string.questions_error_details)) }
                }
                if (expanded) AuthDiagnosticSheet(stringResource(R.string.questions_error_details), remember(error) { authProfileDiagnostic(error) }) {
                    expanded = false
                }
            }
        }
        return
    }
    val message = when (operation) {
        "refreshToken" -> R.string.auth_token_updated
        "export" -> R.string.auth_manage_exported
        "import", "add" -> R.string.auth_manage_imported
        "rename" -> R.string.auth_action_renamed
        "remove" -> R.string.auth_action_removed
        "check" -> when (result?.validity) {
            "expired" -> R.string.auth_manage_token_expired
            "valid_by_timestamp" -> R.string.auth_manage_token_in_time
            else -> R.string.auth_manage_token_unknown
        }
        else -> return
    }
    Row(Modifier.padding(horizontal = 12.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(painterResource(LucideR.drawable.lucide_ic_check), null, Modifier.size(16.dp))
        Text(if (operation == "remove") stringResource(message, result!!.operation.substringAfter(':')) else stringResource(message),
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
internal fun AuthSwitchFeedback(state: AuthProfilesState, id: String, connected: Boolean, scope: kotlinx.coroutines.CoroutineScope) {
    AuthActionFeedback(state, "switch:$id", connected, scope)
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AuthDiagnosticSheet(title: String, diagnostic: String, dismiss: () -> Unit) {
    com.remodex.mobile.ui.theme.RemodexModalBottomSheet(onDismissRequest = dismiss, sheetGesturesEnabled = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            SelectionContainer {
                Text(diagnostic, Modifier.verticalScroll(rememberScrollState()).padding(bottom = 12.dp),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
