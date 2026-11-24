package com.remodex.mobile.ui.theme

import androidx.compose.foundation.layout.fillMaxSize
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.text.font.FontFamily
import com.remodex.mobile.data.CustomFonts
import com.remodex.mobile.data.FontSlot
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import com.remodex.mobile.core.model.AppFontStyle
import com.remodex.mobile.data.AppFontPreferences
import com.remodex.mobile.data.ThemePreferences

private val LightColorScheme =
    lightColorScheme(
        primary = RemodexLightPrimary,
        onPrimary = RemodexLightOnPrimary,
        primaryContainer = RemodexLightPrimaryContainer,
        onPrimaryContainer = RemodexLightOnPrimaryContainer,
        secondary = RemodexLightSecondary,
        onSecondary = RemodexLightOnSecondary,
        secondaryContainer = RemodexLightSecondaryContainer,
        onSecondaryContainer = RemodexLightOnSecondaryContainer,
        tertiary = RemodexLightSecondary,
        onTertiary = RemodexLightOnSecondary,
        tertiaryContainer = RemodexLightSecondaryContainer.copy(alpha = 0.94f),
        onTertiaryContainer = RemodexLightOnSecondaryContainer,
        background = RemodexLightBackground,
        onBackground = RemodexLightOnBackground,
        surface = RemodexLightSurface,
        onSurface = RemodexLightOnSurface,
        surfaceVariant = RemodexLightSurfaceVariant,
        onSurfaceVariant = RemodexLightOnSurfaceVariant,
        outline = RemodexLightOutline,
        outlineVariant = RemodexLightOutlineVariant,
        error = RemodexLightError,
        onError = RemodexLightOnError,
    )

private val DarkColorScheme =
    darkColorScheme(
        primary = RemodexDarkPrimary,
        onPrimary = RemodexDarkOnPrimary,
        primaryContainer = RemodexDarkPrimaryContainer,
        onPrimaryContainer = RemodexDarkOnPrimaryContainer,
        secondary = RemodexDarkSecondary,
        onSecondary = RemodexDarkOnSecondary,
        secondaryContainer = RemodexDarkSecondaryContainer,
        onSecondaryContainer = RemodexDarkOnSecondaryContainer,
        tertiary = RemodexDarkSecondary,
        onTertiary = RemodexDarkOnSecondary,
        tertiaryContainer = RemodexDarkSecondaryContainer.copy(alpha = 0.96f),
        onTertiaryContainer = RemodexDarkOnSecondaryContainer,
        background = RemodexDarkBackground,
        onBackground = RemodexDarkOnBackground,
        surface = RemodexDarkSurface,
        onSurface = RemodexDarkOnSurface,
        surfaceVariant = RemodexDarkSurfaceVariant,
        onSurfaceVariant = RemodexDarkOnSurfaceVariant,
        outline = RemodexDarkOutline,
        outlineVariant = RemodexDarkOutlineVariant,
        error = RemodexDarkError,
        onError = RemodexDarkOnError,
    )

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RemodexTheme(
    darkTheme: Boolean = false,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    var appFontStyle by remember(context) { mutableStateOf(AppFontPreferences.readFontStyle(context)) }
    DisposableEffect(context) {
        val prefs =
            context.applicationContext.getSharedPreferences(
                ThemePreferences.PREFS_NAME,
                android.content.Context.MODE_PRIVATE,
            )
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == AppFontStyle.storageKey || key == AppFontStyle.legacyStorageKey) {
                    appFontStyle = AppFontPreferences.readFontStyle(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }
    AppPaletteProvider {
        RemodexThemeContent(darkTheme, appFontStyle, content)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun RemodexThemeContent(darkTheme: Boolean, appFontStyle: AppFontStyle, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val colorScheme = appColorScheme(if (darkTheme) DarkColorScheme else LightColorScheme, darkTheme)
    RemodexSystemBars(
        background = colorScheme.background,
        darkTheme = darkTheme,
    )

    val fonts by CustomFonts.slots.collectAsState()
    LaunchedEffect(context) { CustomFonts.load(context.applicationContext) }
    val interfaceFace = fonts[FontSlot.Interface]?.font?.face
    val replyFace = fonts[FontSlot.Reply]?.font?.face
    val assets = context.assets
    val useBundledPair = appFontStyle == AppFontStyle.bundled
    val typography = remember(appFontStyle, interfaceFace, assets) {
        val font = interfaceFace?.let { FontFamily(it) } ?: if (useBundledPair) bundledFontFamily(assets, "interface") else null
        remodexTypography(appFontStyle, font)
    }
    val replyFont = remember(replyFace, useBundledPair, assets) {
        replyFace?.let { FontFamily(it) } ?: if (useBundledPair) bundledFontFamily(assets, "reply") else null
    }
    MaterialExpressiveTheme(
        colorScheme = colorScheme,
        typography = typography,
        shapes = RemodexShapes,
        content = {
            CompositionLocalProvider(LocalReplyFont provides replyFont) {
                androidx.compose.material3.Surface(modifier = androidx.compose.ui.Modifier.fillMaxSize(),
                    color = colorScheme.background, contentColor = colorScheme.onBackground, content = content)
            }
        },
    )
}

@Composable
private fun RemodexSystemBars(
    background: Color,
    darkTheme: Boolean,
) {
    val context = LocalContext.current
    DisposableEffect(context, background, darkTheme) {
        val window = context.findActivity()?.window
        if (window != null) {
            val color = background.toArgb()
            window.statusBarColor = color
            window.navigationBarColor = color
            WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
        onDispose {}
    }
}

private tailrec fun Context.findActivity(): Activity? =
    when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
