package com.remodex.mobile.services

import com.remodex.mobile.core.model.GitBranchesWithStatusResult
import com.remodex.mobile.core.model.GitRepoSyncResult
import com.remodex.mobile.core.model.JSONValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

class GitToolbarStatusTest {
    private val noRepository = GitBranchesWithStatusResult(
        branches = emptyList(), branchesCheckedOutElsewhere = emptySet(), worktreePathByBranch = emptyMap(),
        localCheckoutPath = null, currentBranch = null, defaultBranch = null,
        status = GitRepoSyncResult.fromJson(mapOf("isRepo" to JSONValue.Bool(false), "state" to JSONValue.Str("not_initialized"))),
    )

    @Test
    fun slowCheckStopsAtOwnedDeadlineWithOperationAndDirectory() = runTest {
        val failure = assertFailsWith<GitToolbarStatusTimeout> {
            loadGitToolbarStatus("/slow-project", timeoutMillis = 100) {
                delay(101)
                noRepository
            }
        }
        assertEquals("/slow-project", failure.workingDirectory)
        assertEquals("git/branchesWithStatus", failure.operation)
        assertEquals(100L, failure.timeoutMillis)
    }

    @Test
    fun ordinaryFolderIsARealResultNotAnError() = runTest {
        val result = loadGitToolbarStatus("/notes") { noRepository }
        assertFalse(result.status!!.isRepo)
        assertEquals("not_initialized", result.status.state)
    }

    @Test
    fun transportFailureKeepsOriginalCauseAndSuppressedEvidence() = runTest {
        val failure = IllegalStateException("bridge closed", IllegalArgumentException("protocol evidence"))
        val cleanup = IllegalStateException("cleanup failed")
        failure.addSuppressed(cleanup)
        val actual = assertFailsWith<IllegalStateException> {
            loadGitToolbarStatus("/repo") { throw failure }
        }
        // Coroutine stack recovery may wrap a throwable, but must retain the original in its cause chain.
        val causes = generateSequence<Throwable>(actual) { it.cause }.toList()
        assertTrue(causes.any { it === failure })
        assertEquals("bridge closed", actual.message)
        assertSame(cleanup, causes.first { it === failure }.suppressed.single())
        assertTrue(causes.any { it.message == "protocol evidence" })
    }

    @Test
    fun callerCancellationIsNotReportedAsFailure() = runTest {
        val cancellation = CancellationException("switched project")
        val actual = assertFailsWith<CancellationException> {
            loadGitToolbarStatus("/old-project") { throw cancellation }
        }
        assertEquals("switched project", actual.message)
        assertTrue(generateSequence<Throwable>(actual) { it.cause }.any { it === cancellation })
    }

    @Test
    fun anotherScopesTimeoutIsNotReclassifiedAsGitDeadline() = runTest {
        assertFailsWith<TimeoutCancellationException> {
            withTimeout(10) {
                loadGitToolbarStatus("/repo", timeoutMillis = 100) { awaitCancellation() }
            }
        }
        assertFailsWith<TimeoutCancellationException> {
            loadGitToolbarStatus("/repo", timeoutMillis = 100) {
                withTimeout(10) { awaitCancellation() }
            }
        }
    }
}
