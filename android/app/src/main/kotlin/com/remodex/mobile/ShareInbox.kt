package com.remodex.mobile

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.core.content.IntentCompat
import androidx.core.os.BundleCompat
import java.util.UUID

internal data class SharedContent(val id: String, val uris: List<String>, val text: String, val mime: String?) {
    val savedSize get() = text.length + uris.sumOf { it.length + 16 } + (mime?.length ?: 0) + 128
}

internal val LocalShareInbox = staticCompositionLocalOf<ShareInbox?> { null }
internal val LocalChooseShareThread = staticCompositionLocalOf<() -> Unit> { {} }

internal class ShareInbox {
    private val pending = mutableStateListOf<SharedContent>()
    val first get() = pending.firstOrNull()

    fun receive(context: Context, intent: Intent) {
        val streams = linkedSetOf<Uri>()
        when (intent.action) {
            Intent.ACTION_SEND -> IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(streams::add)
            Intent.ACTION_SEND_MULTIPLE -> IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)?.let(streams::addAll)
            else -> return
        }
        val clip = intent.clipData
        for (index in 0 until (clip?.itemCount ?: 0)) clip?.getItemAt(index)?.uri?.let(streams::add)
        for (uri in streams) {
            val authority = uri.authority?.substringAfterLast('@').orEmpty()
            require(uri.scheme == "content" && authority.isNotBlank() && authority != "${context.packageName}.updates") {
                context.getString(R.string.share_invalid)
            }
            if (context.checkUriPermission(uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
                throw SecurityException(context.getString(R.string.share_no_access))
            }
        }
        val text = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
            ?: clip?.takeIf { it.itemCount == 1 }?.getItemAt(0)?.text?.toString().orEmpty()
        require(streams.isNotEmpty() || text.isNotBlank()) { context.getString(R.string.share_invalid) }
        val item = SharedContent(UUID.randomUUID().toString(), streams.map(Uri::toString), text, intent.type)
        // Android saves the activity and navigation state in a shared Binder transaction.
        require(item.savedSize.toLong() + pending.sumOf { it.savedSize } <= 64 * 1024) { context.getString(R.string.share_batch_large) }
        pending.add(item)
    }

    fun take(id: String): SharedContent? = if (first?.id == id) pending.removeAt(0) else null

    fun save(state: Bundle) {
        state.putParcelableArrayList("pendingShares", ArrayList(pending.map {
            Bundle().apply {
                putString("id", it.id)
                putStringArrayList("uris", ArrayList(it.uris))
                putString("text", it.text)
                putString("mime", it.mime)
            }
        }))
    }

    fun restore(state: Bundle) {
        pending.clear()
        BundleCompat.getParcelableArrayList(state, "pendingShares", Bundle::class.java).orEmpty().forEach {
            pending.add(SharedContent(requireNotNull(it.getString("id")), it.getStringArrayList("uris").orEmpty(),
                it.getString("text").orEmpty(), it.getString("mime")))
        }
    }
}
