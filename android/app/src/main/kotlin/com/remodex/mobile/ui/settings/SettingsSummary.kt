package com.remodex.mobile.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.ui.theme.RemodexAlertDialog

@Composable
internal fun SettingsSummary(icon: Int?, label: String, value: String, detail: String? = null,
    trailing: (@Composable () -> Unit)? = null, actions: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.extraLarge,
        color = colors.surface, contentColor = colors.onSurface) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (icon != null) Icon(painterResource(icon), null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = colors.onSurfaceVariant)
                trailing?.invoke()
            }
            Text(value, Modifier.semantics { heading() }, style = MaterialTheme.typography.headlineSmall)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            actions?.invoke()
        }
    }
}

@Composable
internal fun RelayAddressSetting(address: String, save: (String) -> Unit) {
    var draft by rememberSaveable { mutableStateOf<String?>(null) }
    val title = stringResource(R.string.settings_local_relay_host_override_label)
    SettingsNavigationRow(title, address.ifBlank { stringResource(R.string.settings_address_default) }, { draft = address })
    draft?.let { value ->
        RemodexAlertDialog(onDismissRequest = { draft = null }, title = { Text(title) },
            text = { OutlinedTextField(value = value, onValueChange = { draft = it }, singleLine = true,
                modifier = Modifier.fillMaxWidth(), label = { Text(title) },
                placeholder = { Text(stringResource(R.string.settings_local_relay_host_override_placeholder)) }) },
            confirmButton = { TextButton(onClick = { save(value.trim()); draft = null }) { Text(stringResource(R.string.auth_library_save)) } },
            dismissButton = { TextButton(onClick = { draft = null }) { Text(stringResource(android.R.string.cancel)) } })
    }
}
