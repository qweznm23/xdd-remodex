package com.remodex.mobile.ui.settings

import com.remodex.mobile.core.model.*
import com.remodex.mobile.core.error.CodexServiceError
import com.remodex.mobile.data.CodexRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class AuthProfilesStateTest {
    @Test fun quotaErrorStaysWithItsButtonAcrossOtherFileActionsAndListRefresh() = runTest {
        val report = JSONValue.Obj(mapOf("stage" to JSONValue.Str("query_rate_limits"),
            "rpc" to JSONValue.Obj(mapOf("code" to JSONValue.NumLong(-32603), "message" to JSONValue.Str("Access denied"))),
            "runtime" to JSONValue.Obj(mapOf("httpStatuses" to JSONValue.Arr(listOf(JSONValue.Str("403")))))))
        val error = CodexServiceError.RpcFailure(RPCError(-32000, "Query rejected",
            JSONValue.Obj(mapOf("errorCode" to JSONValue.Str("QUOTA_FAILED"), "diagnostic" to report))))
        val state = AuthProfilesState(repo { method, _ -> when (method.substringAfterLast('/')) {
            "list" -> managedList()
            "status" -> managedStatus()
            "query" -> throw error
            "check" -> JSONValue.Obj(profile.map + ("accessTokenState" to JSONValue.Str("expired")))
            else -> error(method)
        } })
        state.refresh()
        val row = state.profiles.single()
        state.query(row)
        state.checkFile(row)
        state.refresh()
        assertSame(error, state.result("query:${row.id}")?.error)
        assertNull(state.result("query:99"))
        assertNull(state.result("list")?.error)
        assertEquals("expired", state.result("file:${row.id}")?.validity)
        val summary = authFailureSummary(error)
        for (detail in listOf("Access denied", "query_rate_limits", "HTTP 403", "QUOTA_FAILED", "-32603")) {
            assertTrue(summary, summary.contains(detail))
        }
    }

    @Test fun accountLineIsIndependentOfAliasEvenWhenTheNamesMatch() {
        val row = AuthProfile("4", "1:2", "", displayName = "工作", accountName = "工作", email = "user@example.test")
        assertEquals("工作 · user@example.test", row.accountIdentity())
        assertEquals(row.accountIdentity(), row.copy(displayName = "备用").accountIdentity())
        assertEquals("工作", row.copy(email = null).accountIdentity())
        assertEquals("user@example.test", row.copy(accountName = null).accountIdentity())
        assertNull(row.copy(accountName = null, email = null).accountIdentity())
    }

    private fun managedList() = JSONValue.Obj(list().map + mapOf("managedBy" to JSONValue.Str("remodex"),
        "supportsImportExport" to JSONValue.Bool(true)))
    private fun managedStatus(row: JSONValue = profile) = JSONValue.Obj(mapOf(
        "managedBy" to JSONValue.Str("remodex"),
        "activeMaintenance" to JSONValue.Obj(mapOf("status" to JSONValue.Str("already_attempted_today"),
            "lastResult" to JSONValue.Obj(mapOf("diagnostic" to JSONValue.Obj(mapOf(
                "stage" to JSONValue.Str("refresh_current_request"), "rpcCode" to JSONValue.NumLong(403))))))),
        "currentProfile" to JSONValue.Obj(profile.map + ("id" to JSONValue.Str("current"))),
        "profiles" to JSONValue.Arr(listOf(JSONValue.Obj(row.objectValue!! + mapOf("active" to JSONValue.Bool(false),
            "lastMaintenance" to JSONValue.Obj(mapOf("status" to JSONValue.Str("failed"), "diagnostic" to JSONValue.Obj(mapOf(
                "stage" to JSONValue.Str("refresh_token"), "rpc" to JSONValue.NumLong(403))))))))),
        "scheduler" to JSONValue.Obj(mapOf("lastCheckAt" to JSONValue.Str("2026-11-18T04:52:00Z"),
            "nextCheck" to JSONValue.Str("2026-11-19T04:00:00+08:00")))))

    @Test fun managementAndFileCheckAreReadOnlyAndRetainActualFailureDetails() = runTest {
        val calls = mutableListOf<String>()
        val state = AuthProfilesState(repo { method, _ ->
            calls += method
            when (method.substringAfterLast('/')) {
                "list" -> managedList()
                "status" -> managedStatus()
                "check" -> JSONValue.Obj(profile.map + ("accessTokenState" to JSONValue.Str("expired")))
                else -> error("Unexpected mutation: $method")
            }
        })
        state.refresh(); state.checkFile(state.profiles.single())
        assertNull(state.failure)
        assertEquals(listOf("account/profiles/list", "account/profiles/status", "account/profiles/check"), calls)
        assertEquals("expired", state.profiles.single().accessTokenState)
        assertEquals("check:1", state.result("file:1")?.operation)
        assertTrue(state.management!!.entries.single().diagnostic!!.contains("403"))
        assertEquals("already_attempted_today", state.management!!.activeState)
        assertTrue(state.management!!.activeDiagnostic!!.contains("refresh_current_request"))
        assertTrue(state.management!!.activeDiagnostic!!.contains("403"))
        assertEquals(java.time.Instant.parse("2026-11-18T20:00:00Z"), state.management!!.nextCheck)
        assertFalse(state.management.toString().contains("must-not-display"))
    }

    @Test fun dynamicLibraryUsesServerIdsAndReadsNamesWithoutQuotaOrTokenRefresh() = runTest {
        var name = "工作"
        var removed = false
        val calls = mutableListOf<String>()
        val state = AuthProfilesState(repo { method, params ->
            val action = method.substringAfterLast('/')
            calls += action
            when (action) {
                "list" -> managedList()
                "status" -> {
                    val row = JSONValue.Obj(profile.map + mapOf("id" to JSONValue.Str("17"),
                        "displayName" to JSONValue.Str(name), "accountName" to JSONValue.Str("Test User"),
                        "email" to JSONValue.Str("user@example.test")))
                    val status = managedStatus(row).map.toMutableMap()
                    status["nextProfileId"] = JSONValue.Str("18")
                    if (removed) status["profiles"] = JSONValue.Arr(emptyList())
                    JSONValue.Obj(status)
                }
                "rename", "remove" -> {
                    val request = params!!.objectValue!!
                    assertEquals("17", request["profile"]?.stringValue)
                    if (action == "rename") {
                        assertEquals(name, request["expectedName"]?.stringValue)
                        name = request["displayName"]!!.stringValue!!
                    } else removed = true
                    JSONValue.Obj(mapOf("status" to JSONValue.Str(if (removed) "removed" else "renamed"),
                        "profile" to JSONValue.Str("17")))
                }
                else -> error(action)
            }
        })
        state.refresh()
        assertEquals("18", state.management?.nextProfileId)
        assertEquals("Test User", state.profiles.single().accountName)
        assertEquals("user@example.test", state.profiles.single().email)
        state.rename(state.profiles.single(), "私人")
        assertEquals("私人", state.profiles.single().displayName)
        state.remove(state.profiles.single())
        assertNull(state.failure)
        assertTrue(state.profiles.isEmpty())
        assertEquals(listOf("list", "status", "rename", "status", "remove", "status"), calls)
    }

    @Test fun priorFailedSwitchStillLoadsTheAuthorizationLibrary() = runTest {
        val state = AuthProfilesState(repo { method, _ -> when (method.substringAfterLast('/')) {
            "list" -> JSONValue.Obj(managedList().map + mapOf("restartStatus" to JSONValue.Str("failed"),
                "restartError" to JSONValue.Str("fixture restart error")))
            "status" -> JSONValue.Obj(managedStatus().map + ("nextProfileId" to JSONValue.Str("4")))
            else -> error(method)
        } })
        state.refresh()
        assertEquals("fixture restart error", state.failure?.message)
        assertEquals("4", state.management?.nextProfileId)
        assertEquals("failed", state.restartStatus)
    }

    @Test fun oversizedImportNeverUploadsAndPreservesTheExistingAuthorization() = runTest {
        val calls = mutableListOf<String>()
        val state = AuthProfilesState(repo { method, _ ->
            calls += method
            if (method.endsWith("list")) managedList() else managedStatus()
        })
        state.refresh()
        val before = state.profiles
        state.importFile("1", "1:200") { java.io.ByteArrayInputStream(ByteArray(65537)) }
        assertEquals("invalid_size", (state.failure as com.remodex.mobile.services.FileTransferFailure).code)
        assertEquals(before, state.profiles)
        assertEquals(2, calls.size)
        assertSame(state.failure, state.result("file:1")?.error)
    }

    @Test fun importUsesProtectedUploadAndExactRevisionBeforeReloadingStatus() = runTest {
        val payload = "synthetic-credential-file".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        var committed = false
        val updated = JSONValue.Obj(profile.map + ("revision" to JSONValue.Str("2:201")))
        val state = AuthProfilesState(repo { method, params ->
            val p = params?.objectValue.orEmpty()
            when (method) {
                "account/profiles/list" -> managedList()
                "account/profiles/status" -> managedStatus(if (committed) updated else profile)
                "workspace/uploadBegin" -> JSONValue.Obj(mapOf("uploadId" to JSONValue.Str("upload-1"), "chunkBytes" to JSONValue.NumLong(65536), "maxBytes" to JSONValue.NumLong(1000000)))
                "workspace/uploadChunk" -> {
                    assertArrayEquals(payload, java.util.Base64.getDecoder().decode(p["dataBase64"]!!.stringValue))
                    JSONValue.Obj(mapOf("nextOffset" to JSONValue.NumLong(payload.size.toLong())))
                }
                "workspace/uploadFinish" -> JSONValue.Obj(mapOf("path" to JSONValue.Str("/home/remodex_uploads/test/auth.json"), "byteLength" to JSONValue.NumLong(payload.size.toLong()), "sha256" to JSONValue.Str(digest)))
                "account/profiles/import" -> {
                    assertEquals("1:200", p["revision"]?.stringValue)
                    assertEquals("/home/remodex_uploads/test/auth.json", p["sourcePath"]?.stringValue)
                    committed = true
                    JSONValue.Obj(mapOf("status" to JSONValue.Str("imported"), "profile" to JSONValue.Str("1")))
                }
                else -> error(method)
            }
        })
        state.refresh()
        state.importFile("1", "1:200") { payload.inputStream() }
        assertNull(state.failure)
        assertTrue(committed)
        assertEquals("2:201", state.profiles.single().revision)
        assertEquals("import:1", state.result("file:1")?.operation)
    }

    @Test fun exportRefusesMismatchedSnapshotBeforeWritingDestination() = runTest {
        val state = AuthProfilesState(repo { method, _ -> when {
            method.endsWith("list") -> managedList()
            method.endsWith("status") -> managedStatus()
            method.endsWith("export") -> JSONValue.Obj(mapOf("profile" to JSONValue.Str("2")))
            else -> error("Unexpected transfer: $method")
        } })
        state.refresh()
        var saved = false
        state.exportFile(state.profiles.single()) { _, _, _ -> saved = true }
        assertFalse(saved)
        assertTrue(state.failure!!.message!!.contains("export_response"))
        assertSame(state.failure, state.result("file:1")?.error)
    }

    @Test fun refreshedRevisionAlsoUpdatesManagementAndIsUsedByExport() = runTest {
        val updated = JSONValue.Obj(profile.map + ("revision" to JSONValue.Str("2:200")))
        val state = AuthProfilesState(repo { method, params -> when (method) {
            "account/profiles/list" -> JSONValue.Obj(managedList().map + ("supportsTokenRefresh" to JSONValue.Bool(true)))
            "account/profiles/status" -> managedStatus()
            "account/profiles/refreshToken" -> JSONValue.Obj(mapOf("profile" to JSONValue.Str("1"), "status" to JSONValue.Str("refreshed"), "profileInfo" to updated))
            "account/profiles/export" -> {
                assertEquals("2:200", params?.objectValue?.get("revision")?.stringValue)
                JSONValue.Obj(mapOf("profile" to JSONValue.Str("1"), "path" to JSONValue.Str("/private/export/auth.json"),
                    "cwd" to JSONValue.Str("/private/export"), "byteLength" to JSONValue.NumLong(200)))
            }
            "workspace/fileInfo" -> JSONValue.Obj(mapOf("path" to JSONValue.Str("/private/export/auth.json"),
                "fileName" to JSONValue.Str("auth.json"), "byteLength" to JSONValue.NumLong(200), "revision" to JSONValue.Str("a".repeat(64))))
            else -> error("Unexpected request: $method")
        } })
        state.refresh(); state.refreshToken(state.profiles.single())
        assertEquals("2:200", state.management!!.entries.single().profile.revision)
        var saved = false
        state.exportFile(state.management!!.entries.single().profile) { _, info, cwd ->
            assertEquals("/private/export", cwd)
            assertEquals(200L, info.byteLength)
            saved = true
        }
        assertNull(state.failure)
        assertTrue(saved)
        assertEquals("export:1", state.result("file:1")?.operation)
    }
    private val profile = JSONValue.Obj(mapOf("id" to JSONValue.Str("1"), "path" to JSONValue.Str("/profiles/1/auth.json"),
        "revision" to JSONValue.Str("1:200"), "accessExpiresAt" to JSONValue.Str("2026-11-22T01:20:26Z"),
        "accessIssuedAt" to JSONValue.Str("2026-11-12T01:20:26Z"), "tokens" to JSONValue.Str("must-not-display")))
    private fun repo(handler: (String, JSONValue?) -> JSONValue): CodexRepository = Proxy.newProxyInstance(
        CodexRepository::class.java.classLoader, arrayOf(CodexRepository::class.java)) { _, method, args ->
        check(method.name == "sendRequest")
        try { RPCMessage(result = handler(args[0] as String, args[1] as JSONValue?)) }
        catch (error: Exception) {
            @Suppress("UNCHECKED_CAST")
            (args.last() as kotlin.coroutines.Continuation<RPCMessage>).resumeWith(Result.failure(error))
            kotlin.coroutines.intrinsics.COROUTINE_SUSPENDED
        }
    } as CodexRepository
    private fun list() = JSONValue.Obj(mapOf("status" to JSONValue.Str("available"), "profiles" to JSONValue.Arr(listOf(profile))))
    private fun quota() = JSONValue.Obj(mapOf("profile" to JSONValue.Str("1"), "profileInfo" to profile,
        "queriedAt" to JSONValue.Str("2026-11-13T01:00:00Z"), "quota" to JSONValue.Obj(mapOf(
            "rateLimits" to JSONValue.Obj(mapOf("primary" to JSONValue.Obj(mapOf("usedPercent" to JSONValue.NumLong(25), "windowDurationMins" to JSONValue.NumLong(10080))))),
            "rateLimitResetCredits" to JSONValue.Obj(mapOf("availableCount" to JSONValue.NumLong(2), "credits" to JSONValue.Arr(emptyList())))
        ))))
    @Test fun tokenRefreshIsExplicitAndUpdatesOnlyTheConfirmedRevision() = runTest {
        val metadata = profile.objectValue!!
        val updated = JSONValue.Obj(metadata + ("revision" to JSONValue.Str("2:201")))
        val calls = mutableListOf<String>()
        val state = AuthProfilesState(repo { method, params ->
            calls += method
            if (method.endsWith("/list")) JSONValue.Obj(list().objectValue!! + ("supportsTokenRefresh" to JSONValue.Bool(true)))
            else {
                assertEquals("1", params?.objectValue?.get("profile")?.stringValue)
                assertEquals("1:200", params?.objectValue?.get("revision")?.stringValue)
                JSONValue.Obj(mapOf("profile" to JSONValue.Str("1"), "status" to JSONValue.Str("refreshed"), "profileInfo" to updated))
            }
        })
        state.refresh()
        assertEquals(listOf("account/profiles/list"), calls)
        state.refreshToken(state.profiles.single())
        assertEquals("account/profiles/refreshToken", calls.last())
        assertNull(state.failure)
        assertEquals("2:201", state.lastRefreshed?.revision)
        assertEquals(state.lastRefreshed, state.profiles.single())
    }

    @Test fun metadataAndQuotaOnlyDecodePublicFieldsAndWeeklyRemaining() = runTest {
        val state = AuthProfilesState(repo { method, _ -> if (method.endsWith("list")) list() else quota() })
        state.refresh(); state.query(state.profiles.single())
        assertFalse(state.profiles.toString().contains("must-not-display"))
        assertEquals(75, state.quota["1"]!!.rows.single().window.remainingPercent)
        assertEquals("Weekly", state.quota["1"]!!.rows.single().label)
        assertEquals(2L, state.quota["1"]!!.details.resetCredits!!.availableCount)
        assertNotNull(state.quota["1"]!!.details.lastSuccessfulReadAt)
    }
    @Test fun failedQueryRetainsSnapshotAndServerDiagnostics() = runTest {
        var fail = false
        val remote = CodexServiceError.RpcFailure(RPCError(-32000, "denied", JSONValue.Obj(mapOf(
            "diagnostic" to JSONValue.Obj(mapOf("stage" to JSONValue.Str("query_rate_limits"), "code" to JSONValue.NumLong(401)))))))
        val state = AuthProfilesState(repo { method, _ -> if (fail) throw remote else if (method.endsWith("list")) list() else quota() })
        state.refresh(); state.query(state.profiles.single()); val before = state.quota
        fail = true; state.query(state.profiles.single())
        assertSame(remote, state.failure); assertEquals(before, state.quota)
        assertTrue(authProfileDiagnostic(state.failure!!).contains("query_rate_limits"))
        assertTrue(authProfileDiagnostic(state.failure!!).contains("401"))
        assertNull(state.busy)
    }
    @Test fun recoveryForRejectedQueryRetainsRpcAndSecondaryFailure() = runTest {
        val http = JSONValue.Obj(mapOf("httpStatuses" to JSONValue.Arr(listOf(JSONValue.NumLong(403)))))
        val report = JSONValue.Obj(mapOf("stage" to JSONValue.Str("quota_read"), "runtime" to http))
        val remote = CodexServiceError.RpcFailure(RPCError(-32000, "denied", JSONValue.Obj(mapOf("diagnostic" to report))))
        val failure = IllegalStateException("query failed", remote).apply { addSuppressed(IllegalStateException("cleanup failed")) }
        val state = AuthProfilesState(repo { method, _ -> if (method.endsWith("list")) list() else throw failure })
        state.refresh()
        state.query(state.profiles.single())
        val stored = state.result("query:1")!!.error!!
        val chain = generateSequence<Throwable>(stored) { it.cause }.toList()
        assertTrue(chain.any { it === failure })
        assertEquals(AuthRecovery(com.remodex.mobile.R.string.auth_recovery_denied), authRecovery("query", stored))
        assertSame(remote, failure.cause)
        assertTrue(chain.any { it === remote })
        val detail = authProfileDiagnostic(stored)
        for (fact in listOf("quota_read", "403", "cleanup failed", "-32000")) assertTrue(detail.contains(fact))
    }

    @Test fun timeoutRecoveryOnlyRepeatsReads() {
        val error = java.util.concurrent.TimeoutException("stage=request")
        assertEquals(AuthRecoveryAction.Query, authRecovery("query", error).action)
        for (op in listOf("switch", "refreshToken", "remove", "import", "restart")) {
            assertEquals(AuthRecoveryAction.Reload, authRecovery(op, error).action)
        }
    }

    @Test fun cancellationPropagatesAndDoesNotBecomeFailure() = runTest {
        val state = AuthProfilesState(repo { _, _ -> throw CancellationException("user left") })
        try { state.refresh(); fail("expected cancellation") } catch (expected: CancellationException) { }
        assertNull(state.failure); assertNull(state.busy)
    }
    @Test fun switchSendsExactSelectionAndRemovesSelectedProfileOnlyAfterAcknowledgement() = runTest {
        val state = AuthProfilesState(repo { method, params ->
            if (method.endsWith("list")) list() else {
                assertEquals("account/profiles/switch", method)
                assertEquals("1:200", params!!.objectValue!!["revision"]!!.stringValue)
                JSONValue.Obj(mapOf("status" to JSONValue.Str("restart_scheduled"), "profile" to JSONValue.Str("1"), "operationId" to JSONValue.Str("switch-1")))
            }
        })
        state.refresh(); state.switch(state.profiles.single())
        assertNull(state.failure); assertTrue(state.profiles.isEmpty()); assertEquals("restart_scheduled", state.restartStatus)
    }
    @Test fun rotatedProfileRevisionIsUsedByNextSwitch() = runTest {
        val updated = JSONValue.Obj(profile.map + ("revision" to JSONValue.Str("2:4100")))
        val state = AuthProfilesState(repo { method, params -> when {
            method.endsWith("list") -> list()
            method.endsWith("query") -> JSONValue.Obj(quota().map + ("profileInfo" to updated))
            else -> {
                assertEquals("2:4100", params!!.objectValue!!["revision"]!!.stringValue)
                JSONValue.Obj(mapOf("status" to JSONValue.Str("restart_scheduled"), "profile" to JSONValue.Str("1"), "operationId" to JSONValue.Str("switch-1")))
            }
        } })
        state.refresh(); state.query(state.profiles.single()); state.switch(state.profiles.single())
        assertNull(state.failure)
    }
    @Test fun anotherAccountsQuotaIsRejectedWithoutReplacingSnapshot() = runTest {
        val state = AuthProfilesState(repo { method, _ ->
            if (method.endsWith("list")) list() else JSONValue.Obj(quota().map + ("profile" to JSONValue.Str("2")))
        })
        state.refresh(); state.query(state.profiles.single())
        assertNotNull(state.failure); assertTrue(state.quota.isEmpty())
        assertEquals("1", state.profiles.single().id)
    }

    @Test fun switchProgressTracksAcknowledgedRestartDisconnectReconnectAndConfirmation() = runTest {
        var response = list()
        lateinit var state: AuthProfilesState
        state = AuthProfilesState(repo { method, _ ->
            if (method.endsWith("switch")) {
                assertEquals("1", state.switchProfileId)
                assertEquals(SwitchPhase.Submit, state.progressPhase(true))
                assertNotNull(state.switchStartedNanos)
                JSONValue.Obj(ack().map + ("restartAfterSeconds" to JSONValue.NumLong(10)))
            } else response
        })
        state.refresh()
        state.switch(state.profiles.single())
        val start = state.switchStartedNanos
        assertNotNull(state.restartExpectedNanos)
        assertEquals(SwitchPhase.AwaitRestart, state.progressPhase(true))
        state.observeSwitchConnection(false)
        assertEquals(SwitchPhase.Reconnect, state.progressPhase(false))
        state.observeSwitchConnection(true)
        assertEquals(SwitchPhase.Verify, state.progressPhase(true))
        response = switchList("restart_scheduled")
        state.refresh()
        assertEquals(start, state.switchStartedNanos)
        assertTrue(state.switching)
        response = switchList("ready")
        state.refresh()
        assertFalse(state.switching)
        assertEquals("ready", state.restartStatus)
        assertEquals("1", state.switchProfileId)
    }

    @Test fun restoringPendingSwitchDoesNotInventARestartCountdown() = runTest {
        val restored = JSONValue.Obj(switchList("restart_scheduled").map + ("selectedProfile" to JSONValue.Str("3")))
        val state = AuthProfilesState(repo { _, _ -> restored })
        state.refresh()
        assertEquals("3", state.switchProfileId)
        assertNotNull(state.switchStartedNanos)
        assertNull(state.restartExpectedNanos)
        assertEquals(SwitchPhase.AwaitRestart, state.progressPhase(true))
    }

    private fun ack() = JSONValue.Obj(mapOf("status" to JSONValue.Str("restart_scheduled"), "profile" to JSONValue.Str("1"), "operationId" to JSONValue.Str("switch-1")))
    private fun switchList(status: String, operation: String = "switch-1") = JSONValue.Obj(list().map + mapOf(
        "restartStatus" to JSONValue.Str(status), "operationId" to JSONValue.Str(operation),
        "restartError" to JSONValue.Str("stage=runtime-account-verification: selected auth not confirmed")))

    @Test fun manualRestartShowsPersistedOutcomeAndKeepsPriorFailure() = runTest {
        fun record(id: String, status: String, kind: String) = JSONValue.Obj(mapOf(
            "kind" to JSONValue.Str(kind), "operationId" to JSONValue.Str(id), "status" to JSONValue.Str(status),
            "requestedAt" to JSONValue.Str("2026-11-20T16:01:14.225637+08:00"),
        ))
        val failed = JSONValue.Obj(record("old", "failed", "switch").map + mapOf(
            "recovery" to JSONValue.Obj(mapOf("status" to JSONValue.Str("verification_required"))),
            "failure" to JSONValue.Obj(mapOf("stage" to JSONValue.Str("daemon_restart"))),
        ))
        fun response(vararg history: JSONValue) = JSONValue.Obj(list().map + mapOf(
            "supportsManualRestart" to JSONValue.Bool(true), "restartHistory" to JSONValue.Arr(history.toList()),
        ))
        var current = response(failed)
        val state = AuthProfilesState(repo { method, params ->
            if (method == "account/profiles/restart") {
                assertTrue(params!!.objectValue!!.isEmpty())
                record("manual-1", "restart_scheduled", "manual")
            } else current
        })
        state.refresh()
        assertTrue(state.verifyingIdentity)
        state.restartCodex()
        assertTrue(state.switching)
        assertEquals("1", state.profiles.single().id)
        current = JSONValue.Obj(response(record("manual-1", "ready", "manual"), failed).map + mapOf(
            "restartStatus" to JSONValue.Str("ready"), "operationId" to JSONValue.Str("manual-1"),
        ))
        state.refresh()
        assertFalse(state.switching)
        assertFalse(state.verifyingIdentity)
        assertEquals(listOf("manual-1", "old"), state.restartHistory.map { it.operationId })
        assertEquals(failed, state.restartHistory.last().detail)
    }

    @Test fun switchStaysPendingUntilTheSameOperationIsRuntimeVerified() = runTest {
        var response = list()
        val state = AuthProfilesState(repo { method, _ -> if (method.endsWith("switch")) ack() else response })
        state.refresh(); state.switch(state.profiles.single())
        assertTrue(state.switching)
        assertNotNull(state.switchDeadline)
        response = switchList("restart_scheduled")
        state.refresh()
        assertTrue(state.switching)
        response = switchList("ready")
        state.refresh()
        assertFalse(state.switching)
        assertNull(state.pendingOperationId)
        assertNull(state.switchDeadline)
        assertNull(state.failure)
    }

    @Test fun liveConfirmationClearsRestoredFailureWithoutStartingAnotherSwitch() = runTest {
        var status = "failed"
        val state = AuthProfilesState(repo { method, _ ->
            assertEquals("account/profiles/list", method)
            JSONValue.Obj(switchList(status).map + ("selectedProfile" to JSONValue.Str("1")))
        })
        state.refresh()
        assertTrue(state.verifyingIdentity)
        assertNotNull(state.result("switch:1")?.error)
        assertNull(state.pendingOperationId)
        status = "ready"
        state.refresh()
        assertFalse(state.verifyingIdentity)
        assertNull(state.result("switch:1"))
        assertNull(state.failure)
    }

    @Test fun anotherSwitchCannotCompleteThePendingOperation() = runTest {
        var response = list()
        val state = AuthProfilesState(repo { method, _ -> if (method.endsWith("switch")) ack() else response })
        state.refresh(); state.switch(state.profiles.single())
        response = switchList("ready", "someone-elses-switch")
        state.refresh()
        assertEquals("verification_failed", state.restartStatus)
        assertTrue(state.failure!!.message!!.contains("superseded"))
    }

    @Test fun disconnectedAndBusyWaitButDeadlineAndRealVerificationFailureRemainVisible() = runTest {
        var response = list()
        var failure: Exception? = null
        val state = AuthProfilesState(repo { method, _ ->
            failure?.let { throw it }
            if (method.endsWith("switch")) ack() else response
        })
        state.refresh(); state.switch(state.profiles.single())
        failure = com.remodex.mobile.core.transport.RelaySocketClosed(4004, "temporarily unavailable")
        state.refresh()
        assertTrue(state.switching)
        assertEquals(4004, (state.failure as com.remodex.mobile.core.transport.RelaySocketClosed).code)
        failure = CodexServiceError.Disconnected
        state.refresh()
        assertTrue(state.switching)
        failure = CodexServiceError.RpcFailure(RPCError(-32000, "maintenance locked", JSONValue.Obj(mapOf("errorCode" to JSONValue.Str("MANAGER_BUSY")))))
        state.refresh()
        assertTrue(state.switching)
        state.checkSwitchDeadline(state.switchDeadline!!)
        assertEquals("verification_failed", state.restartStatus)
        assertTrue(state.failure is java.util.concurrent.TimeoutException)
        failure = null
        response = switchList("verification_failed")
        state.refresh()
        assertTrue(state.failure!!.message!!.contains("runtime-account-verification"))
        response = switchList("ready")
        state.refresh()
        assertEquals("ready", state.restartStatus)
        assertNull(state.failure)
    }
}
