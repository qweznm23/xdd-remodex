package com.remodex.mobile.update

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

/** Use the same app network path as the manifest, without DownloadManager scheduling. */
internal suspend fun downloadAppUpdate(
    client: OkHttpClient,
    update: AppUpdateManifest,
    destination: File,
    onProgress: (Long) -> Unit,
) = withContext(Dispatchers.IO) {
    val call = client.newCall(Request.Builder().url(updateHttpUrl(update.apkUrl)).build())
    val cancellation = launch(start = CoroutineStart.UNDISPATCHED) {
        try { awaitCancellation() } finally { call.cancel() }
    }
    var stage = "connect"
    var created = false
    try {
        call.execute().use { response ->
            if (response.code != 200) throw AppUpdateFailure("http", "http_${response.code}", "Update server returned HTTP ${response.code}")
            val body = response.body ?: throw AppUpdateFailure("http", "empty_response", "Empty APK response")
            if (body.contentLength() >= 0 && body.contentLength() != update.sizeBytes) {
                throw AppUpdateFailure("http", "size_mismatch", "APK response size does not match update information")
            }
            stage = "save"
            if (!destination.createNewFile()) throw AppUpdateFailure(stage, "file_exists", "Update destination already exists")
            created = true
            destination.outputStream().use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(65_536)
                    var count = 0L
                    onProgress(0)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        stage = "read"
                        val size = input.read(buffer)
                        if (size < 0) break
                        if (count + size > update.sizeBytes) throw AppUpdateFailure(stage, "size_mismatch", "APK exceeds its declared size")
                        stage = "save"
                        output.write(buffer, 0, size)
                        count += size
                        onProgress(count)
                    }
                    if (count != update.sizeBytes) throw AppUpdateFailure("read", "size_mismatch", "APK download ended before completion")
                }
                stage = "save"
            }
        }
        currentCoroutineContext().ensureActive()
    } catch (original: Throwable) {
        val error = if (original is IOException && original !is AppUpdateFailure && currentCoroutineContext().isActive) {
            AppUpdateFailure(stage, if (original is SocketTimeoutException) "timeout" else "io_error", original.message ?: original.javaClass.name, original)
        } else original
        if (created) {
            try {
                if (destination.exists() && !destination.delete()) throw IOException("Cannot delete incomplete update: ${destination.name}")
            } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        }
        // Cancellation keeps its semantics even when cancelling the HTTP call produced IOException.
        if (!currentCoroutineContext().isActive && error !is CancellationException) {
            try { currentCoroutineContext().ensureActive() }
            catch (cancelled: CancellationException) { cancelled.addSuppressed(error); throw cancelled }
        }
        throw error
    } finally {
        cancellation.cancel()
    }
}
