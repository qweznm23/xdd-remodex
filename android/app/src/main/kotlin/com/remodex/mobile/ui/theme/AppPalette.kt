package com.remodex.mobile.ui.theme

import android.content.Context
import android.os.Build
import android.util.TypedValue
import android.view.ContextThemeWrapper
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.google.android.material.color.utilities.Hct
import com.google.android.material.color.utilities.MaterialDynamicColors
import com.google.android.material.color.utilities.SchemeTonalSpot
import com.remodex.mobile.R
import com.remodex.mobile.data.ThemePreferences

internal enum class PaletteMode(val label: Int) {
    BuiltIn(R.string.settings_accent_chat), System(R.string.palette_system), Custom(R.string.palette_custom)
}
internal data class AppPalette(val mode: PaletteMode = PaletteMode.BuiltIn, val seed: Int = 0xff007aff.toInt())
internal val LocalAppPalette = staticCompositionLocalOf { AppPalette() }
internal val LocalSelectPalette = staticCompositionLocalOf<(AppPalette) -> Unit> { error("RemodexTheme required") }

@Composable
internal fun AppPaletteProvider(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences(ThemePreferences.PREFS_NAME, Context.MODE_PRIVATE) }
    var selected by remember(prefs) { mutableStateOf(AppPalette(
        PaletteMode.entries.firstOrNull { it.name == prefs.getString("app_palette_mode", null) } ?: PaletteMode.BuiltIn,
        prefs.getInt("app_palette_seed", AppPalette().seed))) }
    CompositionLocalProvider(LocalAppPalette provides selected, LocalSelectPalette provides { choice ->
        prefs.edit().putString("app_palette_mode", choice.mode.name).putInt("app_palette_seed", choice.seed).apply()
        selected = choice
    }, content = content)
}

@Composable
internal fun appColorScheme(base: ColorScheme, dark: Boolean): ColorScheme {
    val selection = LocalAppPalette.current
    val context = LocalContext.current
    val resources = LocalConfiguration.current
    return remember(base, selection, dark, context, resources) {
        when (selection.mode) {
            PaletteMode.BuiltIn -> sessionColorScheme(base, dark)
            PaletteMode.Custom -> seedColorScheme(base, dark, selection.seed)
            PaletteMode.System -> if (Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            } else {
                val theme = ContextThemeWrapper(context, if (dark) android.R.style.Theme_DeviceDefault else android.R.style.Theme_DeviceDefault_Light)
                val attr = TypedValue()
                if (theme.theme.resolveAttribute(android.R.attr.colorAccent, attr, true)) {
                    val seed = if (attr.resourceId != 0) theme.getColor(attr.resourceId) else attr.data
                    seedColorScheme(base, dark, seed)
                } else sessionColorScheme(base, dark)
            }
        }
    }
}

internal fun seedColorScheme(base: ColorScheme, dark: Boolean, seed: Int): ColorScheme {
    val scheme = SchemeTonalSpot(Hct.fromInt(seed), dark, 0.0)
    val roles = MaterialDynamicColors()
    fun color(role: com.google.android.material.color.utilities.DynamicColor) = Color(role.getArgb(scheme))
    return with(roles) {
        base.copy(
            primary = color(primary()), onPrimary = color(onPrimary()), primaryContainer = color(primaryContainer()), onPrimaryContainer = color(onPrimaryContainer()),
            primaryFixed = color(primaryFixed()), onPrimaryFixed = color(onPrimaryFixed()), primaryFixedDim = color(primaryFixedDim()), onPrimaryFixedVariant = color(onPrimaryFixedVariant()),
            secondary = color(secondary()), onSecondary = color(onSecondary()), secondaryContainer = color(secondaryContainer()), onSecondaryContainer = color(onSecondaryContainer()),
            secondaryFixed = color(secondaryFixed()), onSecondaryFixed = color(onSecondaryFixed()), secondaryFixedDim = color(secondaryFixedDim()), onSecondaryFixedVariant = color(onSecondaryFixedVariant()),
            tertiary = color(tertiary()), onTertiary = color(onTertiary()), tertiaryContainer = color(tertiaryContainer()), onTertiaryContainer = color(onTertiaryContainer()),
            tertiaryFixed = color(tertiaryFixed()), onTertiaryFixed = color(onTertiaryFixed()), tertiaryFixedDim = color(tertiaryFixedDim()), onTertiaryFixedVariant = color(onTertiaryFixedVariant()),
            background = color(background()), onBackground = color(onBackground()), surface = color(surface()), onSurface = color(onSurface()),
            surfaceBright = color(surfaceBright()), surfaceDim = color(surfaceDim()), surfaceContainer = color(surfaceContainer()),
            surfaceContainerLow = color(surfaceContainerLow()), surfaceContainerHigh = color(surfaceContainerHigh()), surfaceContainerHighest = color(surfaceContainerHighest()),
            surfaceContainerLowest = color(surfaceContainerLowest()), surfaceVariant = color(surfaceVariant()), onSurfaceVariant = color(onSurfaceVariant()),
            outline = color(outline()), outlineVariant = color(outlineVariant()), surfaceTint = color(surfaceTint()),
            inversePrimary = color(inversePrimary()), inverseSurface = color(inverseSurface()), inverseOnSurface = color(inverseOnSurface()),
            error = color(error()), onError = color(onError()), errorContainer = color(errorContainer()), onErrorContainer = color(onErrorContainer()),
        )
    }
}

internal fun parsePaletteSeed(text: String): Int? {
    val hex = text.trim().removePrefix("#")
    if (hex.length != 6 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
    return hex.toInt(16) or 0xff000000.toInt()
}
