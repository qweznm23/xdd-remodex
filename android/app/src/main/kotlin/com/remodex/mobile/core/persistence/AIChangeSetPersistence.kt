package com.remodex.mobile.core.persistence

import android.content.Context
import com.remodex.mobile.core.model.AIChangeSet
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

class AIChangeSetPersistence(
    private val context: Context,
) {
    private val fileName = "codex-ai-change-sets-v1.json"
    private val macScopedDirName = "codex-ai-change-sets-mac-v1"

    private val json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    private val listSerializer = ListSerializer(AIChangeSet.serializer())

    // One Keystore alias per ledger prevents a missing scoped key from replacing a different ledger's key.
    private val revisions = kotlinx.coroutines.flow.MutableStateFlow(0L)
    internal val revision: kotlinx.coroutines.flow.StateFlow<Long> = revisions
    private val header = "XDD-LEDGER-1\n".encodeToByteArray()

    @Synchronized fun load(macDeviceId: String? = null): List<AIChangeSet> {
        return try { read(storeFile(macDeviceId)) } catch (failure: Exception) {
            android.util.Log.e("RemodexLedger", "load: recovery required; original retained", failure)
            emptyList() // Display only. Mutating operations independently verify the on-disk ledger.
        }
    }

    private fun read(file: File): List<AIChangeSet> {
        val data = try { Files.readAllBytes(file.toPath()) } catch (_: java.nio.file.NoSuchFileException) { return emptyList() }
        if (data.isEmpty()) return emptyList()
        val wrapped = data.size >= header.size && header.indices.all { data[it] == header[it] }
        val bytes = if (wrapped) {
            require(data.size >= header.size + 28) { "Truncated change ledger" }
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(file, false), GCMParameterSpec(128, data, header.size, 12))
            cipher.doFinal(data, header.size + 12, data.size - header.size - 12)
        } else data
        val rows = json.decodeFromString(listSerializer, bytes.decodeToString())
        if (!wrapped) write(file, rows)
        return rows
    }

    private fun key(file: File, create: Boolean): SecretKey {
        val alias = "remodex-ledger-" + hash(file.relativeTo(context.filesDir).path)
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        check(create && !store.containsAlias(alias)) { "Change ledger key unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build())
        }.generateKey()
    }

    @Synchronized fun update(macDeviceId: String?, transform: (List<AIChangeSet>) -> List<AIChangeSet>) {
        val file = storeFile(macDeviceId)
        val current = read(file)
        val next = transform(current)
        if (next != current) {
            write(file, next)
            revisions.value++
        }
    }

    private fun write(file: File, rows: List<AIChangeSet>) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(file, true))
        val encrypted = cipher.doFinal(json.encodeToString(listSerializer, rows).encodeToByteArray())
        check(cipher.iv.size == 12)
        val temp = Files.createTempFile(file.parentFile!!.toPath(), "ledger", ".tmp")
        try {
            temp.toFile().outputStream().use { stream ->
                stream.write(header); stream.write(cipher.iv); stream.write(encrypted); stream.fd.sync()
            }
            Files.move(temp, file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            try { Files.deleteIfExists(temp) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun storeFile(macDeviceId: String? = null): File {
        val dir = File(context.filesDir, context.packageName).apply { mkdirs() }
        val device = macDeviceId?.trim()?.takeIf { it.isNotEmpty() } ?: return File(dir, fileName)
        val scopedDir = dir.resolve(macScopedDirName).resolve(hash(device)).apply { mkdirs() }
        return File(scopedDir, fileName)
    }

    private fun hash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
