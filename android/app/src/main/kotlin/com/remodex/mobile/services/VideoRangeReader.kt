package com.remodex.mobile.services

import java.io.InterruptedIOException
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Random access for the player. Only verified ranges enter this bounded, per-playback memory cache. */
internal class VideoRangeReader(
    initial: Pair<String,FileStreamTicket>,
    private val renew: suspend () -> Pair<String,FileStreamTicket>,
    private val fetch: suspend (String,FileStreamTicket,Long,Long) -> ByteArray = { url,ticket,start,length -> EncryptedFileStream().range(url,ticket,start,length) },
) {
    private var route = initial
    val byteLength = initial.second.byteLength
    private val revision = initial.second.revision
    private val owner = SupervisorJob()
    private val mutex = Mutex()
    private val cache = object: LinkedHashMap<Long,ByteArray>(8,0.75f,true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long,ByteArray>?) = size > 8
    }

    fun read(position: Long, target: ByteArray, offset: Int, length: Int): Int {
        if (position < 0 || offset < 0 || length < 0 || offset > target.size - length) throw FileTransferFailure("video_read","invalid_range","Invalid video read range")
        if (length == 0) return 0
        if (position >= byteLength) return -1
        try {
            return runBlocking(Dispatchers.IO + owner) {
                mutex.withLock {
                    ensureActive()
                    val start = position / BLOCK * BLOCK
                    val block = synchronized(cache) { cache[start] } ?: run {
                        val count = minOf(BLOCK,byteLength-start)
                        val bytes = try { fetch(route.first,route.second,start,count) }
                        catch (expired: FileTransferFailure) {
                            if (expired.code != "http_401") throw expired
                            try {
                                val next = renew()
                                ensureActive()
                                if (next.second.revision != revision || next.second.byteLength != byteLength) throw FileTransferFailure("video_renew","file_changed","Video changed during playback")
                                route = next
                                fetch(route.first,route.second,start,count)
                            } catch (failure: Throwable) { failure.addSuppressed(expired); throw failure }
                        }
                        ensureActive()
                        if (bytes.size.toLong() != count) throw FileTransferFailure("video_read","invalid_length","Incomplete video range")
                        synchronized(cache) { cache[start] = bytes }
                        bytes
                    }
                    val inBlock = (position-start).toInt()
                    val count = minOf(length,block.size-inBlock)
                    block.copyInto(target,offset,inBlock,inBlock+count)
                    count
                }
            }
        } catch (cancelled: CancellationException) {
            // Media3's synchronous boundary uses InterruptedIOException for a cancelled read.
            throw InterruptedIOException("Video read cancelled stage=video_read").apply { initCause(cancelled) }
        }
    }
    fun close() { owner.cancel(CancellationException("Video player closed")); synchronized(cache) {cache.clear()} }
    companion object { private const val BLOCK = 1024L*1024 }
}
