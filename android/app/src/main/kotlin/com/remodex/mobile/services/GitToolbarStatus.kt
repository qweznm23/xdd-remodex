package com.remodex.mobile.services

import com.remodex.mobile.core.model.GitBranchesWithStatusResult
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.withTimeoutOrNull

internal class GitToolbarStatusTimeout(
    val workingDirectory: String,
    val timeoutMillis: Long,
) : TimeoutException("git/branchesWithStatus timed out after ${timeoutMillis}ms for $workingDirectory") {
    val operation = "git/branchesWithStatus"
}

internal suspend fun loadGitToolbarStatus(
    workingDirectory: String,
    timeoutMillis: Long = 12_000L,
    fetch: suspend () -> GitBranchesWithStatusResult,
): GitBranchesWithStatusResult =
    withTimeoutOrNull(timeoutMillis) { fetch() }
        ?: throw GitToolbarStatusTimeout(workingDirectory, timeoutMillis)
