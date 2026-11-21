package com.remodex.mobile.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.remodex.mobile.core.model.CodexFileAttachment
import com.remodex.mobile.services.MAX_FILE_UPLOAD_BYTES
import com.remodex.mobile.services.FileTransferFailure
import com.remodex.mobile.services.WorkspaceFileUploadService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object TurnFileAttachmentCodec {
    suspend fun uploadAttachment(
        context: Context,
        uri: Uri,
        service: WorkspaceFileUploadService,
        tooLargeMessage: String,
        mimeType: String?,
        onProgress: suspend (Long, Long?) -> Unit,
    ): CodexFileAttachment = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var fileName = uri.lastPathSegment ?: "file"
        var size: Long? = null
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) fileName = cursor.getString(nameIndex) ?: fileName
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex).takeIf { it >= 0 }
            }
        }
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
