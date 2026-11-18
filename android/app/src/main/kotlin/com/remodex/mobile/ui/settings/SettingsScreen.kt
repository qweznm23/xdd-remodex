package com.remodex.mobile.ui.settings

import android.content.Context
import android.content.Intent
import android.Manifest
import android.net.Uri
import android.provider.Settings
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.launch
import com.remodex.mobile.R
import com.remodex.mobile.AppContainer
import com.remodex.mobile.core.config.FeatureFlags
import com.remodex.mobile.core.model.AppFontStyle
import com.remodex.mobile.core.model.AppLanguagePreference
import com.remodex.mobile.core.model.AppThemePreference
import com.remodex.mobile.core.model.UserBubbleColor
import com.remodex.mobile.core.notification.LocalNotificationSettings
import com.remodex.mobile.core.readRemodexAppVersionName
import com.remodex.mobile.core.model.RelayHealthSnapshot
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.data.AppFontPreferences
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.LanguagePreferences
import com.remodex.mobile.data.PetCompanionStore
import com.remodex.mobile.data.ThemePreferences
import com.remodex.mobile.data.UserBubblePreferences
import com.remodex.mobile.services.RelayHealthClient
import com.remodex.mobile.services.SubscriptionService
import com.remodex.mobile.services.CodexService
import com.remodex.mobile.ui.theme.remodexScreenTopAppBarColors
import com.remodex.mobile.terminal.TerminalWindowsSetupGuide

