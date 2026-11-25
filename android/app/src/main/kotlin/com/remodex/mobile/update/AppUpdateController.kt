package com.remodex.mobile.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.SystemClock
import android.os.Build
import android.util.Log
import androidx.core.content.FileProvider
import com.remodex.mobile.BuildConfig
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import okhttp3.OkHttpClient

internal enum class UpdatePhase { IDLE, CHECKING, AVAILABLE, LATEST, CONNECTING, DOWNLOADING, CANCELLING, VERIFYING, READY, ERROR }
internal enum class UpdateRetry { CHECK, DOWNLOAD, INSTALL }
internal data class AppUpdateState(
    val phase: UpdatePhase = UpdatePhase.IDLE,
    val update: AppUpdateManifest? = null,
    val bytes: Long = 0,
    val total: Long = 0,
    val retry: UpdateRetry? = null,
    val error: String? = null,
    val launchInstaller: Boolean = false,
    val permissionDenied: Boolean = false,
) {
    val newerRelease: AppUpdateManifest? get() = update?.takeIf { it.versionCode > BuildConfig.VERSION_CODE }
    val busy: Boolean get() = phase in setOf(UpdatePhase.CHECKING, UpdatePhase.CONNECTING, UpdatePhase.DOWNLOADING, UpdatePhase.CANCELLING, UpdatePhase.VERIFYING)
}

private val settingsCheckIntervalMillis = TimeUnit.MINUTES.toMillis(5)

internal fun AppUpdateState.shouldCheckOnSettingsEntry(url: String, lastUrl: String?, elapsedSinceCheck: Long): Boolean {
    if (url.isBlank() || (url == lastUrl && elapsedSinceCheck < settingsCheckIntervalMillis)) return false
    return when (phase) {
        UpdatePhase.IDLE, UpdatePhase.LATEST, UpdatePhase.AVAILABLE -> true
        UpdatePhase.ERROR -> retry == UpdateRetry.CHECK
        else -> false
    }
}

