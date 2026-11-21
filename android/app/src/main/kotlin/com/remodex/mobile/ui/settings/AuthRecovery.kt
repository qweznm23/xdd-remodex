package com.remodex.mobile.ui.settings

import com.remodex.mobile.R
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.core.transport.RelaySocketClosed
import com.remodex.mobile.services.FileTransferFailure

internal enum class AuthRecoveryAction { Reload, Query, Check }
internal data class AuthRecovery(val hint: Int, val action: AuthRecoveryAction? = null)

internal fun authRecovery(operation: String, error: Exception): AuthRecovery {
    val causes = generateSequence<Throwable>(error) { it.cause }.take(16).toList()
    val data = causes.filterIsInstance<CodexServiceError.RpcFailure>().firstOrNull()?.rpcError?.data?.objectValue
    val code = data?.get("errorCode")?.stringValue
    val statuses = data?.get("diagnostic")?.objectValue?.get("runtime")?.objectValue
        ?.get("httpStatuses")?.arrayValue.orEmpty().mapNotNull { it.longValue?.toInt() ?: it.stringValue?.toIntOrNull() }
    val readAction = when (operation) {
        "list" -> AuthRecoveryAction.Reload
        "query" -> AuthRecoveryAction.Query
        "check" -> AuthRecoveryAction.Check
        else -> null
    }
    return when {
        code == "MANAGER_BUSY" -> AuthRecovery(R.string.auth_recovery_busy, readAction ?: AuthRecoveryAction.Reload)
        code in setOf("PROFILE_CHANGED", "PROFILE_ACTIVE") -> AuthRecovery(R.string.auth_recovery_changed, AuthRecoveryAction.Reload)
        code in setOf("CURRENT_SYNC_CONFLICT", "RECOVERY_REQUIRED", "IDENTITY_CHANGED", "RUNTIME_MISMATCH") ->
            AuthRecovery(R.string.auth_recovery_host)
        code in setOf("INVALID_NAME", "INVALID_PROFILE", "AUTH_TOO_LARGE", "IMPORT_SOURCE_NOT_ALLOWED") ||
            causes.filterIsInstance<FileTransferFailure>().any { it.stage == "auth_import_read" && it.code == "invalid_size" } ->
            AuthRecovery(R.string.auth_recovery_input)
        code in setOf("TOKEN_REFRESH_UNCONFIRMED", "TOKEN_REFRESH_EXPIRED") ->
            AuthRecovery(R.string.auth_recovery_token, AuthRecoveryAction.Reload)
        403 in statuses -> AuthRecovery(R.string.auth_recovery_denied)
        401 in statuses -> AuthRecovery(R.string.auth_recovery_authentication)
        causes.any { it === CodexServiceError.Disconnected || it is RelaySocketClosed } ->
            AuthRecovery(R.string.auth_recovery_connection, readAction ?: AuthRecoveryAction.Reload)
        causes.any { it is java.util.concurrent.TimeoutException || it is java.net.SocketTimeoutException } ->
            AuthRecovery(if (readAction == null) R.string.auth_recovery_unknown_write else R.string.auth_recovery_timeout,
                readAction ?: AuthRecoveryAction.Reload)
        operation == "switch" || operation == "restart" -> AuthRecovery(R.string.auth_recovery_switch)
        readAction != null -> AuthRecovery(R.string.auth_recovery_read, readAction)
        else -> AuthRecovery(R.string.auth_recovery_unknown_write, AuthRecoveryAction.Reload)
    }
}
