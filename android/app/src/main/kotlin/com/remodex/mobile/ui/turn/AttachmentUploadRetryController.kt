package com.remodex.mobile.ui.turn

import com.remodex.mobile.services.FileTransferFailure
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Used on the UI dispatcher. Retains the selected source only while loading or awaiting retry. */
internal class AttachmentUploadRetryController(
    private val scope: CoroutineScope,
    private val onState: (String, TurnComposerAttachmentState) -> Unit,
    private val onFailure: (String, Int, Exception) -> Unit,
) {
    private class Transfer(
        val load: suspend (suspend (Long, Long?) -> Unit) -> TurnComposerAttachmentState,
        val releaseSource: () -> Unit,
        var job: Job? = null,
        var attempt: Int = 0,
        var failed: Boolean = false,
    )
    private val transfers = mutableMapOf<String, Transfer>()

    fun add(id: String, releaseSource: () -> Unit = {}, load: suspend (suspend (Long, Long?) -> Unit) -> TurnComposerAttachmentState) {
        remove(id)
        val transfer = Transfer(load, releaseSource)
        transfers[id] = transfer
        start(id, transfer)
    }

    fun retry(id: String) {
        val transfer = transfers[id] ?: return
        if (transfer.failed && transfer.job?.isActive != true) start(id, transfer)
    }

    private fun start(id: String, transfer: Transfer) {
        transfer.failed = false
        val attempt = ++transfer.attempt
        onState(id, TurnComposerAttachmentState.Loading)
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                val ready = transfer.load { bytes, total ->
                    if (transfers[id] === transfer && transfer.attempt == attempt) {
                        onState(id, TurnComposerAttachmentState.Uploading(bytes, total))
                    }
                }
                if (transfers[id] === transfer && transfer.attempt == attempt) {
                    onState(id, ready)
                    transfers.remove(id)
                    transfer.releaseSource()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                if (transfers[id] === transfer && transfer.attempt == attempt) {
                    val canRetry = !(failure is FileTransferFailure && failure.code == "upload_too_large")
                    transfer.failed = canRetry
                    onFailure(id, attempt, failure)
                    onState(id, TurnComposerAttachmentState.Failed(failure.message ?: failure.javaClass.name, canRetry = canRetry))
                    if (!canRetry) {
                        transfers.remove(id)
                        transfer.releaseSource()
                    }
                }
            } finally {
                if (transfer.attempt == attempt) transfer.job = null
            }
        }
        transfer.job = job
        job.start()
    }

    fun remove(id: String) {
        val transfer = transfers.remove(id) ?: return
        val job = transfer.job
        if (job == null) transfer.releaseSource()
        else {
            job.invokeOnCompletion { transfer.releaseSource() }
            job.cancel()
        }
    }
    fun retain(ids: Set<String>) { (transfers.keys - ids).forEach(::remove) }
    fun clear() { transfers.keys.toList().forEach(::remove) }
}
