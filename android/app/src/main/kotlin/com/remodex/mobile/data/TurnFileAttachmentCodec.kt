package com.remodex.mobile.data

import android.content.Context
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.remodex.mobile.core.model.CodexFileAttachment
import com.remodex.mobile.services.MAX_FILE_UPLOAD_BYTES
import com.remodex.mobile.services.FileTransferFailure
import com.remodex.mobile.services.WorkspaceFileUploadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TurnFileAttachmentCodec {
    data class Metadata(val name: String?, val size: Long?)

    fun readMetadata(resolver: ContentResolver, uri: Uri): Metadata {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val name = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val size = cursor.getColumnIndex(OpenableColumns.SIZE)
                return Metadata(
                    if (name >= 0) cursor.getString(name) else null,
                    if (size >= 0 && !cursor.isNull(size)) cursor.getLong(size).takeIf { it >= 0 } else null,
                )
            }
        }
        return Metadata(null, null)
    }

    suspend fun uploadAttachment(
        context: Context,
        uri: Uri,
        service: WorkspaceFileUploadService,
        tooLargeMessage: String,
        mimeType: String?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): CodexFileAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val metadata = readMetadata(resolver, uri)
        val fileName = metadata.name ?: uri.lastPathSegment ?: "file"
        val size = metadata.size
        if ((size ?: 0) > MAX_FILE_UPLOAD_BYTES) throw FileTransferFailure("upload_metadata", "upload_too_large", tooLargeMessage)
        onProgress(0, size)
        val input = resolver.openInputStream(uri)
            ?: throw FileTransferFailure("upload_open", "file_unavailable", "Cannot open selected file")
        val result = input.use {
            service.upload(fileName, size, it) { completed ->
                onProgress(completed, size)
            }
        }
        CodexFileAttachment(fileName = fileName, mimeType = mimeType, sizeBytes = result.byteLength.toInt(),
            textContent = null, sourceUri = uri.toString(), remotePath = result.path)
    }
}
