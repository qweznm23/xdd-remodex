package com.remodex.mobile.ui.settings

import androidx.compose.animation.core.updateTransition
import com.remodex.mobile.ui.shared.RemainingQuotaBar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.composables.icons.lucide.R as LucideR
import com.remodex.mobile.R
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.remodex.mobile.ui.theme.*

internal fun settingsColorScheme(base: ColorScheme, mode: PaletteMode): ColorScheme {
    if (mode != PaletteMode.BuiltIn) return base
    return if (base.background.luminance() >= 0.43f) {
        base.copy(background = Color(0xFFE9E9E6))
    } else {
        val card = Color(0xFF323231)
        base.copy(surface = card, surfaceContainerLow = card, surfaceContainer = card)
    }
}

@Composable
internal fun SettingsPaletteButton() {
    val selected = LocalAppPalette.current
    val select = LocalSelectPalette.current
    var expanded by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    if (editing) SeedDialog(selected.seed, onClose = { editing = false }, onApply = {
        select(AppPalette(PaletteMode.Custom, it))
        editing = false
    })
    val transition = updateTransition(expanded, label = "Settings palette")
    val glyph: @Composable () -> Unit = {
        Icon(painterResource(LucideR.drawable.lucide_ic_palette), null, tint = MaterialTheme.colorScheme.primary)
    }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(painterResource(LucideR.drawable.lucide_ic_palette), stringResource(R.string.settings_palette),
                tint = MaterialTheme.colorScheme.primary)
        }
        RemodexAnchoredMenu(transition, { expanded = false }, panelWidth = 190.dp, badge = glyph) {
            for (mode in PaletteMode.entries) {
                DropdownMenuItem(text = { Text(stringResource(mode.label)) },
                    trailingIcon = { if (selected.mode == mode) Icon(painterResource(LucideR.drawable.lucide_ic_check),
                        stringResource(R.string.settings_accent_selected)) },
                    onClick = {
                        expanded = false
                        if (mode == PaletteMode.Custom) editing = true else select(selected.copy(mode = mode))
                    })
            }
        }
    }
}

@Composable
private fun SeedDialog(seed: Int, onClose: () -> Unit, onApply: (Int) -> Unit) {
    fun hex(value: Int) = "%06X".format(java.util.Locale.ROOT, value and 0xffffff)
    var text by rememberSaveable(seed) { mutableStateOf(hex(seed)) }
    val parsed = parsePaletteSeed(text)
    RemodexAlertDialog(onDismissRequest = onClose, title = { Text(stringResource(R.string.palette_custom)) },
        text = { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(0xff007aff.toInt() to R.string.settings_accent_blue, 0xff44825c.toInt() to R.string.settings_accent_green,
                    0xff8866aa.toInt() to R.string.settings_accent_violet).forEach { (value, label) ->
                    val description = stringResource(label)
                    IconButton(onClick = { text = hex(value) }, modifier = Modifier.semantics { contentDescription = description }) {
                        Box(Modifier.size(26.dp).background(Color(value), CircleShape), contentAlignment = Alignment.Center) {
                            if (parsed == value) Icon(painterResource(LucideR.drawable.lucide_ic_check), null, Modifier.size(16.dp), tint = Color.White)
                        }
                    }
                }
            }
            OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), singleLine = true,
                label = { Text(stringResource(R.string.palette_hex)) }, placeholder = { Text("#007AFF") },
                isError = text.isNotEmpty() && parsed == null)
            if (parsed != null) {
                val base = MaterialTheme.colorScheme
                val preview = remember(base, parsed) { seedColorScheme(base, base.background.luminance() < .43f, parsed) }
                Surface(shape = MaterialTheme.shapes.medium, color = preview.primaryContainer, contentColor = preview.onPrimaryContainer) {
                    Text(stringResource(R.string.palette_preview), Modifier.fillMaxWidth().padding(12.dp))
                }
            }
        } },
        confirmButton = { TextButton(onClick = { parsed?.let(onApply) }, enabled = parsed != null) { Text(stringResource(android.R.string.ok)) } },
        dismissButton = { TextButton(onClick = onClose) { Text(stringResource(android.R.string.cancel)) } })
}

@Composable
internal fun CurrentAccountCard(name: String, identity: String?, verification: String?, weekly: Int?, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = open, shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
        color = colors.surface, contentColor = colors.onSurface) {
        Column {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_users), null, Modifier.size(18.dp), tint = colors.primary)
                    Text(stringResource(R.string.settings_manage_accounts), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                    Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), null, Modifier.size(18.dp))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                        Text(identity.orEmpty().takeUnless { it == name }.orEmpty(), minLines = 1, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            color = colors.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        verification?.let { Text(it, color = colors.onSurfaceVariant, style = MaterialTheme.typography.labelSmall) }
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text(weekly?.let { "$it%" } ?: "—", style = MaterialTheme.typography.headlineMedium)
                        Text(stringResource(R.string.settings_week_left), style = MaterialTheme.typography.labelMedium, color = colors.onSurfaceVariant)
                    }
                }
            }
            RemainingQuotaBar(weekly, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
        }
    }
}
