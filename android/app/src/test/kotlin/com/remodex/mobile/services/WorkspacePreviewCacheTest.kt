package com.remodex.mobile.services
import java.nio.file.Files
import kotlinx.coroutines.*
import kotlin.test.*
class WorkspacePreviewCacheTest {
    @Test fun survivesNewInstancesSeparatesDevicesAndDeduplicatesConcurrentReads() = runBlocking {
        val dir=Files.createTempDirectory("image-cache").toFile()
        try {
            val cache=WorkspacePreviewCache(dir)
            val key=cache.key("device1","/repo","a.png",1600)
            assertNotEquals(key,cache.key("device2","/repo","a.png",1600))
            var calls=0
            coroutineScope { repeat(3) {launch {cache.load(key) {calls++;delay(20);byteArrayOf(1,2,3)}}} }
            assertEquals(1,calls)
            val reopened=WorkspacePreviewCache(dir)
            assertContentEquals(byteArrayOf(1,2,3),reopened.load(key) {error("cache should survive navigation/process restart")}.readBytes())
            assertFailsWith<IOExceptionForTest> {reopened.load(key,true) {throw IOExceptionForTest()}}
            assertContentEquals(byteArrayOf(1,2,3),reopened.cached(key)!!.readBytes())
        } finally {dir.deleteRecursively()}
        Unit
    }
    @Test fun diskUsageIsBoundedAndCancellationPropagates() = runBlocking {
        val dir=Files.createTempDirectory("image-cache").toFile()
        try {
            val cache=WorkspacePreviewCache(dir,10)
            for(i in 1..5) cache.load(cache.key("device",null,"$i",512)) {ByteArray(6)}
            assertTrue(dir.listFiles()!!.sumOf {it.length()} <= 10)
            assertFailsWith<CancellationException> {cache.load("cancel",true) {throw CancellationException("cancelled")}}
        } finally {dir.deleteRecursively()}
        Unit
    }
    @Test fun fullscreenPreviewCanReuseThumbnailWithoutCrossingDeviceOrWorkspace() = runBlocking {
        val dir = Files.createTempDirectory("image-cache-fallback").toFile()
        try {
            val cache = WorkspacePreviewCache(dir)
            val thumbnail = cache.load(cache.key("device", "/repo", "image.png", 512)) { byteArrayOf(1,2,3) }
            assertEquals(thumbnail, cache.cachedPreview("device", "/repo", "image.png", 1600))
            assertNull(cache.cachedPreview("other-device", "/repo", "image.png", 1600))
            assertNull(cache.cachedPreview("device", "/other-repo", "image.png", 1600))
            assertNull(cache.cachedPreview("device", "/repo", "other.png", 1600))
            val full = cache.load(cache.key("device", "/repo", "image.png", 1600)) { byteArrayOf(4,5,6) }
            assertEquals(full, cache.cachedPreview("device", "/repo", "image.png", 1600))
            assertEquals(thumbnail, cache.cachedPreview("device", "/repo", "image.png", 512))
        } finally { dir.deleteRecursively() }
        Unit
    }
    private class IOExceptionForTest:java.io.IOException("network failed")
}
