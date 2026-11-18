package com.remodex.mobile.ui.turn

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import com.remodex.mobile.data.CodexRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

/** A selected conversation behind the full-screen drawer has not been read. */
internal val LocalConversationVisible = compositionLocalOf { true }

@Composable
internal fun ThreadReadObserver(repository: CodexRepository, threadId: String, atBottom: Boolean, contentCurrent: Boolean) {
    val deviceId by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    val activity by repository.sidebarActivity.collectAsStateWithLifecycle()
    val pages by repository.threadHistoryPaginationByThread.collectAsStateWithLifecycle()
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val visible = LocalConversationVisible.current && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val record = activity.takeIf { it.deviceId == deviceId }?.threads?.get(threadId)
    val loadedRevision = pages[threadId]?.loadedActivityRevision

    var readFailed by remember(threadId, deviceId, visible) { mutableStateOf(false) }
    // Only refresh for a new remote activity revision. Never observe token-by-token message deltas here.
    LaunchedEffect(repository, threadId, deviceId, visible, ready, record?.revision, loadedRevision) {
        if (!visible || !ready || record?.unread != true || loadedRevision == record.revision) return@LaunchedEffect
        try {
            val completed = withTimeoutOrNull(45_000) {
                repository.syncThreadHistory(threadId, force = true)
                true
            }
            if (completed == null) {
                Log.e("RemodexSidebarActivity", "operation=read-prepare stage=history-timeout thread=$threadId attempt=1",
                    TimeoutException("Read preparation timed out stage=history timeoutMs=45000"))
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            // History service retains the RPC code, cause and failure stage. Leave the marker unread.
        }
    }
    LaunchedEffect(repository, threadId, deviceId, visible, ready, atBottom, contentCurrent, record, loadedRevision) {
        val device = deviceId ?: return@LaunchedEffect
        if (readFailed || !ready || !visible || !atBottom || !contentCurrent || record?.unread != true || loadedRevision != record.revision) return@LaunchedEffect
        withFrameNanos { } // Let the loaded latest content reach the screen before consuming this revision.
        try { repository.markThreadRead(threadId, device, record.revision) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) {
            readFailed = true // Conflicts need a fresh user action, never an automatic retry.
            Log.e("RemodexSidebarActivity", "operation=mark-read stage=server-update thread=$threadId revision=${record.revision}", failure)
        }
    }
}
