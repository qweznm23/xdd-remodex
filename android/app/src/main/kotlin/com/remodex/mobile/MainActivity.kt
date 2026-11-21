package com.remodex.mobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import com.remodex.mobile.core.model.AppLanguagePreference
import com.remodex.mobile.core.model.AppThemePreference
import com.remodex.mobile.core.model.UserBubbleColor
import com.remodex.mobile.data.LanguagePreferences
import com.remodex.mobile.data.ThemePreferences
import com.remodex.mobile.data.UserBubblePreferences
import com.remodex.mobile.core.notification.RemodexLocalNotificationPresenter
import com.remodex.mobile.ui.LocalAIChangeSetPersistence
import com.remodex.mobile.ui.LocalCodexRepository
import com.remodex.mobile.ui.LocalUserBubbleColor
import com.remodex.mobile.ui.RootScreen
import com.remodex.mobile.ui.theme.RemodexTheme

class MainActivity : ComponentActivity() {
    private val sharedContent = ShareInbox()
    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(LanguagePreferences.wrapContext(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState != null) sharedContent.restore(savedInstanceState)
        else handleLaunch(intent)
        setContent {
            val context = LocalContext.current
            val configuration = LocalConfiguration.current
            var themePref by remember { mutableStateOf(ThemePreferences.read(context)) }
            var languagePref by remember { mutableStateOf(LanguagePreferences.read(context)) }
            var userBubbleColor by remember { mutableStateOf(UserBubblePreferences.read(context)) }
            val systemLocaleTag = remember(configuration) {
                LanguagePreferences.currentSystemLocaleTag(context)
            }
            val systemDark = isSystemInDarkTheme()
            DisposableEffect(context) {
                val prefs =
                    context.applicationContext.getSharedPreferences(
                        ThemePreferences.PREFS_NAME,
                        android.content.Context.MODE_PRIVATE,
                    )
                val listener =
                    android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                        if (key == AppThemePreference.storageKey) {
                            themePref = ThemePreferences.read(context)
                        } else if (key == AppLanguagePreference.storageKey) {
                            languagePref = LanguagePreferences.read(context)
                        } else if (key == UserBubbleColor.storageKey) {
                            userBubbleColor = UserBubblePreferences.read(context)
                        }
                    }
                prefs.registerOnSharedPreferenceChangeListener(listener)
                onDispose {
                    prefs.unregisterOnSharedPreferenceChangeListener(listener)
                }
            }
            val darkTheme = themePref.isDark(systemDark)
            val localizedContext = remember(context, languagePref, systemLocaleTag) {
                LanguagePreferences.wrapContext(context, languagePref)
            }
            CompositionLocalProvider(
                LocalContext provides localizedContext,
                LocalCodexRepository provides AppContainer.codexRepository,
                LocalAIChangeSetPersistence provides AppContainer.aiChangeSetPersistence,
                LocalUserBubbleColor provides userBubbleColor,
                LocalShareInbox provides sharedContent,
            ) {
                RemodexTheme(darkTheme = darkTheme) {
                    com.remodex.mobile.ui.settings.AppUpdateInstallerHost()
                    RootScreen()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleLaunch(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        sharedContent.save(outState)
    }

    private fun handleLaunch(intent: Intent?) {
        if (intent?.action in setOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            try {
                sharedContent.receive(this, requireNotNull(intent))
            } catch (error: Exception) {
                android.util.Log.w("RemodexShare", "stage=receive", error)
                android.widget.Toast.makeText(this, error.localizedMessage ?: getString(R.string.share_invalid),
                    android.widget.Toast.LENGTH_LONG).show()
            }
        } else handleNotificationLaunchIntent(intent)
    }

    private fun handleNotificationLaunchIntent(intent: Intent?) {
        val tid =
            intent?.getStringExtra(RemodexLocalNotificationPresenter.EXTRA_THREAD_ID)?.trim()
                ?: return
        if (tid.isNotEmpty() && RemodexLocalNotificationPresenter.consumeLaunchToken(this, intent, tid)) {
            AppContainer.setPendingOpenThreadFromNotification(tid)
        }
    }
}
