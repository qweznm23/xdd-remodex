package com.remodex.mobile.services

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.remodex.mobile.core.error.CodexServiceError
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal suspend fun saveWorkspaceFile(context: Context, transfer: WorkspaceFileTransferService, info: WorkspaceFileInfo, cwd: String?, selectedUri: Uri?, onProgress: (Long) -> Unit): Uri {
    val resolver = context.contentResolver
    val mediaStore = selectedUri == null && Build.VERSION.SDK_INT >= 29
    val uri = withContext(NonCancellable + Dispatchers.IO) {
        selectedUri ?: if (Build.VERSION.SDK_INT >= 29) {
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, info.fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, info.mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Remodex")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }) ?: throw FileTransferFailure("save", "create_failed", "Cannot create file in Downloads")
        } else throw FileTransferFailure("save", "destination_missing", "Choose a download location")
    }
    try {
        currentCoroutineContext().ensureActive()
        val output = withContext(NonCancellable + Dispatchers.IO) {
            resolver.openOutputStream(uri, "w") ?: throw FileTransferFailure("save", "open_failed", "Cannot open download destination")
        }
        output.use { transfer.copyTo(info, cwd, it, onProgress) }
        currentCoroutineContext().ensureActive()
        if (mediaStore) withContext(Dispatchers.IO) {
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null) != 1) {
                throw FileTransferFailure("publish", "publish_failed", "Cannot publish downloaded file")
            }
        }
        return uri
    } catch (error: Throwable) {
        withContext(NonCancellable + Dispatchers.IO) {
            try {
                val removed = if (mediaStore) resolver.delete(uri, null, null) > 0 else DocumentsContract.deleteDocument(resolver, uri)
                if (!removed) throw FileTransferFailure("cleanup", "delete_failed", "Cannot remove incomplete download: $uri")
            } catch (cleanup: Exception) { error.addSuppressed(cleanup) }
        }
        throw error
    }
}

internal fun recordFileTransferError(context: Context, operation: String, path: String, bytes: Long, error: Throwable) {
    val rpc = (error as? CodexServiceError.RpcFailure)?.rpcError
    val diagnostic = "operation=$operation path=$path bytes=$bytes stage=${(error as? FileTransferFailure)?.stage ?: operation} code=${(error as? FileTransferFailure)?.code ?: rpc?.code ?: "unknown"} rpcData=${rpc?.data}\n${error.stackTraceToString()}"
    Log.e("RemodexFile", diagnostic)
    try {
        File(context.filesDir, "last-file-transfer-error.txt").writeText(if (diagnostic.length > 32_000) diagnostic.take(32_000) + "\n[diagnostic truncated]" else diagnostic)
    } catch (secondary: Exception) {
        error.addSuppressed(secondary)
        Log.e("RemodexFile", "Failed to persist file diagnostic; primary failure retained", error)
    }
}
