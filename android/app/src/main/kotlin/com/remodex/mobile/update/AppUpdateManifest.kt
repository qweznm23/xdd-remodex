package com.remodex.mobile.update

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request

internal const val MAX_MANIFEST_BYTES = 65_536L
internal val updateJson = Json { ignoreUnknownKeys = true }

@Serializable
internal data class AppUpdateManifest(
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val apkUrl: String,
    val sha256: String,
    val sizeBytes: Long,
    val minSdk: Int = 26,
    val notes: String = "",
)

internal class AppUpdateFailure(
    val stage: String,
    val code: String,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

internal fun updateHttpUrl(value: String): okhttp3.HttpUrl {
    val url = value.trim().toHttpUrlOrNull()
        ?: throw AppUpdateFailure("manifest", "invalid_url", "Invalid update address")
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null) {
        throw AppUpdateFailure("manifest", "invalid_url", "Update address must not contain credentials or a fragment")
    }
    return url
}

internal fun fetchAppUpdate(client: OkHttpClient, url: String, packageName: String, sdk: Int): AppUpdateManifest {
    val request = Request.Builder().url(updateHttpUrl(url)).header("Cache-Control", "no-cache").build()
    client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw AppUpdateFailure("manifest", "http_${response.code}", "Update server returned HTTP ${response.code}")
        val source = response.body?.source() ?: throw AppUpdateFailure("manifest", "empty_response", "Empty update response")
        source.request(MAX_MANIFEST_BYTES + 1)
        if (source.buffer.size > MAX_MANIFEST_BYTES) throw AppUpdateFailure("manifest", "manifest_too_large", "Update information exceeds 64 KiB")
        val manifest = try {
            updateJson.decodeFromString<AppUpdateManifest>(source.readUtf8())
        } catch (e: kotlinx.serialization.SerializationException) {
            throw AppUpdateFailure("manifest", "invalid_json", "Invalid update information", e)
        }
        if (manifest.packageName != packageName) throw AppUpdateFailure("manifest", "wrong_package", "Update belongs to another app")
        if (manifest.versionCode <= 0 || manifest.versionName.isBlank() || manifest.sizeBytes !in 1..536_870_912L ||
            !manifest.sha256.matches(Regex("[0-9a-fA-F]{64}")) || manifest.minSdk < 1) {
            throw AppUpdateFailure("manifest", "invalid_metadata", "Invalid update version, size or checksum")
        }
        if (manifest.minSdk > sdk) throw AppUpdateFailure("manifest", "unsupported_android", "This update requires a newer Android version")
        val apk = response.request.url.resolve(manifest.apkUrl)
            ?: throw AppUpdateFailure("manifest", "invalid_apk_url", "Invalid APK address")
        updateHttpUrl(apk.toString())
        return manifest.copy(apkUrl = apk.toString(), sha256 = manifest.sha256.lowercase())
    }
}

internal fun verifyUpdateBytes(file: File, update: AppUpdateManifest) {
    if (file.length() != update.sizeBytes) throw AppUpdateFailure("verify", "size_mismatch", "Downloaded file size does not match")
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(65_536)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    val actual = digest.digest().joinToString("") { "%02x".format(it) }
    if (actual != update.sha256) throw AppUpdateFailure("verify", "checksum_mismatch", "Downloaded file checksum does not match")
}

internal fun validateApkIdentity(update: AppUpdateManifest, packageName: String, version: Long, installedSigners: Set<String>, apkSigners: Set<String>) {
    if (packageName != update.packageName || version != update.versionCode) {
        throw AppUpdateFailure("verify", "apk_identity_mismatch", "APK package or version does not match the update")
    }
    if (installedSigners.isEmpty() || installedSigners != apkSigners) {
        throw AppUpdateFailure("verify", "signature_mismatch", "APK signing certificate differs from the installed app")
    }
}
