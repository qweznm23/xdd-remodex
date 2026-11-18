package com.remodex.mobile.services

import com.remodex.mobile.core.model.RPCObject
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class FileStreamTicket(val token: String, val key: ByteArray, val revision: String, val byteLength: Long) {
    companion object {
        fun parse(row: RPCObject): FileStreamTicket {
            val token = row["token"]?.stringValue.orEmpty()
            val revision = row["revision"]?.stringValue.orEmpty()
            val size = row["byteLength"]?.longValue
            val key = try { Base64.getDecoder().decode(row["keyBase64"]?.stringValue.orEmpty()) }
            catch (cause: IllegalArgumentException) { throw FileTransferFailure("stream_ticket", "invalid_key", "Invalid file transfer key", cause) }
            if (row["protocol"]?.stringValue != "aesgcm-records-v1" || !token.matches(Regex("[0-9a-f]{64}")) || !revision.matches(Regex("[0-9a-f]{64}")) || key.size != 32 || size == null || size < 0) {
                throw FileTransferFailure("stream_ticket", "invalid_ticket", "Invalid file transfer ticket")
            }
            return FileStreamTicket(token, key, revision, size)
        }
    }
}

/** Dedicated HTTP client: no chat socket, secure-session counters, or reconnect state is touched. */
internal class EncryptedFileStream(private val client: OkHttpClient = clientInstance) {
    suspend fun copy(baseUrl: String, ticket: FileStreamTicket, output: OutputStream, progress: (Long) -> Unit) = permits.withPermit {
        copyStream(baseUrl,ticket,output,progress,0,ticket.byteLength,false)
    }
    suspend fun range(baseUrl: String, ticket: FileStreamTicket, start: Long, length: Long): ByteArray = permits.withPermit {
        if (start < 0 || start > ticket.byteLength || length !in 1..4L*1024*1024) throw FileTransferFailure("stream_range","invalid_range","Invalid video byte range")
        val end = minOf(ticket.byteLength,start + length)
        val output = java.io.ByteArrayOutputStream((end-start).toInt())
        copyStream(baseUrl,ticket,output,{},start,end,true)
        output.toByteArray()
    }
    private suspend fun copyStream(baseUrl: String, ticket: FileStreamTicket, output: OutputStream, progress: (Long) -> Unit, start: Long, end: Long, ranged: Boolean) {
        val base = baseUrl.toHttpUrlOrNull() ?: throw FileTransferFailure("stream_route", "invalid_route", "File transfer route is unavailable")
        var offset = start
        var attempts = 0
        val recovered = mutableListOf<Throwable>()
        var lastProgress = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val url = base.newBuilder().encodedPath("/files/stream").query(null).fragment(null).addQueryParameter("offset",offset.toString()).apply { if (ranged) addQueryParameter("length",(end-offset).coerceAtLeast(1).toString()) }.build()
            val call = client.newCall(Request.Builder().url(url).header("Authorization","Bearer ${ticket.token}").build())
            try {
                coroutineScope {
                    val cancellation = launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) {
                        try { awaitCancellation() } finally { call.cancel() }
                    }
                    try {
                        withContext(Dispatchers.IO) {
                            val response = try { call.execute() } catch (cause: IOException) {
                                ensureActive(); throw transportFailure("stream_connect",cause,offset)
                            }
                            response.use {
                                if (it.code != 200) {
                                    val detail = it.peekBody(4096).string()
                                    throw FileTransferFailure("stream_http", "http_${it.code}", "File transfer HTTP ${it.code}: $detail")
                                }
                                val body = it.body ?: throw FileTransferFailure("stream_http","empty_response","Missing file stream")
                                val input = DataInputStream(body.byteStream())
                                fun readFrame(): Pair<Int, ByteArray> {
                                    try {
                                        val count = input.readInt()
                                        if (count !in 0..262144 || count.toLong() > end - offset) throw FileTransferFailure("stream_verify","invalid_length","Invalid file record length")
                                        val nonce = ByteArray(12); input.readFully(nonce)
                                        val encrypted = ByteArray(count + 16); input.readFully(encrypted)
                                        val bytes = try {
                                            Cipher.getInstance("AES/GCM/NoPadding").run {
                                                init(Cipher.DECRYPT_MODE,SecretKeySpec(ticket.key,"AES"),GCMParameterSpec(128,nonce))
                                                updateAAD("${ticket.token}:${ticket.revision}:$offset".toByteArray())
                                                doFinal(encrypted)
                                            }
                                        } catch (cause: java.security.GeneralSecurityException) { throw FileTransferFailure("stream_verify","authentication_failed","File record authentication failed",cause) }
                                        return count to bytes
                                    } catch (failure: FileTransferFailure) { throw failure }
                                    catch (cause: IOException) { ensureActive(); throw transportFailure("stream_read",cause,offset) }
                                }
                                while (true) {
                                    ensureActive()
                                    val (count,bytes) = readFrame()
                                    if (count == 0) {
                                        if (offset != end) throw FileTransferFailure("stream_verify","early_eof","File stream ended before expected size")
                                        break
                                    }
                                    try { output.write(bytes) }
                                    catch (cause: IOException) { throw FileTransferFailure("stream_save","storage_io","Cannot write downloaded file at byte $offset",cause) }
                                    offset += count
                                    val now = System.nanoTime()
                                    if (now - lastProgress > 100_000_000L) { progress(offset); lastProgress = now }
                                }
                                try { output.flush() } catch(cause: IOException) { throw FileTransferFailure("stream_save","storage_io","Cannot flush downloaded file",cause) }
                            }
                        }
                    } finally { cancellation.cancel() }
                }
                progress(offset)
                if (recovered.isNotEmpty()) recordRecoveredTransfer(recovered, offset)
                return
            } catch (cancelled: CancellationException) { recovered.forEach(cancelled::addSuppressed); throw cancelled }
            catch (failure: Exception) {
                currentCoroutineContext().ensureActive()
                if (failure !is FileTransferFailure || failure.code !in setOf("socket_io","read_timeout","incomplete_stream") || attempts++ >= 2) {
                    recovered.forEach(failure::addSuppressed); throw failure
                }
                recovered.add(failure)
                delay(300L * attempts)
            }
        }
    }
    private fun transportFailure(stage: String, cause: IOException, offset: Long): FileTransferFailure {
        val code = when(cause) {
            is java.net.SocketTimeoutException -> "read_timeout"
            is java.net.SocketException -> "socket_io"
            is java.io.EOFException -> "incomplete_stream"
            is java.net.UnknownHostException -> "dns_failure"
            is javax.net.ssl.SSLException -> "tls_error"
            is java.net.ProtocolException -> "protocol_error"
            else -> "io_unknown"
        }
        return FileTransferFailure(stage,code,"File transfer interrupted at byte $offset ($code)",cause)
    }
    private fun recordRecoveredTransfer(errors: List<Throwable>, bytes: Long) {
        // Retain recovered network evidence without any bearer ticket or URL.
        val diagnostic = FileTransferFailure("stream_resume","recovered","File transfer resumed after ${errors.size} interruptions; bytes=$bytes",errors.first())
        errors.drop(1).forEach(diagnostic::addSuppressed)
        java.util.logging.Logger.getLogger("RemodexFile").log(java.util.logging.Level.WARNING, diagnostic.message, diagnostic)
    }
    companion object {
        private val permits = Semaphore(3)
        private val clientInstance = OkHttpClient.Builder().connectTimeout(15,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS)
            .callTimeout(0,TimeUnit.SECONDS).retryOnConnectionFailure(false).followRedirects(false).followSslRedirects(false).build()
    }
}
