package com.remodex.mobile.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import com.remodex.mobile.core.model.*
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.services.AccountUsageDetailsCodec
import com.remodex.mobile.services.RateLimitPayloadCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import java.time.Instant
import com.remodex.mobile.services.WorkspaceFileUploadService
import com.remodex.mobile.services.WorkspaceFileTransferService
import com.remodex.mobile.services.WorkspaceFileInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream

internal fun authProfileDiagnostic(error: Exception): String {
    val rpc = (error as? com.remodex.mobile.core.error.CodexServiceError.RpcFailure)?.rpcError
    return (rpc?.data?.let { JSONValue.toJsonElement(it).toString() + "\n" } ?: "") + error.stackTraceToString()
}

internal data class AuthProfile(
    val id: String, val revision: String, val path: String,
    val issued: Instant? = null, val expires: Instant? = null, val refreshed: Instant? = null, val modified: Instant? = null,
    val accessTokenState: String? = null,
) {
    companion object {
        fun decode(value: JSONValue): AuthProfile {
            val p = value.objectValue ?: error("profile must be an object")
            fun text(key: String) = p[key]?.stringValue ?: error("profile.$key missing")
            fun date(key: String): Instant? = p[key]?.takeUnless { it == JSONValue.Null }?.let {
                Instant.parse(it.stringValue ?: error("profile.$key must be a date"))
            }
            return AuthProfile(text("id"), text("revision"), text("path"), date("accessIssuedAt"),
                date("accessExpiresAt"), date("lastRefresh"), date("modifiedAt"), p["accessTokenState"]?.stringValue)
        }
    }
}

internal data class ProfileQuota(val at: Instant, val email: String?, val plan: String?,
    val rows: List<CodexRateLimitDisplayRow>, val details: AccountUsageDetails) {
    companion object {
        fun decode(p: Map<String, JSONValue>): ProfileQuota {
            val at = Instant.parse(p["queriedAt"]?.stringValue ?: error("queriedAt missing"))
            val quota = p["quota"]?.objectValue ?: error("quota missing")
            return ProfileQuota(at, p["email"]?.stringValue, p["plan"]?.stringValue,
                CodexRateLimitBucket.visibleDisplayRows(RateLimitPayloadCodec.decodeRateLimitBuckets(quota)),
                AccountUsageDetailsCodec.decode(quota).copy(lastSuccessfulReadAt = at))
        }
    }
}

internal class AuthProfilesState(private val repository: CodexRepository) {
    var switchProfileId by mutableStateOf<String?>(null); private set
    var switchStartedNanos by mutableStateOf<Long?>(null); private set
    var restartExpectedNanos by mutableStateOf<Long?>(null); private set
    var sawSwitchDisconnect by mutableStateOf(false); private set

    fun observeSwitchConnection(connected: Boolean) {
        if (switching && !connected) sawSwitchDisconnect = true
    }

    var supportsTokenRefresh by mutableStateOf(false); private set
    var supportsManagement by mutableStateOf(false); private set
    var management by mutableStateOf<AuthManagementStatus?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    var checkedProfileId by mutableStateOf<String?>(null); private set
    var currentProfile by mutableStateOf<AuthProfile?>(null); private set
    var lastRefreshed by mutableStateOf<AuthProfile?>(null); private set
    var profiles by mutableStateOf<List<AuthProfile>>(emptyList()); private set
    var available by mutableStateOf<Boolean?>(null); private set
    var busy by mutableStateOf<String?>(null); private set
    var failure by mutableStateOf<Exception?>(null); private set
    var failureOperation by mutableStateOf<String?>(null); private set
    var restartStatus by mutableStateOf<String?>(null); private set
    var pendingOperationId by mutableStateOf<String?>(null); private set
    var switchDeadline by mutableStateOf<Instant?>(null); private set
    val switching: Boolean get() = pendingOperationId != null && restartStatus == "restart_scheduled"
    var quota by mutableStateOf<Map<String, ProfileQuota>>(emptyMap()); private set

    private suspend fun request(action: String, profile: AuthProfile? = null, extra: Map<String, JSONValue> = emptyMap()): Map<String, JSONValue> {
        val params = profile?.let { mapOf("profile" to JSONValue.Str(it.id), "revision" to JSONValue.Str(it.revision)) }.orEmpty() + extra
        val response = withTimeoutOrNull(if (action == "query" || action == "refreshToken") 250_000L else 30_000L) {
            repository.sendRequest("account/profiles/$action", JSONValue.Obj(params))
        } ?: throw java.util.concurrent.TimeoutException("account/profiles/$action stage=request; result unknown, refresh before retry")
        return response.result?.objectValue ?: error("account/profiles/$action: missing result")
    }

