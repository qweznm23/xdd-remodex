package com.remodex.mobile.services

import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Shared, device-scoped thumbnails survive navigation and process restarts; never cache original attachments. */
internal class WorkspacePreviewCache(private val directory: File, private val limit: Long = 128L * 1024 * 1024) {
    private val locks = Array(16) { Mutex() }
    private val diskLock = Any()
    fun key(device: String, cwd: String?, path: String, dimension: Long): String = MessageDigest.getInstance("SHA-256")
        .digest(listOf(device,cwd.orEmpty(),path,dimension.toString()).joinToString("\u0000").toByteArray()).joinToString("") { "%02x".format(it) }
    suspend fun cached(key: String): File? = withContext(Dispatchers.IO) {
        File(directory,"$key.jpg").takeIf { it.isFile && it.length() in 1..2*1024*1024 }
    }
    // Reuse a different preview size only as an immediate display fallback. The caller
    // still fetches the requested size when connected; device and workspace must match.
    suspend fun cachedPreview(device: String, cwd: String?, path: String, dimension: Long): File? {
        for (size in listOf(dimension, 1600L, 512L).distinct()) {
            cached(key(device, cwd, path, size))?.let { return it }
        }
        return null
    }
    suspend fun load(key: String, force: Boolean = false, fetch: suspend () -> ByteArray): File = locks[(key.hashCode() and Int.MAX_VALUE) % locks.size].withLock {
        val old = cached(key)
        if (!force && old != null && System.currentTimeMillis() - old.lastModified() < 120_000) return@withLock old
        val bytes = fetch()
        if (bytes.isEmpty() || bytes.size > 2*1024*1024) throw FileTransferFailure("preview_cache","invalid_image_size","Invalid image thumbnail size")
        withContext(Dispatchers.IO) {
            synchronized(diskLock) {
                if (!directory.isDirectory && !directory.mkdirs()) throw FileTransferFailure("preview_cache","mkdir_failed","Cannot create image cache")
                val target = File(directory,"$key.jpg")
                val pending = File(directory,"$key.tmp")
                try {
                    pending.writeBytes(bytes)
                    java.nio.file.Files.move(pending.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING,java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                } catch (failure: Exception) {
                    try { if (pending.exists() && !pending.delete()) throw java.io.IOException("Cannot remove partial image cache") } catch(cleanup: Exception) { failure.addSuppressed(cleanup) }
                    throw FileTransferFailure("preview_cache","storage_io","Cannot save image cache",failure)
                }
                val files = directory.listFiles()?.filter { it.extension == "jpg" }?.sortedBy { it.lastModified() }.orEmpty()
                var total = files.sumOf { it.length() }
                for (file in files) {
                    if (total <= limit) break
                    if (file == target) continue
                    val size = file.length()
                    if (!file.delete()) throw FileTransferFailure("preview_cache","cleanup_failed","Cannot prune image cache")
                    total -= size
                }
                target
            }
        }
    }
}
