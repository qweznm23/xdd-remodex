package com.remodex.mobile.ui.settings

import com.remodex.mobile.services.resetCard
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
    val rpcData = generateSequence<Throwable>(error) { it.cause }.take(16)
        .filterIsInstance<com.remodex.mobile.core.error.CodexServiceError.RpcFailure>()
        .mapNotNull { it.rpcError.data }.joinToString("\n") { JSONValue.toJsonElement(it).toString() }
    return listOf(rpcData, error.stackTraceToString()).filter { it.isNotEmpty() }.joinToString("\n")
}

internal class SwitchVerificationFailure(val operationId: String?, message: String) : IllegalStateException(message) {
    val record = if (message.trimStart().startsWith("{")) try {
        kotlinx.serialization.json.Json.parseToJsonElement(message) as? kotlinx.serialization.json.JsonObject
    } catch (_: kotlinx.serialization.SerializationException) { null } else null

    fun field(name: String) = (record?.get(name) as? kotlinx.serialization.json.JsonPrimitive)?.content
    val unmanaged = field("stage") == "daemon_restart" && field("exit_code") == "1" &&
        field("stderr")?.trim() == "Error: app server is running but is not managed by codex app-server daemon"
}

internal data class FailedAccountSwitch(val target: String, val runtime: String?, val restartFailed: Boolean) {
    companion object {
        fun fromList(fields: Map<String, JSONValue>, record: AuthRestartRecord?): FailedAccountSwitch? {
            if (fields["restartStatus"]?.stringValue !in setOf("failed", "verification_failed")) return null
            val target = fields["selectedProfile"]?.stringValue ?: return null
            val id = fields["operationId"]?.stringValue
            val latest = record?.takeIf { it.operationId == id && (it.profile == target || it.detail.objectValue?.get("expectedProfile")?.stringValue == target) }
            val stage = latest?.detail?.objectValue?.get("failure")?.objectValue?.get("stage")?.stringValue
                ?: latest?.detail?.objectValue?.get("stage")?.stringValue
                ?: fields["restartError"]?.stringValue?.let { SwitchVerificationFailure(id, it).field("stage") }
            val live = fields["runtimeVerification"]?.objectValue?.takeIf { id != null &&
                it["operationId"]?.stringValue == id && it["profile"]?.stringValue == target &&
                it["status"]?.stringValue == "unconfirmed" }
            return FailedAccountSwitch(target, live?.get("runtimeProfile")?.stringValue, stage in setOf("daemon_restart", "daemon_start", "service_restart", "direct_start", "direct_stop", "handover_stop"))
        }
    }
}

internal data class AuthActionResult(val operation: String, val error: Exception?, val validity: String?)

internal fun authActionSlot(operation: String): String = when (operation.substringBefore(':')) {
    "check", "rename", "export", "import", "remove" -> "file:" + operation.substringAfter(':')
    "add", "file_selection" -> "add"
    else -> operation
}

internal data class AuthProfile(
    val id: String, val revision: String, val path: String,
    val issued: Instant? = null, val expires: Instant? = null, val refreshed: Instant? = null, val modified: Instant? = null,
    val accessTokenState: String? = null,
    val displayName: String? = null, val accountName: String? = null, val email: String? = null,
) {
    fun accountIdentity(queriedEmail: String? = null): String? {
        val address = email?.takeIf { it.isNotBlank() } ?: queriedEmail
        return listOfNotNull(accountName, address).map { it.trim() }.filter { it.isNotEmpty() }
            .distinct().joinToString(" · ").ifEmpty { null }
    }

    companion object {
        fun decode(value: JSONValue): AuthProfile {
            val p = value.objectValue ?: error("profile must be an object")
            fun text(key: String) = p[key]?.stringValue ?: error("profile.$key missing")
            fun date(key: String): Instant? = p[key]?.takeUnless { it == JSONValue.Null }?.let {
                Instant.parse(it.stringValue ?: error("profile.$key must be a date"))
            }
            return AuthProfile(text("id"), text("revision"), text("path"), date("accessIssuedAt"),
                date("accessExpiresAt"), date("lastRefresh"), date("modifiedAt"), p["accessTokenState"]?.stringValue,
                p["displayName"]?.stringValue, p["accountName"]?.stringValue, p["email"]?.stringValue)
        }
    }
}