private enum class SettingsPage(val title: Int) {
    HOME(R.string.nav_settings), APPEARANCE(R.string.settings_section_appearance),
    NOTIFICATIONS(R.string.settings_section_notifications), CONNECTION(R.string.settings_section_connection),
    COMPONENTS(R.string.settings_components), TERMINAL(R.string.terminal_windows_guide_title),
    PETS(R.string.settings_section_pet), UPDATES(R.string.app_update_title),
    ACCOUNT(R.string.settings_accounts_auth), SUBSCRIPTION(R.string.settings_section_subscription),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    repository: CodexRepository,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToWhatsNew: () -> Unit,
    onNavigateToTesterHq: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    fun back() { page = if (page == SettingsPage.TERMINAL) SettingsPage.COMPONENTS else SettingsPage.HOME }
    val navigateBack = { if (page == SettingsPage.HOME) onNavigateBack() else back() }
    BackHandler(onBack = navigateBack)
    val conn by repository.connectionState.collectAsStateWithLifecycle()
    val ready by repository.isSessionReady.collectAsStateWithLifecycle()
    val deviceId by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    val activeThread by repository.activeThreadId.collectAsStateWithLifecycle()
    var fontStyle by remember { mutableStateOf(AppFontPreferences.readFontStyle(context)) }
    var languagePreference by remember { mutableStateOf(LanguagePreferences.read(context)) }
    var themePreference by remember { mutableStateOf(ThemePreferences.read(context)) }
    var userBubbleColor by remember { mutableStateOf(UserBubblePreferences.read(context)) }
    var localRelayHostOverride by remember { mutableStateOf(AppContainer.sessionPersistence.loadLocalRelayHostOverride().orEmpty()) }
    val versionName = remember { readRemodexAppVersionName(context) }
    var host by remember(repository, deviceId) { mutableStateOf<SettingsHostStatus?>(null) }
    val authProfiles = remember(repository, deviceId) { AuthProfilesState(repository) }
    var hostError by remember(repository, deviceId) { mutableStateOf<Exception?>(null) }
    var hostLoading by remember(repository, deviceId) { mutableStateOf(false) }
    var hostRequestRevision by remember(repository, deviceId) { mutableStateOf(0L) }
    suspend fun refreshHost() {
        if (hostLoading || authProfiles.switching || !ready || conn !is ConnectionState.Connected) return
        hostLoading = true
        val requestRevision = ++hostRequestRevision
        var stage = "request"
        try {
            val response = repository.sendRequest("account/status/read", com.remodex.mobile.core.model.JSONValue.Obj(emptyMap()))
            stage = "decode"
            val decoded = SettingsHostStatus.decode(response.result)
            if (requestRevision == hostRequestRevision) {
                host = decoded
                hostError = null
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
        catch (error: Exception) {
            if (requestRevision == hostRequestRevision) hostError = error
            android.util.Log.e("RemodexSettings", "operation=account/status/read stage=$stage attempt=1", error)
        } finally { if (requestRevision == hostRequestRevision) hostLoading = false }
    }
    LaunchedEffect(page, deviceId, ready, conn) {
        if (page in setOf(SettingsPage.ACCOUNT, SettingsPage.COMPONENTS) && host == null && hostError == null) refreshHost()
    }
    LaunchedEffect(authProfiles.restartStatus) {
        if (authProfiles.restartStatus == "restart_scheduled" || authProfiles.restartStatus == "ready") {
            hostRequestRevision++ // Late replies from the old account cannot replace the new snapshot.
            hostLoading = false
            host = null
            hostError = null
        }
        if (authProfiles.restartStatus == "ready") {
            refreshHost()
        }
    }
    if (usageOpen) com.remodex.mobile.ui.turn.ThreadUsageStatusBottomSheet(true, { usageOpen = false }, activeThread.orEmpty(), repository)
    Scaffold(modifier = modifier, topBar = {
        TopAppBar(title = { Text(stringResource(page.title)) }, colors = remodexScreenTopAppBarColors(),
            navigationIcon = { IconButton(onClick = navigateBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.cd_navigate_back))
            } })
    }) { innerPadding ->
        pageStates.SaveableStateProvider(page.name) {
            Column(Modifier.fillMaxSize().padding(innerPadding).navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp)) {
                when (page) {
                    SettingsPage.HOME -> {
                        SettingsGroup(stringResource(R.string.settings_group_preferences)) {
                            SettingsNavigationRow(stringResource(R.string.settings_section_appearance),
                                stringResource(R.string.settings_appearance_summary, stringResource(settingsThemeTitleRes(themePreference)), stringResource(settingsLanguageTitleRes(languagePreference))),
                                { page = SettingsPage.APPEARANCE })
                            SettingsNavigationRow(stringResource(R.string.settings_section_notifications), stringResource(R.string.settings_notifications_run_completion_title),
                                { page = SettingsPage.NOTIFICATIONS })
                            SettingsNavigationRow(stringResource(R.string.settings_section_pet), stringResource(R.string.settings_pet_summary), { page = SettingsPage.PETS })
                        }
                        SettingsGroup(stringResource(R.string.settings_group_account_connection)) {
                            SettingsNavigationRow(stringResource(R.string.settings_accounts_auth), stringResource(R.string.settings_accounts_summary), { page = SettingsPage.ACCOUNT })
                            SettingsNavigationRow(stringResource(R.string.settings_section_usage), stringResource(R.string.settings_usage_summary), { usageOpen = true })
                            SettingsNavigationRow(stringResource(R.string.settings_section_connection), stringResource(if (conn is ConnectionState.Connected) R.string.sidebar_bridge_connected else R.string.settings_connection_summary), { page = SettingsPage.CONNECTION })
                            if (AppContainer.subscriptionService.isConfigured) SettingsNavigationRow(stringResource(R.string.settings_section_subscription), "", { page = SettingsPage.SUBSCRIPTION })
                        }
                        SettingsGroup(stringResource(R.string.settings_group_application)) {
                            SettingsNavigationRow(stringResource(R.string.app_update_title), versionName, { page = SettingsPage.UPDATES })
                            SettingsNavigationRow(stringResource(R.string.settings_components), stringResource(R.string.settings_components_summary), { page = SettingsPage.COMPONENTS })
                            SettingsNavigationRow(stringResource(R.string.nav_about_remodex), stringResource(R.string.settings_about_remodex_hint), {
                                scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("about_screen_opened", "settings") }
                                onNavigateToAbout()
                            })
                            SettingsNavigationRow(stringResource(R.string.nav_whats_new), "", {
                                scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("settings_whats_new_opened", "settings") }
                                onNavigateToWhatsNew()
                            })
                            if (FeatureFlags.betaEngagementEnabled) SettingsNavigationRow(stringResource(R.string.nav_tester_hq), "", {
                                scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("settings_tester_hq_entry_opened", "settings") }
                                onNavigateToTesterHq()
                            })
                        }
                    }
                    SettingsPage.APPEARANCE -> SettingsGroup {
                        SettingsPickerRow(stringResource(R.string.settings_theme_title), AppThemePreference.entries, themePreference,
                            { stringResource(settingsThemeTitleRes(it)) }, { stringResource(settingsThemeSubtitleRes(it)) }) {
                            themePreference = it; ThemePreferences.write(context, it)
                        }
                        SettingsPickerRow(stringResource(R.string.settings_language_title), AppLanguagePreference.entries, languagePreference,
                            { stringResource(settingsLanguageTitleRes(it)) }) { languagePreference = it; LanguagePreferences.write(context, it) }
                        SettingsPickerRow(stringResource(R.string.settings_font_title), AppFontStyle.entries, fontStyle,
                            { if (it == AppFontStyle.system) stringResource(R.string.settings_theme_system_title) else it.title },
                            { stringResource(when (it) { AppFontStyle.system -> R.string.font_system_subtitle; AppFontStyle.geist -> R.string.font_geist_subtitle; AppFontStyle.jetBrainsMono -> R.string.font_mono_subtitle }) }) {
                            fontStyle = it; AppFontPreferences.writeFontStyle(context, it)
                        }
                        SettingsPickerRow(stringResource(R.string.settings_user_bubble_title), UserBubbleColor.entries, userBubbleColor,
                            { stringResource(settingsUserBubbleTitleRes(it)) }) { color ->
                            val changed = color != userBubbleColor
                            userBubbleColor = color; UserBubblePreferences.write(context, color)
                            if (changed) scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("user_bubble_color_changed", "settings", refreshAfter = false) }
                        }
                    }
                    SettingsPage.NOTIFICATIONS -> SettingsGroup { SettingsNotificationSection(context) }
                    SettingsPage.PETS -> SettingsGroup { SettingsPetCompanionSection(repository, AppContainer.petCompanionStore) }
                    SettingsPage.SUBSCRIPTION -> SettingsGroup { SettingsSubscriptionSection(AppContainer.subscriptionService) }
                    SettingsPage.CONNECTION -> {
                        SettingsGroup(stringResource(R.string.settings_current_connection)) {
                            SettingsConnectionStatus(conn)
                            OutlinedTextField(value = localRelayHostOverride, onValueChange = {
                                localRelayHostOverride = it; AppContainer.sessionPersistence.saveLocalRelayHostOverride(it)
                            }, modifier = Modifier.fillMaxWidth(), label = { Text(stringResource(R.string.settings_local_relay_host_override_label)) },
                                placeholder = { Text(stringResource(R.string.settings_local_relay_host_override_placeholder)) }, singleLine = true)
                        }
                        SettingsGroup { SettingsRelayHealthSection(AppContainer.relayHealthClient, localRelayHostOverride) }
                    }
                    SettingsPage.ACCOUNT -> {
                        SettingsGroup(stringResource(R.string.settings_current_account)) {
                            HostRefreshStatus(hostLoading, hostError, ready && conn is ConnectionState.Connected) { scope.launch { refreshHost() } }
                            SettingsValueRow(stringResource(R.string.settings_account_identity), host?.email, host != null)
                            SettingsValueRow(stringResource(R.string.settings_account_status), host?.let { stringResource(when(it.status) {
                                "authenticated" -> R.string.settings_auth_valid
                                "pending_login" -> R.string.settings_auth_pending
                                "expired" -> R.string.settings_auth_expired
                                "not_logged_in" -> R.string.settings_auth_missing
                                else -> R.string.usage_reset_card_unknown
                            }) }, host != null)
                            SettingsValueRow(stringResource(R.string.settings_account_plan), host?.plan, host != null)
                            SettingsValueRow(stringResource(R.string.settings_account_method), host?.authMethod?.let {
                                when (it) { "chatgpt", "chatgptAuthTokens" -> "ChatGPT"; "apikey", "apiKey" -> "API key"; else -> it }
                            }, host != null)
                        }
                        SettingsGroup(stringResource(R.string.settings_auth_files)) {
                            SettingsAuthFileContent(host?.authFile, host != null, hostLoading)
                            authProfiles.currentProfile?.takeIf { authProfiles.supportsTokenRefresh }?.let { current ->
                                RefreshTokenAction(current, authProfiles, ready && conn is ConnectionState.Connected && !hostLoading) {
                                    refreshHost()
                                }
                            }
                        }
                        AuthProfilesSection(authProfiles, ready && conn is ConnectionState.Connected) {
                            // Host refresh is triggered only by verified runtime readiness above.
                        }
                    }
                    SettingsPage.COMPONENTS -> {
                        SettingsGroup(stringResource(R.string.settings_phone_component)) {
                            SettingsValueRow("Remodex", versionName)
                            SettingsValueRow("Android", Build.VERSION.RELEASE)
                        }
                        SettingsGroup(stringResource(R.string.settings_computer_components)) {
                            HostRefreshStatus(hostLoading, hostError, ready && conn is ConnectionState.Connected) { scope.launch { refreshHost() } }
                            SettingsValueRow(stringResource(R.string.settings_bridge_version), host?.bridgeVersion, host != null)
                            SettingsValueRow(stringResource(R.string.settings_host_system), host?.hostPlatform?.let {
                                when (it) { "linux" -> "Linux"; "macos" -> "macOS"; "windows" -> "Windows"; else -> it }
                            }, host != null)
                            SettingsValueRow(stringResource(R.string.settings_host_transport), host?.transport, host != null)
                        }
                        SettingsGroup(stringResource(R.string.settings_help_tools)) {
                            SettingsNavigationRow(stringResource(R.string.terminal_windows_guide_title), stringResource(R.string.settings_terminal_summary), { page = SettingsPage.TERMINAL })
                        }
                    }
                    SettingsPage.TERMINAL -> SettingsGroup { TerminalWindowsSetupGuide() }
                    SettingsPage.UPDATES -> SettingsGroup { AppUpdateSection() }
                }
            }
        }
    }
}

