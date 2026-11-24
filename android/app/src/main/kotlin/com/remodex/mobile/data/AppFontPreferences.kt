package com.remodex.mobile.data

import android.content.Context
import com.remodex.mobile.BuildConfig
import com.remodex.mobile.core.model.AppFontStyle

object AppFontPreferences {
    private const val PREFS_NAME = "remodex_ui"
    val availableStyles = AppFontStyle.entries.filter { it != AppFontStyle.bundled || BuildConfig.BUNDLED_FONT_PAIR }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readFontStyle(context: Context): AppFontStyle {
        val raw = prefs(context).getString(AppFontStyle.storageKey, null) ?: return AppFontStyle.defaultStyle
        return availableStyles.firstOrNull { it.name == raw } ?: AppFontStyle.defaultStyle
    }

    fun writeFontStyle(
        context: Context,
        style: AppFontStyle,
    ) {
        prefs(context).edit().putString(AppFontStyle.storageKey, style.name).apply()
    }
}