internal data class ProfileQuota(val at: Instant, val email: String?, val plan: String?,
    val rows: List<CodexRateLimitDisplayRow>, val details: AccountUsageDetails,
    val weekly: CodexRateLimitWindow?) {
    companion object {
        fun decode(p: Map<String, JSONValue>): ProfileQuota {
            val at = Instant.parse(p["queriedAt"]?.stringValue ?: error("queriedAt missing"))
            val quota = p["quota"]?.objectValue ?: error("quota missing")
            val buckets = RateLimitPayloadCodec.decodeRateLimitBuckets(quota)
            return ProfileQuota(at, p["email"]?.stringValue, p["plan"]?.stringValue,
                CodexRateLimitBucket.visibleDisplayRows(buckets),
                AccountUsageDetailsCodec.decode(quota).copy(lastSuccessfulReadAt = at), accountWeeklyQuota(buckets))
        }
    }
}

internal class AuthProfilesState(private val repository: CodexRepository) {
    var initialListSettled by mutableStateOf(false); private set
    var switchProfileId by mutableStateOf<String?>(null); private set
    var failedSwitch by mutableStateOf<FailedAccountSwitch?>(null); private set
    val verifyingIdentity: Boolean get() = switching || restartStatus == "verification_failed" || failedSwitch != null || if (restartHistory.isEmpty()) {
        restartStatus in setOf("failed", "verification_failed", "superseded_by_newer_switch")
    } else restartHistory.firstOrNull {
        it.status == "ready" || it.status == "recovered" || it.authorizationUnconfirmed
    }?.authorizationUnconfirmed == true
    var switchStartedNanos by mutableStateOf<Long?>(null); private set
    var restartExpectedNanos by mutableStateOf<Long?>(null); private set
    var sawSwitchDisconnect by mutableStateOf(false); private set

    var awaitingSwitchConnection by mutableStateOf(false); private set
    private var lastVerificationWait: Exception? = null

    fun observeSwitchConnection(connected: Boolean) {
        if (switching) {
            awaitingSwitchConnection = !connected
            if (!connected) sawSwitchDisconnect = true
        }
    }

    var supportsTokenRefresh by mutableStateOf(false); private set
    var supportsManagement by mutableStateOf(false); private set
    var supportsManualRestart by mutableStateOf(false); private set
    var runtimeControl by mutableStateOf<Map<String, JSONValue>>(emptyMap()); private set
    private var supportsProgress = false
    var liveQuota by mutableStateOf<ProfileQuota?>(null); private set
    var liveQuotaProfile by mutableStateOf<String?>(null); private set
    private var observedOperation: String? = null
    var restartHistory by mutableStateOf<List<AuthRestartRecord>>(emptyList()); private set
    var management by mutableStateOf<AuthManagementStatus?>(null); private set
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

    private val results = androidx.compose.runtime.mutableStateMapOf<String, AuthActionResult>()
    private var dismissedSwitch by mutableStateOf<Triple<String, String?, String?>?>(null)

    fun result(slot: String): AuthActionResult? = results[slot]?.takeUnless {
        val error = it.error as? SwitchVerificationFailure
        error != null && dismissedSwitch == Triple(slot, error.operationId, error.message)
    }

    fun dismissSwitchFailure(slot: String, error: SwitchVerificationFailure) {
        if (results[slot]?.error === error) dismissedSwitch = Triple(slot, error.operationId, error.message)
    }