/** Downloads survive navigation inside the app. Completed packages are reverified after process restart. */
internal class AppUpdateController(context: Context, private val client: OkHttpClient, private val connectionSource: () -> String = { "" }) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("remodex_app_updates", Context.MODE_PRIVATE)
    private val downloads = app.getSystemService(DownloadManager::class.java) // Cleanup of legacy tasks only.
    private val downloadClient = client.newBuilder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS).callTimeout(15, TimeUnit.MINUTES).retryOnConnectionFailure(false).build()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var operation: Job? = null
    private var lastCheckUrl: String? = null
    private var lastCheckStartedAt = 0L
    private val mutableState = MutableStateFlow(AppUpdateState())
    val state = mutableState.asStateFlow()
    val savedSource: String? get() = prefs.getString("source", null)?.takeIf { it.isNotBlank() }
    // Legacy installations saved only the URL. Honour it until the user explicitly follows the relay.
    val customSource: String? get() = savedSource?.takeIf { prefs.getBoolean("source_custom", true) }
    val source: String get() = customSource ?: connectionSource()
    val pairedSource: String get() = connectionSource()
    fun followConnectionSource() {
        if (state.value.busy) return
        prefs.edit().putBoolean("source_custom", false).apply()
        check(source)
    }
    private fun updateDir(): File = File(app.getExternalFilesDir(null)
        ?: throw AppUpdateFailure("download", "storage_unavailable", "App download storage is unavailable"), "updates").also {
        if (!it.isDirectory && !it.mkdirs()) throw AppUpdateFailure("download", "create_directory_failed", "Cannot create update folder")
    }
    private fun apkFile(update: AppUpdateManifest) = File(updateDir(), "update-${update.versionCode}-${update.sha256.take(16)}.apk")

    init {
        if (prefs.contains("download_manifest")) operation = scope.launch {
            mutableState.value = AppUpdateState(UpdatePhase.CHECKING)
            try {
                val update = updateJson.decodeFromString<AppUpdateManifest>(prefs.getString("download_manifest", "").orEmpty())
                when {
                    update.versionCode <= BuildConfig.VERSION_CODE -> {
                        withContext(Dispatchers.IO) { clearDownload() }
                        mutableState.value = AppUpdateState()
                    }
                    prefs.contains("download_id") -> {
                        withContext(Dispatchers.IO) { clearDownload() }
                        mutableState.value = AppUpdateState(UpdatePhase.AVAILABLE, update)
                    }
                    else -> showCandidate(update)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { fail("restore_download", e, if (state.value.update == null) UpdateRetry.CHECK else UpdateRetry.DOWNLOAD) }
        }
    }

    private suspend fun showCandidate(update: AppUpdateManifest) {
        val complete = withContext(Dispatchers.IO) {
            val file = apkFile(update)
            file.isFile && file.length() == update.sizeBytes
        }
        if (!complete) {
            mutableState.value = AppUpdateState(UpdatePhase.AVAILABLE, update)
            return
        }
        mutableState.value = AppUpdateState(UpdatePhase.VERIFYING, update)
        withContext(Dispatchers.IO) { verifyPackage(update) }
        mutableState.value = AppUpdateState(UpdatePhase.READY, update, update.sizeBytes, update.sizeBytes)
    }

    suspend fun checkOnSettingsEntry() {
        // A cold start may still be restoring the previously downloaded package.
        if (state.value.phase == UpdatePhase.CHECKING) operation?.join()
        val url = source.trim()
        if (state.value.shouldCheckOnSettingsEntry(url, lastCheckUrl, SystemClock.elapsedRealtime() - lastCheckStartedAt)) {
            check(url)
        }
    }

    fun check(url: String, saveSource: Boolean = false) {
        if (state.value.busy) return
        val knownRelease = state.value.newerRelease.takeIf { url.trim() == lastCheckUrl }
        lastCheckUrl = url.trim()
        lastCheckStartedAt = SystemClock.elapsedRealtime()
        val previous = operation
        mutableState.value = AppUpdateState(UpdatePhase.CHECKING, knownRelease)
        operation = scope.launch {
            previous?.cancelAndJoin()
            try {
                updateHttpUrl(url)
                if (saveSource) prefs.edit().putString("source", url.trim()).putBoolean("source_custom", true).apply()
                val update = withContext(Dispatchers.IO) { fetchAppUpdate(client, url, app.packageName, Build.VERSION.SDK_INT) }
                if (update.versionCode > BuildConfig.VERSION_CODE) {
                    showCandidate(update)
                } else {
                    withContext(Dispatchers.IO) { clearDownload() }
                    mutableState.value = AppUpdateState(UpdatePhase.LATEST, update)
                }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { fail("manifest", e, if (state.value.phase == UpdatePhase.VERIFYING) UpdateRetry.DOWNLOAD else UpdateRetry.CHECK) }
        }
    }

    fun download() {
        val update = state.value.update ?: return
        if (state.value.busy || update.versionCode <= BuildConfig.VERSION_CODE) return
        val previous = operation
        mutableState.value = AppUpdateState(UpdatePhase.CONNECTING, update, total = update.sizeBytes)
        operation = scope.launch {
            previous?.cancelAndJoin()
            try {
                clearDownload()
                if (!prefs.edit().putString("download_manifest", updateJson.encodeToString(update)).commit()) {
                    throw AppUpdateFailure("download", "save_download_failed", "Cannot save download state")
                }
                var lastProgress = 0L
                downloadAppUpdate(downloadClient, update, apkFile(update)) { bytes ->
                    val now = System.nanoTime()
                    if (bytes == 0L || bytes == update.sizeBytes || now - lastProgress >= 100_000_000L) {
                        mutableState.update { current ->
                            if (current.phase == UpdatePhase.CANCELLING) current
                            else AppUpdateState(UpdatePhase.DOWNLOADING, update, bytes, update.sizeBytes)
                        }
                        lastProgress = now
                    }
                }
                currentCoroutineContext().ensureActive()
                mutableState.value = AppUpdateState(UpdatePhase.VERIFYING, update)
                withContext(Dispatchers.IO) { verifyPackage(update) }
                mutableState.value = AppUpdateState(UpdatePhase.READY, update, update.sizeBytes, update.sizeBytes, launchInstaller = true)
            } catch (e: CancellationException) {
                if (e.suppressed.isNotEmpty()) recordFailure("cancel_download", e)
                throw e
            } catch (e: Exception) { fail("download", e) }
        }
    }

    fun cancel() {
        if (state.value.phase !in setOf(UpdatePhase.CONNECTING, UpdatePhase.DOWNLOADING)) return
        val previous = operation
        mutableState.value = state.value.copy(phase = UpdatePhase.CANCELLING)
        operation = scope.launch {
            previous?.cancelAndJoin()
            try {
                clearDownload()
                mutableState.value = AppUpdateState(UpdatePhase.AVAILABLE, state.value.update)
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) { fail("cancel_download", e) }
        }
    }

    fun requestInstaller() {
        mutableState.update { current ->
            if (current.phase == UpdatePhase.READY || current.retry == UpdateRetry.INSTALL)
                current.copy(launchInstaller = true, permissionDenied = false) else current
        }
    }

    fun finishInstallerRequest(expected: AppUpdateManifest, denied: Boolean = false) {
        mutableState.update { current ->
            if (current.update == expected) current.copy(launchInstaller = false, permissionDenied = denied) else current
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyPackage(update: AppUpdateManifest): File {
        val file = apkFile(update)
        verifyUpdateBytes(file, update)
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val installed = app.packageManager.getPackageInfo(app.packageName, flags)
        val archive = app.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
            ?: throw AppUpdateFailure("verify", "invalid_apk", "Downloaded file is not a valid APK")
        fun signers(info: PackageInfo): Set<String> {
            val certificates = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
            return certificates.orEmpty().map { signature ->
                MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
            }.toSet()
        }
        val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        validateApkIdentity(update, archive.packageName, version, signers(installed), signers(archive))
        if (version <= BuildConfig.VERSION_CODE) throw AppUpdateFailure("verify", "not_newer", "This version is already installed")
        return file
    }

    /** Recheck the file immediately before handing it to the system installer. */
    suspend fun installIntent(): Intent? {
        val update = state.value.update ?: return null
        if (state.value.phase != UpdatePhase.READY && state.value.retry != UpdateRetry.INSTALL) return null
        return try {
            val file = try { withContext(Dispatchers.IO) { verifyPackage(update) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { fail("verify", e, UpdateRetry.DOWNLOAD); return null }
            val uri = FileProvider.getUriForFile(app, "${app.packageName}.updates", file)
            // Keep the installer outside the task whose package is about to be replaced.
            // Its completion screen must survive removal of the old Remodex activity.
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (e: CancellationException) { throw e
        } catch (e: Exception) { fail("install", e, UpdateRetry.INSTALL); null }
    }

    fun installFailed(error: Exception) = fail("install", error, UpdateRetry.INSTALL)

    private fun clearDownload() {
        val id = prefs.getLong("download_id", -1)
        if (id >= 0) downloads.remove(id)
        prefs.edit().remove("download_id").remove("download_manifest").apply()
        updateDir().listFiles()?.forEach { file ->
            if (file.name.startsWith("update-") && file.extension == "apk" && !file.delete()) {
                throw AppUpdateFailure("cleanup", "delete_failed", "Cannot remove previous update file")
            }
        }
    }

    private fun recordFailure(stage: String, error: Exception) {
        val code = (error as? AppUpdateFailure)?.code ?: "unknown"
        val actualStage = (error as? AppUpdateFailure)?.stage ?: stage
        val diagnostic = "operation=app_update stage=$actualStage code=$code downloadId=${prefs.getLong("download_id", -1)}\n${error.stackTraceToString()}"
        Log.e("RemodexUpdate", diagnostic)
        try {
            val bounded = if (diagnostic.length > 32_000) diagnostic.take(32_000) + "\n[diagnostic truncated]" else diagnostic
            File(app.filesDir, "last-app-update-error.txt").writeText(bounded)
        } catch (secondary: Exception) {
            error.addSuppressed(secondary)
            Log.e("RemodexUpdate", "Failed to persist update diagnostic; primary failure retained", error)
        }
    }

    private fun fail(stage: String, error: Exception, retry: UpdateRetry = UpdateRetry.DOWNLOAD) {
        recordFailure(stage, error)
        val code = (error as? AppUpdateFailure)?.code ?: "unknown"
        val actualStage = (error as? AppUpdateFailure)?.stage ?: stage
        mutableState.value = state.value.copy(phase = UpdatePhase.ERROR, retry = retry, launchInstaller = false, error = "$actualStage / $code: ${error.message ?: error.javaClass.name}")
    }
}
