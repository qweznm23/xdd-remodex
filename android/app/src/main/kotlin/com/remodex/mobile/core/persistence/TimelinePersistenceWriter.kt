package com.remodex.mobile.core.persistence

import com.remodex.mobile.core.model.CodexMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Coalesces complete immutable snapshots, never individual message deltas. */
internal class TimelinePersistenceWriter(
    scope: CoroutineScope,
    private val save: (Map<String, List<CodexMessage>>, String?) -> Unit,
    private val onFailure: (String?, Int, Exception) -> Unit,
    private val intervalMillis: Long = 500L,
) {
    private val pendingLock = Any()
    private val writeLock = Any()
    private val pending = linkedMapOf<String?, Map<String, List<CodexMessage>>>()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (signal in wake) {
                delay(intervalMillis)
                synchronized(writeLock) {
                    val batch = synchronized(pendingLock) {
                        pending.toMap().also { pending.clear() }
                    }
                    for ((deviceId, messages) in batch) {
                        try {
                            save(messages, deviceId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: Exception) {
                            onFailure(deviceId, messages.size, error)
                        }
                    }
                }
            }
        }
    }

    fun submit(messages: Map<String, List<CodexMessage>>, deviceId: String?) {
        synchronized(pendingLock) { pending[deviceId] = messages }
        wake.trySend(Unit)
    }

    /** Device switches must commit before loading another device's cached state. */
    fun saveNow(messages: Map<String, List<CodexMessage>>, deviceId: String?) {
        synchronized(writeLock) {
            synchronized(pendingLock) { pending.remove(deviceId) }
            save(messages, deviceId)
        }
    }
}