    private fun rememberResult(operation: String, error: Exception? = null) {
        val slot = if (operation.startsWith("remove:") && error == null) "removed" else authActionSlot(operation)
        val id = operation.substringAfter(':', "")
        if (error == null && operation.substringBefore(':') in setOf("remove", "import")) {
            results.keys.filter { it.substringAfter(':', "") == id }.forEach(results::remove)
        }
        val validity = if (operation.startsWith("check:") && error == null)
            (management?.entries?.firstOrNull { it.profile.id == id }?.profile ?: management?.current?.takeIf { it.id == id })?.accessTokenState
            else null
        results[slot] = AuthActionResult(operation, error, validity)
    }

    private suspend fun request(action: String, profile: AuthProfile? = null, extra: Map<String, JSONValue> = emptyMap()): Map<String, JSONValue> {
        val params = profile?.let { mapOf("profile" to JSONValue.Str(it.id), "revision" to JSONValue.Str(it.revision)) }.orEmpty() + extra
        val response = withTimeoutOrNull(if (action == "query" || action == "refreshToken") 250_000L else 30_000L) {
            repository.sendRequest("account/profiles/$action", JSONValue.Obj(params))
        } ?: throw java.util.concurrent.TimeoutException("account/profiles/$action stage=request; result unknown, refresh before retry")
        response.error?.let { throw com.remodex.mobile.core.error.CodexServiceError.RpcFailure(it) }
        return response.result?.objectValue ?: error("account/profiles/$action: missing result")
    }

