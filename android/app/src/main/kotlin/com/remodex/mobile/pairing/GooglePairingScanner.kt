package com.remodex.mobile.pairing

import android.content.Context
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import com.google.android.gms.common.moduleinstall.InstallStatusListener
import com.google.android.gms.common.moduleinstall.ModuleInstall
import com.google.android.gms.common.moduleinstall.ModuleInstallRequest
import com.google.android.gms.common.moduleinstall.ModuleInstallStatusUpdate.InstallState
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal class PlayServicesUnavailable(val code: Int) : Exception(
    "Google Play services unavailable: code=$code (${ConnectionResult(code).errorMessage ?: GoogleApiAvailability.getInstance().getErrorString(code)})",
)

private class GoogleTaskCanceled : Exception("Google Play services canceled the request")

internal class GooglePairingScanner(private val context: Context) : PairingScannerBackend {
    private val scanner by lazy {
        GmsBarcodeScanning.getClient(
            context,
            GmsBarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE)
                .enableAutoZoom().build(),
        )
    }
    private val modules by lazy { ModuleInstall.getClient(context) }

    override suspend fun prepare(onProgress: (ScannerPhase, Float?) -> Unit) {
        val availability = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(context)
        if (availability != ConnectionResult.SUCCESS) throw PlayServicesUnavailable(availability)
        if (modules.areModulesAvailable(scanner).awaitScannerTask().areModulesAvailable()) return

        val completed = CompletableDeferred<Unit>()
        var listening = true
        val listener = InstallStatusListener { update ->
            if (listening && !completed.isCompleted) {
                val progress = update.progressInfo?.let {
                    if (it.totalBytesToDownload > 0) {
                        (it.bytesDownloaded.toDouble() / it.totalBytesToDownload).toFloat().coerceIn(0f, 1f)
                    } else null
                }
                when (update.installState) {
                    InstallState.STATE_COMPLETED -> completed.complete(Unit)
                    InstallState.STATE_FAILED, InstallState.STATE_CANCELED -> completed.completeExceptionally(
                        ApiException(Status(update.errorCode,
                            "Scanner module install state=${update.installState} session=${update.sessionId} code=${update.errorCode}")),
                    )
                    InstallState.STATE_DOWNLOADING -> onProgress(ScannerPhase.DOWNLOADING, progress)
                    InstallState.STATE_DOWNLOAD_PAUSED -> onProgress(ScannerPhase.PAUSED, progress)
                    InstallState.STATE_INSTALLING -> onProgress(ScannerPhase.INSTALLING, progress)
                    else -> onProgress(ScannerPhase.PREPARING, progress)
                }
            }
        }
        var primary: Throwable? = null
        try {
            val response = modules.installModules(
                ModuleInstallRequest.newBuilder().addApi(scanner).setListener(listener).build(),
            ).awaitScannerTask()
            // An accepted install request is not proof that the module is installed.
            if (!response.areModulesAlreadyInstalled()) completed.await()
            check(modules.areModulesAvailable(scanner).awaitScannerTask().areModulesAvailable()) {
                "Scanner module reported completion but availability check returned false"
            }
        } catch (error: Throwable) {
            primary = error
            throw error
        } finally {
            listening = false
            try {
                withContext(NonCancellable) {
                    check(withTimeoutOrNull(5_000) { modules.unregisterListener(listener).awaitScannerTask(); true } == true) {
                        "Scanner module listener cleanup timed out after 5 seconds"
                    }
                }
            } catch (cleanup: Exception) {
                if (primary != null) primary.addSuppressed(cleanup) else throw cleanup
            }
        }
    }

    override suspend fun scan(): String? = try {
        scanner.startScan().awaitScannerTask().rawValue
            ?: error("Google scanner returned a barcode without rawValue")
    } catch (_: GoogleTaskCanceled) {
        null
    }
}

private suspend fun <T> Task<T>.awaitScannerTask(): T = suspendCancellableCoroutine { continuation ->
    addOnCompleteListener { task ->
        if (continuation.isActive) {
            when {
                task.isCanceled -> continuation.resumeWithException(GoogleTaskCanceled())
                task.isSuccessful -> continuation.resume(task.result)
                else -> continuation.resumeWithException(task.exception ?: IllegalStateException("Google task failed without an exception"))
            }
        }
    }
}
