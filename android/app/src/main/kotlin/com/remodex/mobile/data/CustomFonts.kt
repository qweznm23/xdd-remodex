package com.remodex.mobile.data

import android.content.Context
import android.graphics.Typeface
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.util.UUID

internal enum class FontSlot { Interface, Reply }

internal data class ImportedFont(val name: String, val face: Typeface)
internal data class SlotFont(val font: ImportedFont? = null, val loadError: String? = null)

internal object CustomFonts {
    private val lock = Mutex()
    private val state = MutableStateFlow<Map<FontSlot, SlotFont>>(emptyMap())
    val slots = state.asStateFlow()
    private const val MAX_BYTES = 64L * 1024 * 1024

    private fun directory(context: Context, slot: FontSlot) =
        File(context.filesDir, "custom-fonts/${slot.name}")

    private fun face(file: File): Typeface {
        val signature = file.inputStream().use { java.io.DataInputStream(it).readInt() }
        require(signature == 0x00010000 || signature == 0x4f54544f) { "Expected a TTF or OTF font" }
        return Typeface.Builder(file).setFontVariationSettings("'wght' 400").build()
            ?: throw IOException("Android could not decode this font")
    }

    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        lock.withLock {
            for (slot in FontSlot.entries) {
                if (slot in state.value) continue
                val dir = directory(context, slot)
                val manifest = File(dir, "selected.json")
                val current = try {
                    if (manifest.exists()) {
                        val data = JSONObject(manifest.readText())
                        val id = UUID.fromString(data.getString("id"))
                        SlotFont(ImportedFont(data.getString("name"), face(File(dir, "$id.font"))))
                    } else SlotFont()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e("RemodexFonts", "slot=$slot stage=restore", e)
                    SlotFont(loadError = e.localizedMessage ?: e.javaClass.simpleName)
                }
                state.value += slot to current
            }
        }
    }

    suspend fun replace(context: Context, slot: FontSlot, uri: Uri?) = withContext(Dispatchers.IO) {
        lock.withLock {
            val dir = directory(context, slot)
            Files.createDirectories(dir.toPath())
            val manifest = File(dir, "selected.json")
            val next = if (uri == null) {
                Files.deleteIfExists(manifest.toPath())
                null
            } else {
                val resolver = context.contentResolver
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: uri.lastPathSegment ?: "Font"
                val id = UUID.randomUUID()
                val file = File(dir, "$id.font")
                val temp = File(dir, "$id.json.tmp")
                try {
                    val input = resolver.openInputStream(uri) ?: throw IOException("Document provider returned no stream")
                    input.use { source ->
                        file.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val count = source.read(buffer)
                                if (count < 0) break
                                total += count
                                require(total <= MAX_BYTES) { "Font exceeds 64 MiB" }
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    val imported = ImportedFont(name, face(file))
                    temp.outputStream().use {
                        it.write(JSONObject().put("id", id.toString()).put("name", name).toString().toByteArray())
                        it.fd.sync()
                    }
                    currentCoroutineContext().ensureActive()
                    Files.move(temp.toPath(), manifest.toPath(), ATOMIC_MOVE, REPLACE_EXISTING)
                    id to imported
                } catch (error: Throwable) {
                    for (partial in listOf(file, temp)) {
                        try { Files.deleteIfExists(partial.toPath()) }
                        catch (cleanup: Exception) { error.addSuppressed(cleanup) }
                    }
                    throw error
                }
            }
            state.value += slot to SlotFont(next?.second)
            for (old in dir.listFiles().orEmpty()) {
                if ((old.extension == "font" || old.name.endsWith(".json.tmp")) && old.nameWithoutExtension != next?.first?.toString()) {
                    try { Files.deleteIfExists(old.toPath()) }
                    catch (error: IOException) { Log.w("RemodexFonts", "slot=$slot stage=cleanup", error) }
                }
            }
        }
    }
}
