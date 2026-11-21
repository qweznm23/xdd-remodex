package com.remodex.mobile.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Color
import com.google.android.material.color.utilities.*

// Built-in chat gray, also the upgrade default for installations with legacy accent settings.
internal fun sessionColorScheme(base: ColorScheme, dark: Boolean): ColorScheme {
    val scheme = SchemeMonochrome(Hct.fromInt(0xff595957.toInt()), dark, 0.0)
    val roles = MaterialDynamicColors()
    fun color(role: DynamicColor) = Color(role.getArgb(scheme))
    fun neutral(tone: Double): Color = Color(scheme.neutralPalette.tone(tone.toInt()))
    val accent = color(roles.primary())
    val onAccent = color(roles.onPrimary())
    val container = scheme.primaryPalette
    val layers = chatSurfaces(light = !dark)
    val page = layers.reading
    val card = layers.raised
    val block = if (dark) neutral(18.0) else layers.outgoing
    val ink = if (dark) Color(scheme.neutralPalette.tone(97)) else color(roles.onSurface())
    return base.copy(
        primaryFixed = neutral(90.0), onPrimaryFixed = neutral(10.0), primaryFixedDim = neutral(80.0), onPrimaryFixedVariant = neutral(30.0),
        secondaryFixed = neutral(90.0), onSecondaryFixed = neutral(10.0), secondaryFixedDim = neutral(80.0), onSecondaryFixedVariant = neutral(30.0),
        tertiaryFixed = neutral(90.0), onTertiaryFixed = neutral(10.0), tertiaryFixedDim = neutral(80.0), onTertiaryFixedVariant = neutral(30.0),
        inverseSurface = color(roles.inverseSurface()), inverseOnSurface = color(roles.inverseOnSurface()),
        inversePrimary = color(roles.inversePrimary()), surfaceBright = neutral(if (dark) 24.0 else 98.0),
        surfaceDim = neutral(if (dark) 6.0 else 87.0), surfaceContainerLowest = neutral(if (dark) 4.0 else 100.0), primary = accent, onPrimary = onAccent, surfaceTint = accent,
        primaryContainer = Color(container.tone(if (dark) 30 else 90)), onPrimaryContainer = Color(container.tone(if (dark) 90 else 30)),
        error = color(roles.error()), onError = color(roles.onError()),
        errorContainer = color(roles.errorContainer()), onErrorContainer = color(roles.onErrorContainer()),
        secondary = color(roles.secondary()), onSecondary = color(roles.onSecondary()),
        secondaryContainer = color(roles.secondaryContainer()), onSecondaryContainer = color(roles.onSecondaryContainer()),
        tertiary = color(roles.tertiary()), onTertiary = color(roles.onTertiary()),
        tertiaryContainer = color(roles.tertiaryContainer()), onTertiaryContainer = color(roles.onTertiaryContainer()),
        background = page, onBackground = ink, surface = card, onSurface = ink, surfaceVariant = block,
        surfaceContainer = block, surfaceContainerLow = card, surfaceContainerHigh = neutral(if (dark) 24.0 else 92.0),
        surfaceContainerHighest = neutral(if (dark) 27.0 else 90.0), onSurfaceVariant = color(roles.onSurfaceVariant()),
        outline = color(roles.outline()), outlineVariant = layers.outgoingEdge)
}

internal fun contrastRatio(first: Color, second: Color): Float {
    val a = first.luminance()
    val b = second.luminance()
    return (maxOf(a, b) + 0.05f) / (minOf(a, b) + 0.05f)
}

internal fun ColorScheme.readableLinkOn(background: Color): Color =
    if (contrastRatio(primary, background) >= 4.5f) primary else onPrimaryContainer
