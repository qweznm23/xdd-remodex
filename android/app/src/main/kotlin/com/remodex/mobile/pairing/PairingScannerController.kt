package com.remodex.mobile.pairing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull

internal enum class ScannerPhase { IDLE, PREPARING, DOWNLOADING, PAUSED, INSTALLING, SCANNING, CANCELED, FAILED }

internal data class ScannerFailure(val stage: String, val attempt: Int, val cause: Exception) {
    fun diagnostic(): String = "operation=pairing_scan stage=$stage attempt=$attempt\n${cause.stackTraceToString()}"
}

internal data class ScannerState(
    val phase: ScannerPhase = ScannerPhase.IDLE,
    val progress: Float? = null,
    val failure: ScannerFailure? = null,
) {
    val busy: Boolean get() = phase in setOf(
        ScannerPhase.PREPARING, ScannerPhase.DOWNLOADING, ScannerPhase.PAUSED,
        ScannerPhase.INSTALLING, ScannerPhase.SCANNING,
    )
}

internal interface PairingScannerBackend {
    suspend fun prepare(onProgress: (ScannerPhase, Float?) -> Unit)
    /** Null means the user dismissed the Google scanner, not a failure. */
    suspend fun scan(): String?
}

internal class ScannerPreparationTimeout(cause: CancellationException?) :
    Exception("Scanner module preparation exceeded 180 seconds", cause)

/** Owns one attempt; cancellation and callbacks from an old attempt cannot publish a payload. */
internal class PairingScannerController(private val backend: PairingScannerBackend) {
    private val mutableState = MutableStateFlow(ScannerState())
    val state = mutableState.asStateFlow()
    private var attempt = 0

    suspend fun scan(): String? {
        if (mutableState.value.busy) return null
        val currentAttempt = ++attempt
        var stage = "module_prepare"
        mutableState.value = ScannerState(ScannerPhase.PREPARING)
        try {
            var preparationCancellation: CancellationException? = null
            val prepared = withTimeoutOrNull(180_000) {
                try {
                    backend.prepare { phase, progress ->
                        if (attempt == currentAttempt && mutableState.value.busy && stage == "module_prepare") {
                            mutableState.value = ScannerState(phase, progress)
                        }
                    }
                    true
                } catch (cancel: CancellationException) {
                    preparationCancellation = cancel
                    throw cancel
                }
            }
            if (prepared != true) throw ScannerPreparationTimeout(preparationCancellation)
            currentCoroutineContext().ensureActive()
            stage = "scan"
            mutableState.value = ScannerState(ScannerPhase.SCANNING)
            val payload = backend.scan()
            currentCoroutineContext().ensureActive()
            if (payload != null && payload.isBlank()) error("Google scanner returned an empty barcode value")
            mutableState.value = ScannerState(if (payload == null) ScannerPhase.CANCELED else ScannerPhase.IDLE)
            return payload
        } catch (error: CancellationException) {
            mutableState.value = ScannerState(ScannerPhase.CANCELED)
            throw error
        } catch (error: Exception) {
            mutableState.value = ScannerState(
                ScannerPhase.FAILED,
                failure = ScannerFailure(stage, currentAttempt, error),
            )
            return null
        }
    }
}