    private suspend fun operation(name: String, work: suspend () -> Unit) {
        if (busy != null) return
        val verificationId = pendingOperationId?.takeIf { switching && name in setOf("list", "restart-progress") }
        val verifyingSwitch = verificationId != null
        results.remove(authActionSlot(name))
        busy = name
        try {
            work()
            if (name == "list" && restartStatus == "ready") {
                val slot = "switch:$switchProfileId"
                if (verifyingSwitch || results[slot]?.error is SwitchVerificationFailure) results.remove(slot)
            }
            rememberResult(name)
            failure = null
            failureOperation = null
        }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (verifyingSwitch && verificationId == pendingOperationId) {
                val causes = generateSequence<Throwable>(error) { it.cause }.take(16)
                val disconnected = causes.any {
                    when (it) {
                        com.remodex.mobile.core.error.CodexServiceError.Disconnected, is java.net.SocketException -> true
                        is com.remodex.mobile.core.transport.RelaySocketClosed ->
                            it.code in setOf(1000, 1001, 1006) || com.remodex.mobile.core.transport.isBridgeUnavailableClose(it.code)
                        else -> false
                    }
                }
                val locked = (error as? com.remodex.mobile.core.error.CodexServiceError.RpcFailure)
                    ?.rpcError?.data?.objectValue?.get("errorCode")?.stringValue == "MANAGER_BUSY"
                if (disconnected || locked) {
                    lastVerificationWait = error
                    if (disconnected) observeSwitchConnection(false)
                    return
                }
                restartStatus = "verification_failed"
                pendingOperationId = null
                switchDeadline = null
            }
            if (verifyingSwitch && lastVerificationWait != null && lastVerificationWait !== error && error is SwitchVerificationFailure) {
                error.addSuppressed(lastVerificationWait!!)
            }
            failure = error
            val target = if (name == "list" && restartHistory.isEmpty() && switchProfileId != null &&
                (verifyingSwitch || error is SwitchVerificationFailure)) "switch:$switchProfileId" else name
            failureOperation = target
            rememberResult(target, error)
        }
        finally { busy = null }
        if (name == "list") initialListSettled = true
    }

    private var lastLiveRead = 0L

    suspend fun refresh(forceLiveQuota: Boolean = false) {
        loadList()
        if (failedSwitch?.runtime != null && supportsProgress && !switching &&
            (forceLiveQuota || lastLiveRead == 0L || System.nanoTime() - lastLiveRead >= 300_000_000_000L)) readLiveQuota()
    }

    private suspend fun loadList() = operation("list") {
        val expected = pendingOperationId
        val p = request("list")
        if (expected != null && expected != pendingOperationId) throw CancellationException("Account switch verification superseded")
        awaitingSwitchConnection = false
        val status = p["status"]?.stringValue
        require(status == "available" || status == "unavailable") { "Invalid profile list status" }
        val rows = p["profiles"]?.arrayValue ?: error("profiles missing")
        val updated = rows.map(AuthProfile::decode)
        quota = quota.filterKeys { id ->
            val previous = profiles.firstOrNull { it.id == id }
            previous != null && updated.any { it.id == id && it.revision == previous.revision }
        }
        require(updated.map { it.id }.distinct().size == updated.size) { "Duplicate profile id" }
        val listedCurrent = p["currentProfile"]?.takeUnless { it == JSONValue.Null }?.let(AuthProfile::decode)
        val operationId = p["operationId"]?.stringValue
        val statusAfterRestart = p["restartStatus"]?.stringValue
        if (expected != null && operationId != expected) {
            throw SwitchVerificationFailure(expected, "stage=switch-verification: selection was superseded; refresh before selecting again")
        }
        val managed = p["supportsImportExport"]?.boolValue == true && p["managedBy"]?.stringValue == "remodex"
        // Keep the acknowledged operation pending until its verified account snapshot is available.
        val verifiedManagement = if (expected != null && managed && statusAfterRestart == "ready") {
            val snapshot = AuthManagementStatus.decode(request("status"))
            val selected = p["selectedProfile"]?.stringValue
            if (selected != null && snapshot.entries.none { it.active && it.profile.id == selected }) {
                throw SwitchVerificationFailure(expected, "stage=switch-verification: current authorization changed after the restart result")
            }
            snapshot
        } else null
        if (expected != null && expected != pendingOperationId) throw CancellationException("Account switch verification superseded")
        supportsTokenRefresh = p["supportsTokenRefresh"]?.boolValue == true
        supportsManagement = managed
        supportsManualRestart = p["supportsManualRestart"]?.boolValue == true
        runtimeControl = p["runtimeControl"]?.objectValue.orEmpty()
        supportsProgress = p["supportsRestartProgress"]?.boolValue == true
        restartHistory = p["restartHistory"]?.arrayValue.orEmpty().map(AuthRestartRecord::decode)
        // Keep both groups from the last complete management snapshot until status returns.
        if (!supportsManagement || management == null) {
            currentProfile = listedCurrent
            profiles = updated
        }
        available = status == "available"
        restartStatus = statusAfterRestart
        if (verifiedManagement != null) applyManagement(verifiedManagement, listedCurrent != null)
        val failureSnapshot = FailedAccountSwitch.fromList(p, restartHistory.firstOrNull())
        if (observedOperation != operationId || failureSnapshot != failedSwitch) {
            lastLiveRead = 0L
            liveQuota = null
            liveQuotaProfile = null
        }
        observedOperation = operationId
        failedSwitch = failureSnapshot
        results.filterValues { result ->
            val error = result.error as? SwitchVerificationFailure
            error?.operationId != null && operationId != null && error.operationId != operationId
        }.keys.forEach(results::remove)
        if (restartStatus == "ready" && (pendingOperationId == null || operationId == pendingOperationId)) {
            val slot = "switch:${p["selectedProfile"]?.stringValue ?: switchProfileId}"
            if (results[slot]?.error is SwitchVerificationFailure || results[slot]?.error is java.util.concurrent.TimeoutException) results.remove(slot)
            if (results["restart"]?.error is java.util.concurrent.TimeoutException) results.remove("restart")
        }
        if (p["restartHistory"] != null) {
            switchProfileId = p["selectedProfile"]?.stringValue
            if (restartStatus == "restart_scheduled" && operationId != null) {
                if (pendingOperationId != operationId || switchDeadline == null) followRestart(operationId, null)
            } else {
                pendingOperationId = null
                switchDeadline = null
            }
            if (verifiedManagement == null && supportsManagement && !switching) loadManagement(listedCurrent != null)
            return@operation
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
            if (pendingOperationId == null) switchProfileId = p["selectedProfile"]?.stringValue
            pendingOperationId = null
            switchDeadline = null
            val failure = SwitchVerificationFailure(operationId, p["restartError"]?.stringValue ?: "stage=switch-verification: $restartStatus")
            if (supportsManagement) {
                try { loadManagement(listedCurrent != null) }
                catch (cancel: CancellationException) { throw cancel }
                catch (statusError: Exception) { failure.addSuppressed(statusError) }
            }
            throw failure
        }
        if (restartStatus == "ready") {
            switchProfileId = p["selectedProfile"]?.stringValue ?: switchProfileId.takeIf { operationId != null && pendingOperationId == operationId }
            pendingOperationId = null
            switchDeadline = null
        }
        if (verifiedManagement == null && supportsManagement && !switching) loadManagement(listedCurrent != null)
    }

    private suspend fun loadManagement(includeCurrent: Boolean = currentProfile != null) {
        applyManagement(AuthManagementStatus.decode(request("status")), includeCurrent)
    }

    private fun applyManagement(updated: AuthManagementStatus, includeCurrent: Boolean) {
        val previousCurrent = management?.current
        if (previousCurrent != null && previousCurrent.revision != updated.current.revision) {
            results.remove("refreshToken:current")
            results.remove("file:current")
        }
        management = updated
        val inactive = updated.entries.filterNot { it.active }.map { it.profile }
        quota = quota.filterKeys { id -> profiles.any { old -> old.id == id && inactive.any { it.id == id && it.revision == old.revision } } }
        val knownIds = updated.entries.mapTo(mutableSetOf("current")) { it.profile.id }
        switchProfileId?.let(knownIds::add)
        results.keys.filter { ':' in it && it.substringAfter(':') !in knownIds }.forEach(results::remove)
        profiles = inactive
        currentProfile = updated.current.takeIf { includeCurrent }
    }

    // list and status are separate snapshots. Only attach library names to a matching current file.
    val currentLibraryProfiles: List<AuthProfile>
        get() {
            val snapshot = management ?: return emptyList()
            if (currentProfile != null && currentProfile?.revision != snapshot.current.revision) return emptyList()
            return snapshot.entries.filter { it.active }.map { it.profile }
        }

    private fun applyProfile(updated: AuthProfile) {
        if (updated.id == "current") currentProfile = updated
        else profiles = profiles.map { if (it.id == updated.id) updated else it }
        management = management?.let { status -> status.copy(
            current = if (updated.id == "current") updated else status.current,
            entries = status.entries.map { if (it.profile.id == updated.id) it.copy(profile = updated) else it },
        ) }
    }

    fun reportFileFailure(error: Exception, operation: String = "file_selection") {
        failure = error
        failureOperation = operation
        rememberResult(operation, error)
    }

    private suspend fun fileOperation(name: String, work: suspend () -> Unit) {
        // Returning from a system picker can overlap the page's read-only reload.
        // Do not silently discard the selected file in that case.
        val idle = withTimeoutOrNull(30_000) { snapshotFlow { busy }.first { it == null }; true }
        if (idle != true) {
            reportFileFailure(java.util.concurrent.TimeoutException("stage=$name: waiting for authorization operation timed out"), name)
            return
        }
        if (available == null) refresh()
        operation(name, work)
    }

    suspend fun checkFile(profile: AuthProfile) = operation("check:${profile.id}") {
        check(supportsManagement && !switching) { "File management unavailable or account switch still pending" }
        val updated = AuthProfile.decode(JSONValue.Obj(request("check", profile)))
        require(updated.id == profile.id) { "stage=check_response: profile mismatch" }
        applyProfile(updated)
    }

    private suspend fun reloadAfterChange(action: String) {
        try { loadManagement() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) {
            throw IllegalStateException("stage=status_after_$action: change saved, reloading the authorization list failed", error)
        }
    }

    suspend fun rename(profile: AuthProfile, name: String) = operation("rename:${profile.id}") {
        check(management?.nextProfileId != null && !switching) { "Profile library unavailable or account switch still pending" }
        val result = request("rename", profile, mapOf("displayName" to JSONValue.Str(name),
            "expectedName" to JSONValue.Str(profile.displayName.orEmpty())))
        require(result["status"]?.stringValue == "renamed" && result["profile"]?.stringValue == profile.id) { "stage=rename_acknowledgement: profile or status mismatch" }
        reloadAfterChange("rename")
    }

    suspend fun remove(profile: AuthProfile) = operation("remove:${profile.id}") {
        check(management?.nextProfileId != null && !switching) { "Profile library unavailable or account switch still pending" }
        val result = request("remove", profile)
        require(result["status"]?.stringValue == "removed" && result["profile"]?.stringValue == profile.id) { "stage=remove_acknowledgement: profile or status mismatch" }
        quota = quota - profile.id
        reloadAfterChange("remove")
    }

    suspend fun importFile(id: String, revision: String, name: String? = null, openInput: () -> InputStream) = fileOperation(if (revision == "missing") "add:$id" else "import:$id") {
        check(supportsManagement && !switching) { "File management unavailable or account switch still pending" }
        // Read a bounded credential file without parsing or logging its contents.
        val bytes = withContext(Dispatchers.IO) { openInput().use(::readAuthImport) }
        try {
            val uploaded = bytes.inputStream().use { WorkspaceFileUploadService(repository).upload("auth.json", bytes.size.toLong(), it) {} }
            val args = mutableMapOf<String, JSONValue>("sourcePath" to JSONValue.Str(uploaded.path))
            if (name != null) args["displayName"] = JSONValue.Str(name)
            val result = request("import", AuthProfile(id, revision, ""), args)
            require(result["status"]?.stringValue == "imported" && result["profile"]?.stringValue == id) { "stage=import_acknowledgement: profile or status mismatch" }
            quota = quota - id
            reloadAfterChange("import")
        } finally { bytes.fill(0) }
    }

    suspend fun exportFile(profile: AuthProfile, save: suspend (WorkspaceFileTransferService, WorkspaceFileInfo, String) -> Unit) = fileOperation("export:${profile.id}") {
        check(supportsManagement && !switching) { "File management unavailable or account switch still pending" }
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
    }

    suspend fun resetCard(profile: AuthProfile, creditId: String, ticket: String?) =
        repository.resetCard(profile.id, profile.revision, creditId, ticket)

    suspend fun query(profile: AuthProfile) = operation("query:${profile.id}") {
        check(!switching) { "Account switch is still pending" }
        if (failedSwitch?.runtime == profile.id && supportsProgress) {
            val response = request("runtime-quota")
            require(response["profile"]?.stringValue == profile.id) { "Live account changed; refresh the list" }
            val result = ProfileQuota.decode(response)
            liveQuota = result
            liveQuotaProfile = profile.id
            quota = quota + (profile.id to result)
            return@operation
        }
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
        dismissedSwitch = null
        failedSwitch = null
        switchStartedNanos = System.nanoTime()
        restartExpectedNanos = null
        sawSwitchDisconnect = false
        pendingOperationId = null
        switchDeadline = null
        restartStatus = null
        failure = null
        val p = request("switch", profile)
        if (p["kind"] != null) {
            acceptRestartRecord(p)
            return@operation
        }
        require(p["status"]?.stringValue == "restart_scheduled" && p["profile"]?.stringValue == profile.id) {
            "Switch outcome unknown; refresh account status"
        }
        val operationId = p["operationId"]?.stringValue
            ?: throw IllegalStateException("stage=switch-acknowledgement: operationId missing; switch outcome unknown, refresh status")
        followRestart(operationId, p["restartAfterSeconds"]?.longValue)
        if (management == null) profiles = profiles.filterNot { it.id == profile.id }
        available = null // Re-read after the connection recovers.
    }

    private suspend fun readLiveQuota() = operation("runtime-quota") {
        lastLiveRead = System.nanoTime()
        val response = request("runtime-quota")
        liveQuota = ProfileQuota.decode(response)
        liveQuotaProfile = response["profile"]?.stringValue
        if (liveQuotaProfile == failedSwitch?.runtime) liveQuotaProfile?.let { id ->
            liveQuota?.let { quota = quota + (id to it) }
        }
    }

    suspend fun pollRestart() {
        val expected = pendingOperationId
        if (!supportsProgress || expected == null) { refresh(); return }
        var refreshIdentity = false
        operation("restart-progress") {
            val records = request("restart-progress")["restartHistory"]?.arrayValue?.map(AuthRestartRecord::decode)
                ?: error("Missing restart history")
            if (pendingOperationId != expected) return@operation
            awaitingSwitchConnection = false
            restartHistory = records
            refreshIdentity = records.firstOrNull()?.let {
                it.operationId != expected || it.status !in setOf("restart_scheduled", "running")
            } ?: true
            // A delayed timer or interrupted worker needs the lock-owning reconciler.
            if (records.firstOrNull()?.status == "restart_scheduled" &&
                records.first().requestedAt?.isBefore(Instant.now().minusSeconds(75)) == true) refreshIdentity = true
        }
        if (refreshIdentity) refresh()
    }

    val listenerConfirmation get() = runtimeControl["listenerConfirmation"]?.stringValue
        ?: runtimeControl["confirmation"]?.stringValue.takeIf { runtimeControl["action"]?.stringValue == "adopt" }

    suspend fun adoptCodex(confirmation: String) = operation("restart") {
        check(!switching && listenerConfirmation == confirmation)
        acceptRestartRecord(request("adopt", extra = mapOf("confirmation" to JSONValue.Str(confirmation))))
    }

    suspend fun restartCodex(expectedRuntime: String? = listenerConfirmation) = operation("restart") {
        check(supportsManualRestart && !switching) { "Manual restart is unavailable" }
        val args = if (runtimeControl["action"]?.stringValue == "start")
            mapOf("startIfStopped" to JSONValue.Bool(true))
        else expectedRuntime?.let { mapOf("expectedRuntime" to JSONValue.Str(it)) }.orEmpty()
        acceptRestartRecord(request("restart", extra = args))
    }

    private fun acceptRestartRecord(response: Map<String, JSONValue>) {
        liveQuota = null
        liveQuotaProfile = null
        val record = AuthRestartRecord.decode(JSONValue.Obj(response))
        restartHistory = listOf(record) + restartHistory.filterNot { it.operationId == record.operationId }.take(9)
        switchProfileId = record.profile
        restartStatus = record.status
        failedSwitch = FailedAccountSwitch.fromList(mapOf("selectedProfile" to (response["profile"] ?: JSONValue.Null),
            "operationId" to JSONValue.Str(record.operationId), "restartStatus" to JSONValue.Str(record.status)), record)
        if (record.status == "restart_scheduled") followRestart(record.operationId, response["restartAfterSeconds"]?.longValue)
    }

    private fun followRestart(operationId: String, delaySeconds: Long?) {
        if (pendingOperationId != operationId) {
            lastVerificationWait = null
            awaitingSwitchConnection = false
            results.remove("restart-progress")
            results.remove("restart")
            switchStartedNanos = System.nanoTime()
            sawSwitchDisconnect = false
        }
        pendingOperationId = operationId
        restartStatus = "restart_scheduled"
        restartExpectedNanos = delaySeconds?.takeIf { it in 1..180 }?.let { System.nanoTime() + it * 1_000_000_000L }
        switchDeadline = Instant.now().plusSeconds(180)
    }

    fun checkSwitchDeadline(now: Instant = Instant.now()) {
        if (switching && switchDeadline?.let { !now.isBefore(it) } == true) {
            restartStatus = "verification_failed"
            val timeout = java.util.concurrent.TimeoutException("stage=restart-status-poll operation=$pendingOperationId timeoutMs=180000; server outcome unknown; refresh status before retry")
            lastVerificationWait?.let(timeout::initCause)
            pendingOperationId = null
            switchDeadline = null
            if (restartHistory.isNotEmpty()) {
                reportFileFailure(timeout, "restart")
            } else reportFileFailure(timeout, switchProfileId?.let { "switch:$it" } ?: "list")
        }
    }
}
