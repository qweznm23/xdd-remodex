package com.remodex.mobile.update

import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.*

class AppUpdateManifestTest {
    private val bytes = "valid apk bytes".toByteArray()
    private val checksum = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private val update = AppUpdateManifest("com.remodex.mobile", 21, "0.1.7-test", "app.apk", checksum, bytes.size.toLong())
    private fun fetch(body: String, status: Int = 200): AppUpdateManifest = MockWebServer().use { server ->
        server.enqueue(MockResponse().setResponseCode(status).setBody(body))
        fetchAppUpdate(OkHttpClient(), server.url("/updates/update.json").toString(), update.packageName, 35)
    }
    @Test fun resolvesRelativeApkAndKeepsReleaseNotes() {
        val result = fetch(updateJson.encodeToString(update.copy(notes = "修复连接")))
        assertTrue(result.apkUrl.endsWith("/updates/app.apk"))
        assertEquals("修复连接", result.notes)
    }
    @Test fun rejectsWrongPackageBadMetadataAndUnsupportedAndroid() {
        for (bad in listOf(update.copy(packageName = "other"), update.copy(sha256 = "bad"), update.copy(sizeBytes = 0), update.copy(versionCode = -1), update.copy(minSdk = 99))) {
            assertFailsWith<AppUpdateFailure> { fetch(updateJson.encodeToString(bad)) }
        }
    }
    @Test fun preservesHttpStatusAndParsingCause() {
        assertEquals("http_503", assertFailsWith<AppUpdateFailure> { fetch("offline", 503) }.code)
        val error = assertFailsWith<AppUpdateFailure> { fetch("{broken}") }
        assertEquals("manifest", error.stage)
        assertNotNull(error.cause)
    }
    @Test fun boundsManifestAndRejectsUnsafeUrls() {
        assertEquals("manifest_too_large", assertFailsWith<AppUpdateFailure> { fetch(" ".repeat(65_537)) }.code)
        for (url in listOf("file:///tmp/apk", "http://user:password@host/update.json", "https://host/#fragment")) {
            assertFailsWith<AppUpdateFailure> { updateHttpUrl(url) }
        }
    }
    @Test fun detectsTruncatedAndCorruptedFiles() {
        val path = Files.createTempFile("update-test", ".apk")
        try {
            Files.write(path, bytes); verifyUpdateBytes(path.toFile(), update)
            Files.write(path, bytes.copyOf(bytes.size - 1))
            assertEquals("size_mismatch", assertFailsWith<AppUpdateFailure> { verifyUpdateBytes(path.toFile(), update) }.code)
            Files.write(path, ByteArray(bytes.size))
            assertEquals("checksum_mismatch", assertFailsWith<AppUpdateFailure> { verifyUpdateBytes(path.toFile(), update) }.code)
        } finally { Files.delete(path) }
    }
    @Test fun verifiesVersionPackageAndSigningCertificate() {
        validateApkIdentity(update, update.packageName, update.versionCode, setOf("our-key"), setOf("our-key"))
        assertFailsWith<AppUpdateFailure> { validateApkIdentity(update, "other", 21, setOf("our-key"), setOf("our-key")) }
        assertFailsWith<AppUpdateFailure> { validateApkIdentity(update, update.packageName, 20, setOf("our-key"), setOf("our-key")) }
        assertEquals("signature_mismatch", assertFailsWith<AppUpdateFailure> { validateApkIdentity(update, update.packageName, 21, setOf("our-key"), setOf("other-key")) }.code)
    }
}
