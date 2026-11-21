package com.remodex.mobile.ui.settings

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import com.remodex.mobile.R
import com.remodex.mobile.data.CustomFonts
import com.remodex.mobile.data.FontSlot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun CustomFontSetting(slot: FontSlot) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val slots by CustomFonts.slots.collectAsState()
    val current = slots[slot]
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<String?>(null) }
    fun update(uri: Uri?) {
        busy = true
        failure = null
        scope.launch {
            try {
                CustomFonts.replace(context, slot, uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("RemodexFonts", "slot=$slot stage=${if (uri == null) "reset" else "import"}", e)
                failure = e.localizedMessage ?: e.javaClass.simpleName
            } finally {
                busy = false
            }
        }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) update(uri)
    }
    Column(Modifier.padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(if (slot == FontSlot.Reply) R.string.font_reply else R.string.font_interface),
                    style = MaterialTheme.typography.bodyLarge)
                Text(current?.font?.name ?: stringResource(R.string.font_builtin), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (current?.font != null || current?.loadError != null) {
                TextButton(enabled = !busy, onClick = { update(null) }) { Text(stringResource(R.string.font_reset)) }
            }
            TextButton(enabled = !busy && current != null, onClick = { picker.launch(arrayOf("*/*")) }) {
                Text(stringResource(if (busy) R.string.font_working else R.string.font_import))
            }
        }
        (failure ?: current?.loadError)?.let {
            Text(stringResource(R.string.font_problem, it), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall)
        }
    }
}