    private suspend fun operation(name: String, work: suspend () -> Unit) {
        if (busy != null) return
        busy = name
        notice = null
        try { work(); failure = null; failureOperation = null }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            failure = error
            failureOperation = name
            if (switching && name == "list") {
                val code = (error as? com.remodex.mobile.core.error.CodexServiceError.RpcFailure)?.rpcError?.data?.objectValue?.get("errorCode")?.stringValue
                val bridgeRestarting = error is com.remodex.mobile.core.transport.RelaySocketClosed &&
                    com.remodex.mobile.core.transport.isBridgeUnavailableClose(error.code)
                if (!bridgeRestarting && error !== com.remodex.mobile.core.error.CodexServiceError.Disconnected && code != "MANAGER_BUSY") {
                    restartStatus = "verification_failed"
                }
            }
        }
        finally { busy = null }
    }

    suspend fun refresh() = operation("list") {
        val p = request("list")
        val status = p["status"]?.stringValue
        require(status == "available" || status == "unavailable") { "Invalid profile list status" }
        val rows = p["profiles"]?.arrayValue ?: error("profiles missing")
        val updated = rows.map(AuthProfile::decode)
        require(updated.map { it.id }.distinct().size == updated.size) { "Duplicate profile id" }
        currentProfile = p["currentProfile"]?.takeUnless { it == JSONValue.Null }?.let(AuthProfile::decode)
        supportsTokenRefresh = p["supportsTokenRefresh"]?.boolValue == true
        supportsManagement = p["supportsImportExport"]?.boolValue == true && p["managedBy"]?.stringValue == "remodex"
        profiles = updated
        available = status == "available"
        restartStatus = p["restartStatus"]?.stringValue
        val operationId = p["operationId"]?.stringValue
        if (pendingOperationId != null && operationId != pendingOperationId) {
            restartStatus = "verification_failed"
            error("stage=switch-verification: selection was superseded; refresh before selecting again")
        }
        if (restartStatus == "restart_scheduled" && pendingOperationId == null) {
            switchProfileId = p["selectedProfile"]?.stringValue
            switchStartedNanos = System.nanoTime()
            restartExpectedNanos = null // A restored operation has no local acknowledgement time.
            sawSwitchDisconnect = false
            pendingOperationId = operationId
            switchDeadline = Instant.now().plusSeconds(180)
        }
        if (restartStatus in setOf("failed", "verification_failed", "superseded_by_newer_switch")) {
            error(p["restartError"]?.stringValue ?: "stage=switch-verification: $restartStatus")
        }
        if (restartStatus == "ready") {
            switchProfileId = p["selectedProfile"]?.stringValue ?: switchProfileId.takeIf { operationId != null && pendingOperationId == operationId }
            pendingOperationId = null
            switchDeadline = null
        }
        quota = quota.filterKeys { id -> profiles.any { it.id == id } }
        if (supportsManagement && !switching) loadManagement()
    }

    private suspend fun loadManagement() {
        val updated = AuthManagementStatus.decode(request("status"))
        management = updated
        profiles = updated.entries.filterNot { it.active }.map { it.profile }
        if (currentProfile != null) currentProfile = updated.current
    }

    private fun applyProfile(updated: AuthProfile) {
        if (updated.id == "current") currentProfile = updated
        else profiles = profiles.map { if (it.id == updated.id) updated else it }
        management = management?.let { status -> status.copy(
            current = if (updated.id == "current") updated else status.current,
            entries = status.entries.map { if (it.profile.id == updated.id) it.copy(profile = updated) else it },
        ) }
    }

    fun reportFileFailure(error: Exception) { failure = error; failureOperation = "file_selection"; notice = null }

    private suspend fun fileOperation(name: String, work: suspend () -> Unit) {
        // Returning from a system picker can overlap the page's read-only reload.
        // Do not silently discard the selected file in that case.
        val idle = withTimeoutOrNull(30_000) { snapshotFlow { busy }.first { it == null }; true }
        if (idle != true) {
            reportFileFailure(java.util.concurrent.TimeoutException("stage=$name: waiting for authorization operation timed out"))
            return
        }
        if (available == null) refresh()
        operation(name, work)
    }

    suspend fun checkFile(profile: AuthProfile) = operation("check:${profile.id}") {
        check(supportsManagement && !switching)
        val updated = AuthProfile.decode(JSONValue.Obj(request("check", profile)))
        require(updated.id == profile.id) { "stage=check_response: profile mismatch" }
        applyProfile(updated)
        checkedProfileId = profile.id
        notice = "checked"
    }

    suspend fun importFile(id: String, revision: String, openInput: () -> InputStream) = fileOperation("import:$id") {
        check(supportsManagement && !switching)
        // Read a bounded credential file without parsing or logging its contents.
        val bytes = withContext(Dispatchers.IO) { openInput().use(::readAuthImport) }
        try {
            val uploaded = bytes.inputStream().use { WorkspaceFileUploadService(repository).upload("auth.json", bytes.size.toLong(), it) {} }
            val result = request("import", AuthProfile(id, revision, ""), mapOf("sourcePath" to JSONValue.Str(uploaded.path)))
            require(result["status"]?.stringValue == "imported" && result["profile"]?.stringValue == id) { "stage=import_acknowledgement: profile or status mismatch" }
            quota = quota - id
            checkedProfileId = null
            notice = "imported"
            loadManagement()
        } finally { bytes.fill(0) }
    }

    suspend fun exportFile(profile: AuthProfile, save: suspend (WorkspaceFileTransferService, WorkspaceFileInfo, String) -> Unit) = fileOperation("export:${profile.id}") {
        check(supportsManagement && !switching)
        val result = request("export", profile)
        require(result["profile"]?.stringValue == profile.id) { "stage=export_response: profile mismatch" }
        val path = result["path"]?.stringValue ?: error("stage=export_response: missing path")
        val cwd = result["cwd"]?.stringValue ?: error("stage=export_response: missing cwd")
        val size = result["byteLength"]?.longValue ?: error("stage=export_response: missing size")
        require(size in 1..65536) { "stage=export_response: invalid credential size" }
        val transfer = WorkspaceFileTransferService(repository)
        val info = transfer.info(path, cwd)
        require(info.byteLength == size && info.path == path) { "stage=export_metadata: snapshot mismatch" }
        save(transfer, info, cwd)
        notice = "exported"
    }

    suspend fun query(profile: AuthProfile) = operation("query:${profile.id}") {
        check(!switching) { "Account switch is still pending" }
        val p = request("query", profile)
        require(p["profile"]?.stringValue == profile.id) { "Quota belongs to another profile" }
        val updated = AuthProfile.decode(p["profileInfo"] ?: error("profileInfo missing"))
        require(updated.id == profile.id) { "Profile changed during query" }
        val result = ProfileQuota.decode(p)
        quota = quota + (profile.id to result)
        applyProfile(updated)
    }

    suspend fun refreshToken(profile: AuthProfile) = operation("refreshToken:${profile.id}") {
        check(!switching && supportsTokenRefresh) { "Token refresh unavailable" }
        lastRefreshed = null
        val result = request("refreshToken", profile)
        require(result["status"]?.stringValue == "refreshed" && result["profile"]?.stringValue == profile.id) {
            "stage=refresh-acknowledgement: account or status mismatch"
        }
        val updated = AuthProfile.decode(result["profileInfo"] ?: error("refreshToken: profileInfo missing"))
        require(updated.id == profile.id) { "refreshToken: authorization file mismatch" }
        applyProfile(updated)
        lastRefreshed = updated
    }

    suspend fun switch(profile: AuthProfile) = operation("switch:${profile.id}") {
        check(!switching) { "Account switch is still pending" }
        switchProfileId = profile.id
        switchStartedNanos = System.nanoTime()
        restartExpectedNanos = null
        sawSwitchDisconnect = false
        pendingOperationId = null
        switchDeadline = null
        restartStatus = null
        failure = null
        val p = request("switch", profile)
        require(p["status"]?.stringValue == "restart_scheduled" && p["profile"]?.stringValue == profile.id) {
            "Switch outcome unknown; refresh account status"
        }
        pendingOperationId = p["operationId"]?.stringValue
            ?: throw IllegalStateException("stage=switch-acknowledgement: operationId missing; switch outcome unknown, refresh status")
        restartStatus = "restart_scheduled"
        restartExpectedNanos = p["restartAfterSeconds"]?.longValue?.takeIf { it in 1..180 }
            ?.let { System.nanoTime() + it * 1_000_000_000L }
        switchDeadline = Instant.now().plusSeconds(180)
        profiles = profiles.filterNot { it.id == profile.id }
        available = null // Re-read after the connection recovers.
    }

    fun checkSwitchDeadline(now: Instant = Instant.now()) {
        if (switching && switchDeadline?.let { !now.isBefore(it) } == true) {
            restartStatus = "verification_failed"
            failure = java.util.concurrent.TimeoutException("stage=switch-reconnect-verification operation=$pendingOperationId timeoutMs=180000; file selection may have completed; refresh status before retry")
        }
    }
}