@Composable
private fun SettingsAuthFileContent(info: SettingsAuthFileInfo?, resolved: Boolean, loading: Boolean) {
    if (info == null) {
        Text(if (!resolved || loading) "—" else stringResource(R.string.settings_auth_file_bridge_unsupported),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    val status = when (info.status) {
        "present" -> R.string.settings_auth_file_current
        "missing" -> R.string.settings_auth_file_missing
        "error" -> R.string.settings_auth_file_failed
        "non_file" -> when (info.storage) {
            "keyring" -> R.string.settings_auth_file_keyring
            "ephemeral" -> R.string.settings_auth_file_memory
            else -> R.string.settings_auth_file_auto
        }
        else -> when (info.reason) {
            "external_runtime" -> R.string.settings_auth_file_remote
            "automatic_storage" -> R.string.settings_auth_file_auto
            else -> R.string.settings_auth_file_unknown
        }
    }
    Text(stringResource(status), style = MaterialTheme.typography.bodyMedium,
        color = if (info.status == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    info.path?.let { SettingsValueRow(stringResource(R.string.settings_auth_file_path), it) }
    info.resolvedPath?.takeIf { it != info.path }?.let { SettingsValueRow(stringResource(R.string.settings_auth_file_target), it) }
    if (info.status == "present") {
        val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        val now = java.time.Instant.now()
        SettingsValueRow(stringResource(R.string.settings_account_method), when (info.authMode) {
            "chatgpt", "chatgptAuthTokens" -> "ChatGPT"
            "apikey", "apiKey" -> "API key"
            else -> null
        })
        SettingsValueRow(stringResource(R.string.settings_auth_file_modified), info.modifiedAt?.let {
            com.remodex.mobile.ui.turn.formatUsageCalendarTime(it, now, locale = locale)
        })
        SettingsValueRow(stringResource(R.string.settings_auth_file_refreshed), info.lastRefresh?.let {
            com.remodex.mobile.ui.turn.formatUsageCalendarTime(it, now, locale = locale)
        })
        SettingsValueRow(stringResource(R.string.settings_auth_file_size), info.sizeBytes?.let {
            android.text.format.Formatter.formatShortFileSize(LocalContext.current, it)
        })
        Text(stringResource(R.string.settings_auth_file_source_note), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    info.errorDetails?.let { error ->
        var expanded by rememberSaveable { mutableStateOf(false) }
        TextButton(onClick = { expanded = !expanded }) { Text(stringResource(R.string.questions_error_details)) }
        if (expanded) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun SettingsGroup(title: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        androidx.compose.material3.Surface(Modifier.fillMaxWidth(), shape = androidx.compose.foundation.shape.RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun <T> SettingsPickerRow(title: String, choices: List<T>, selected: T,
    label: @Composable (T) -> String, description: @Composable (T) -> String = { "" }, onSelect: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsNavigationRow(title, label(selected), { open = true })
    if (open) androidx.compose.material3.AlertDialog(onDismissRequest = { open = false }, title = { Text(title) },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            choices.forEach { choice ->
                Row(Modifier.fillMaxWidth().selectable(choice == selected, onClick = { onSelect(choice); open = false }, role = Role.RadioButton).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(choice == selected, onClick = null)
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(label(choice), style = MaterialTheme.typography.bodyLarge)
                        description(choice).takeIf { it.isNotEmpty() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.questions_close)) } })
}

@Composable
private fun SettingsValueRow(label: String, value: String?, resolved: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.foundation.text.selection.SelectionContainer(Modifier.weight(1.4f)) {
            Text(value ?: if (resolved) stringResource(R.string.usage_details_unavailable) else "—", style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun HostRefreshStatus(loading: Boolean, error: Exception?, connected: Boolean, onRefresh: () -> Unit) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(when { !connected -> R.string.usage_rate_limits_offline; loading -> R.string.usage_refreshing_keep_data; error != null -> R.string.settings_host_read_failed; else -> R.string.settings_host_read_source }),
            Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onRefresh, enabled = connected && !loading) { Text(stringResource(R.string.turn_usage_sheet_refresh_all)) }
    }
    if (error != null) {
        TextButton(onClick = { showDetails = !showDetails }) { Text(stringResource(R.string.questions_error_details)) }
        if (showDetails) Text(error.message ?: error.javaClass.name, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun SettingsNotificationSection(context: Context) {
    var refreshNonce by remember { mutableStateOf(0) }
    var requestedInSession by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            requestedInSession = true
            refreshNonce++
        }
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshNonce++
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val permissionStatus =
        remember(refreshNonce, context) {
            LocalNotificationSettings.permissionStatus(context)
        }
    val notificationsEnabled =
        permissionStatus == LocalNotificationSettings.PermissionStatus.Granted ||
            permissionStatus == LocalNotificationSettings.PermissionStatus.NotRequired

    Text(
        text = stringResource(R.string.settings_notifications_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.settings_notifications_run_completion_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(
                    if (notificationsEnabled) {
                        R.string.settings_notifications_status_enabled
                    } else {
                        R.string.settings_notifications_status_disabled
                    }
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (notificationsEnabled) {
        Text(
            text = stringResource(R.string.settings_notifications_enabled_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            text = stringResource(R.string.settings_notifications_disabled_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (
            Build.VERSION.SDK_INT >= 33 &&
            permissionStatus == LocalNotificationSettings.PermissionStatus.RuntimePermissionRequired &&
            !requestedInSession
        ) {
            TextButton(
                onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) },
            ) {
                Text(stringResource(R.string.settings_notifications_request_permission))
            }
        } else {
            TextButton(
                onClick = { openSystemNotificationSettings(context) },
            ) {
                Text(stringResource(R.string.settings_notifications_open_system_settings))
            }
        }
    }
}

@Composable
private fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp)
                .selectable(
                    selected = false,
                    onClick = onClick,
                    role = Role.Button,
                )
                .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle.isNotBlank()) Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.width(12.dp))
        Icon(
            imageVector = Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsConnectionStatus(conn: ConnectionState) {
    val text =
        when (val c = conn) {
            ConnectionState.Offline -> stringResource(R.string.sidebar_bridge_offline)
            ConnectionState.Connecting -> stringResource(R.string.sidebar_bridge_connecting)
            ConnectionState.Connected -> stringResource(R.string.sidebar_bridge_connected)
            is ConnectionState.Error -> stringResource(R.string.sidebar_bridge_error, c.message)
        }
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
    )
}

@Composable
private fun SettingsPetCompanionSection(
    repository: CodexRepository,
    petStore: PetCompanionStore,
) {
    val isEnabled by petStore.isEnabled.collectAsStateWithLifecycle()
    val availablePets by petStore.availablePets.collectAsStateWithLifecycle()
    val selectedPetId by petStore.selectedPetId.collectAsStateWithLifecycle()
    val isLoading by petStore.isLoading.collectAsStateWithLifecycle()
    val errorMessage by petStore.errorMessage.collectAsStateWithLifecycle()
    val conn by repository.connectionState.collectAsStateWithLifecycle()
    val sessionReady by repository.isSessionReady.collectAsStateWithLifecycle()
    val codexService = repository as? CodexService
    val scope = rememberCoroutineScope()

    LaunchedEffect(conn, sessionReady, isEnabled, codexService) {
        if (conn is ConnectionState.Connected && sessionReady && isEnabled && codexService != null) {
            petStore.loadPetsIfNeeded(codexService)
            petStore.loadSelectedPet(codexService)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_pet_companion_title),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text = stringResource(R.string.settings_beta_badge),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier =
                    Modifier
                        .background(
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                            shape = CircleShape,
                        )
                        .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
        Switch(
            checked = isEnabled,
            onCheckedChange = { enabled ->
                petStore.setEnabled(enabled)
                if (enabled && codexService != null && conn is ConnectionState.Connected && sessionReady) {
                    scope.launch {
                        petStore.loadPetsIfNeeded(codexService)
                        petStore.loadSelectedPet(codexService)
                    }
                }
            },
        )
    }
    if (isEnabled) {
        when {
            isLoading -> {
                Text(
                    text = stringResource(R.string.settings_pet_loading),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            availablePets.isEmpty() -> {
                Text(
                    text = stringResource(R.string.settings_pet_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> {
                availablePets.forEach { pet ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = pet.id == selectedPetId,
                                    onClick = {
                                        petStore.selectPet(pet.id)
                                        if (codexService != null && conn is ConnectionState.Connected && sessionReady) {
                                            scope.launch { petStore.loadSelectedPet(codexService) }
                                        }
                                    },
                                    role = Role.RadioButton,
                                )
                                .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = pet.id == selectedPetId,
                            onClick = null,
                        )
                        Column(modifier = Modifier.padding(start = 8.dp)) {
                            Text(text = pet.displayName, style = MaterialTheme.typography.bodyLarge)
                            pet.description?.takeIf { it.isNotBlank() }?.let { description ->
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        errorMessage?.takeIf { it.isNotBlank() }?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(
            onClick = {
                if (codexService != null && conn is ConnectionState.Connected && sessionReady) {
                    scope.launch { petStore.refreshPets(codexService) }
                }
            },
            enabled = !isLoading && conn is ConnectionState.Connected && sessionReady,
        ) {
            Text(stringResource(R.string.settings_pet_refresh))
        }
    }
}

@Composable
private fun SettingsSubscriptionSection(
    subscriptionService: SubscriptionService,
) {
    val context = LocalContext.current
    var redemptionMessage by remember { mutableStateOf<String?>(null) }
    TextButton(
        onClick = {
            val intent = subscriptionService.createRedeemIntent()
            if (intent != null) {
                runCatching {
                    context.startActivity(intent)
                    val synced = subscriptionService.syncPurchasesAfterOfferCodeRedemption()
                    redemptionMessage =
                        if (synced) {
                            null
                        } else {
                            context.getString(R.string.settings_redeem_code_manual_refresh)
                        }
                }
            }
        },
    ) {
        Text(stringResource(R.string.settings_redeem_code))
    }
    redemptionMessage?.let { message ->
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsRelayHealthSection(
    relayHealthClient: RelayHealthClient,
    localRelayHostOverride: String,
) {
    var relayHealth by remember { mutableStateOf<RelayHealthSnapshot?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }

    LaunchedEffect(localRelayHostOverride, refreshNonce) {
        relayHealth = relayHealthClient.fetchHealth(localRelayHostOverride)
    }

    Text(
        text = stringResource(R.string.settings_relay_health_title),
        style = MaterialTheme.typography.titleMedium,
    )
    TextButton(onClick = { refreshNonce++ }) {
        Text(stringResource(R.string.settings_relay_health_refresh))
    }
    when {
        relayHealth == null -> {
            Text(
                text = stringResource(R.string.settings_relay_health_unavailable),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        relayHealth?.ok == true -> {
            Text(
                text = stringResource(R.string.settings_relay_health_ok),
                style = MaterialTheme.typography.bodyMedium,
            )
            relayHealth?.relay?.sessionsWithClients?.let { count ->
                Text(
                    text = stringResource(R.string.settings_relay_health_clients, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        else -> {
            Text(
                text = stringResource(R.string.settings_relay_health_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }
}

private fun settingsThemeTitleRes(option: AppThemePreference): Int =
    when (option) {
        AppThemePreference.system -> R.string.settings_theme_system_title
        AppThemePreference.light -> R.string.settings_theme_light_title
        AppThemePreference.dark -> R.string.settings_theme_dark_title
    }

private fun settingsThemeSubtitleRes(option: AppThemePreference): Int =
    when (option) {
        AppThemePreference.system -> R.string.settings_theme_system_subtitle
        AppThemePreference.light -> R.string.settings_theme_light_subtitle
        AppThemePreference.dark -> R.string.settings_theme_dark_subtitle
    }

private fun settingsUserBubbleTitleRes(option: UserBubbleColor): Int =
    when (option) {
        UserBubbleColor.default -> R.string.settings_user_bubble_default
        UserBubbleColor.orange -> R.string.settings_user_bubble_orange
        UserBubbleColor.yellow -> R.string.settings_user_bubble_yellow
        UserBubbleColor.green -> R.string.settings_user_bubble_green
        UserBubbleColor.blue -> R.string.settings_user_bubble_blue
        UserBubbleColor.pink -> R.string.settings_user_bubble_pink
        UserBubbleColor.purple -> R.string.settings_user_bubble_purple
        UserBubbleColor.black -> R.string.settings_user_bubble_black
    }

private fun settingsLanguageTitleRes(option: AppLanguagePreference): Int =
    when (option) {
        AppLanguagePreference.english -> R.string.settings_language_english_title
        AppLanguagePreference.simplifiedChinese -> R.string.settings_language_chinese_title
        AppLanguagePreference.system -> R.string.settings_language_system_title
    }

private fun openSystemNotificationSettings(context: Context) {
    val appPackage = Uri.fromParts("package", context.packageName, null)
    val intent =
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    runCatching { context.startActivity(intent) }
        .recoverCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = appPackage
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
        }
}
