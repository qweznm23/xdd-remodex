package com.remodex.mobile.services

import com.remodex.mobile.core.model.JSONValue
import com.remodex.mobile.core.model.RPCObject
import com.remodex.mobile.core.model.WorkspaceImageReadResult
import com.remodex.mobile.data.CodexRepository

class WorkspaceImageService(
    private val repository: CodexRepository,
) {
    data class PreviewDataUrl(
        val path: String,
        val dataUrl: String,
        val byteLength: Long,
        val mtimeMs: Double,
        val previewMaxPixelDimension: Int?,
    )

    private data class PreviewCacheKey(
        val path: String,
        val maxPixelDimension: Long,
        val cwd: String?,
    )

    private val previewCache = mutableMapOf<PreviewCacheKey, PreviewDataUrl>()

    suspend fun readImage(params: RPCObject): WorkspaceImageReadResult =
        WorkspaceImageReadResult.fromJson(send("workspace/readImage", params))

    suspend fun readPreviewDataUrl(
        path: String,
        maxPixelDimension: Long,
        cwd: String? = null,
    ): PreviewDataUrl? {
        val trimmedPath = path.trim()
        if (trimmedPath.isEmpty()) return null
        val key = PreviewCacheKey(trimmedPath, maxPixelDimension, cwd)
        val cached = synchronized(previewCache) { previewCache[key] }
        val params =
            linkedMapOf<String, JSONValue>(
                "path" to JSONValue.Str(trimmedPath),
                "maxPixelDimension" to JSONValue.NumLong(maxPixelDimension),
            )
        cwd?.takeIf { it.isNotBlank() }?.let { params["cwd"] = JSONValue.Str(it) }
        if (cached != null) {
            params["ifByteLength"] = JSONValue.NumLong(cached.byteLength)
            params["ifMtimeMs"] = JSONValue.NumDouble(cached.mtimeMs)
        }

        val result = readImage(params)
        if (result.notModified && cached != null) return cached
        val base64 = result.dataBase64?.takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("Image response missing data stage=image_preview_response")
        val mime = result.mimeType.ifBlank { "image/png" }
        val fresh =
            PreviewDataUrl(
                path = result.path.ifBlank { trimmedPath },
                dataUrl = "data:$mime;base64,$base64",
                byteLength = result.byteLength,
                mtimeMs = result.mtimeMs,
                previewMaxPixelDimension = result.previewMaxPixelDimension,
            )
        synchronized(previewCache) {
            previewCache[key] = fresh
            if (fresh.path != trimmedPath) {
                previewCache[PreviewCacheKey(fresh.path, maxPixelDimension, cwd)] = fresh
            }
        }
        return fresh
    }

    private suspend fun send(
        method: String,
        params: RPCObject,
    ): RPCObject {
        val response =
            repository.sendRequest(
                method = method,
                params = JSONValue.Obj(params),
            )
        return response.result?.objectValue
            ?: error("Invalid response from bridge.")
    }
}
