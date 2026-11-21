package com.remodex.mobile.ui.theme

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.material3.Typography
import com.remodex.mobile.R
import com.remodex.mobile.core.model.AppFontStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

private val GeistFamily =
    FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
        Font(R.font.geist_bold, FontWeight.Bold),
    )

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
internal val CodeFontFamily = FontFamily(
    listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
        Font(R.font.jetbrains_mono, weight = weight,
            variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
    },
)

internal val LocalReplyFont = staticCompositionLocalOf<FontFamily?> { null }

// The reference editor uses its regular sans body token with a font-relative line height.
internal val Typography.composerInput: TextStyle
    get() = bodyLarge.copy(fontSize = 17.sp, fontWeight = FontWeight.Normal,
        lineHeight = 1.4.em, letterSpacing = 0.sp)

private fun proseFontFamily(style: AppFontStyle): FontFamily =
    when (style) {
        AppFontStyle.system -> FontFamily.SansSerif
        AppFontStyle.geist -> GeistFamily
        AppFontStyle.jetBrainsMono -> CodeFontFamily
    }

fun remodexTypography(style: AppFontStyle, custom: FontFamily? = null): Typography {
    val prose = custom ?: proseFontFamily(style)
    val defaults = Typography()
    return Typography(
        displayLarge = defaults.displayLarge.copy(fontFamily = prose),
        displayMedium = defaults.displayMedium.copy(fontFamily = prose),
        displaySmall = defaults.displaySmall.copy(fontFamily = prose),
        headlineSmall = defaults.headlineSmall.copy(fontFamily = prose),
        labelSmall = defaults.labelSmall.copy(fontFamily = prose),
        headlineLarge =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.Bold,
                fontSize = 34.sp,
                lineHeight = 40.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.SemiBold,
                fontSize = 28.sp,
                lineHeight = 34.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.SemiBold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.Medium,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.Normal,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 16.sp,
            ),
        titleSmall = TextStyle(fontFamily = prose, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        labelLarge =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                lineHeight = 18.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = prose,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 14.sp,
            ),
    )
}
