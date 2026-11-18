package com.remodex.mobile.update

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import kotlin.test.*

class AppUpdateDownloadTest {
    private val bytes = ByteArray(180_000) { (it % 251).toByte() }
    private fun update(server: MockWebServer) = AppUpdateManifest("com.remodex.mobile", 26, "test", server.url("/app.apk").toString(), "0".repeat(64), bytes.size.toLong())

    @Test fun downloadsBinaryDirectlyWithBoundedProgress() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val dir = Files.createTempDirectory("direct-update").toFile()
            try {
                val target = dir.resolve("app.apk")
                val progress = mutableListOf<Long>()
                downloadAppUpdate(OkHttpClient(), update(server), target) { progress.add(it) }
                assertContentEquals(bytes, target.readBytes())
                assertEquals(0L, progress.first())
                assertEquals(bytes.size.toLong(), progress.last())
                assertTrue(progress.zipWithNext().all { (a, b) -> b > a && b - a <= 65_536 })
                assertEquals("/app.apk", server.takeRequest().path)
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun httpAndLengthErrorsPreserveStageAndDoNotLeaveFiles() = runBlocking {
        for ((response, code) in listOf(
            MockResponse().setResponseCode(503) to "http_503",
            MockResponse().setBody("truncated") to "size_mismatch",
            MockResponse().setChunkedBody(Buffer().write(bytes.copyOf(200_000)), 4096) to "size_mismatch",
        )) {
            MockWebServer().use { server ->
                server.enqueue(response)
                val dir = Files.createTempDirectory("direct-update-error").toFile()
                try {
                    val target = dir.resolve("app.apk")
                    val error = assertFailsWith<AppUpdateFailure> { downloadAppUpdate(OkHttpClient(), update(server), target) {} }
                    assertEquals(code, error.code)
                    assertTrue(error.stage in setOf("http", "read"))
                    assertFalse(target.exists())
                } finally { dir.deleteRecursively() }
            }
        }
    }

    @Test fun stalledReadTimesOutWithCauseAndCleansPartialFile() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).setBodyDelay(2, TimeUnit.SECONDS))
            val dir = Files.createTempDirectory("direct-update-timeout").toFile()
            try {
                val target = dir.resolve("app.apk")
                val client = OkHttpClient.Builder().readTimeout(150, TimeUnit.MILLISECONDS).build()
                val error = assertFailsWith<AppUpdateFailure> { downloadAppUpdate(client, update(server), target) {} }
                assertEquals("read", error.stage)
                assertEquals("timeout", error.code)
                assertIs<java.net.SocketTimeoutException>(error.cause)
                assertFalse(target.exists())
            } finally { dir.deleteRecursively() }
        }
    }

    @Test fun cancelDuringReadStopsRequestAndCleansBeforeCompletion() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)).throttleBody(1024, 5, TimeUnit.SECONDS))
            val dir = Files.createTempDirectory("direct-update-cancel").toFile()
            try {
                val target = dir.resolve("app.apk")
                val started = CompletableDeferred<Unit>()
                val task = async { downloadAppUpdate(OkHttpClient(), update(server), target) { if (it > 0) started.complete(Unit) } }
                withTimeout(3000) { started.await() }
                withTimeout(3000) { task.cancelAndJoin() }
                assertTrue(task.isCancelled)
                assertFalse(target.exists())
            } finally { dir.deleteRecursively() }
        }
    }
}
