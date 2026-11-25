package com.remodex.mobile.ui.settings

import com.remodex.mobile.ui.theme.RemodexPageStack
import com.remodex.mobile.ui.theme.LocalSheetClose
import com.remodex.mobile.ui.theme.conversationColors
import com.remodex.mobile.ui.theme.RemodexModalBottomSheet
import com.composables.icons.lucide.R as LucideR
import androidx.compose.ui.res.painterResource
import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Badge
import androidx.compose.material3.TextButton
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.update.UpdatePhase
import com.remodex.mobile.core.config.FeatureFlags
import com.remodex.mobile.core.model.AppFontStyle
import com.remodex.mobile.core.model.AppLanguagePreference
import com.remodex.mobile.core.model.AppThemePreference
import com.remodex.mobile.core.model.RelayHealthSnapshot
import com.remodex.mobile.core.model.UserBubbleColor
import com.remodex.mobile.core.notification.LocalNotificationSettings
import com.remodex.mobile.BuildConfig
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.data.AppFontPreferences
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.LanguagePreferences
import com.remodex.mobile.data.PetCompanionStore
import com.remodex.mobile.data.ThemePreferences
import com.remodex.mobile.data.UserBubblePreferences
import com.remodex.mobile.services.CodexService
import com.remodex.mobile.services.RelayHealthClient
import com.remodex.mobile.services.SubscriptionService
import com.remodex.mobile.terminal.TerminalWindowsSetupGuide
import com.remodex.mobile.ui.theme.RemodexPageHeader
import com.remodex.mobile.ui.turn.currentWeeklyQuota
import com.remodex.mobile.ui.turn.weeklyQuotaCountdown
import com.remodex.mobile.ui.turn.weeklyQuotaLabel
import kotlinx.coroutines.launch

private enum class SettingsPage(val title: Int) {
    HOME(R.string.nav_settings), APPEARANCE(R.string.settings_section_appearance),
    NOTIFICATIONS(R.string.settings_section_notifications), CONNECTION(R.string.settings_section_connection),
    APP(R.string.settings_app_hub), COMPONENTS(R.string.settings_components), TERMINAL(R.string.terminal_windows_guide_title),
    PETS(R.string.settings_section_pet), UPDATES(R.string.app_update_title),
    ACCOUNT(R.string.settings_accounts_auth), SUBSCRIPTION(R.string.settings_section_subscription),
}

@Composable
internal fun rememberSettingsProfiles(repository: CodexRepository): AuthProfilesState {
    val device by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    return remember(repository, device) { AuthProfilesState(repository) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    repository: CodexRepository,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToWhatsNew: () -> Unit,
    onNavigateToTesterHq: () -> Unit = {},
    modifier: Modifier = Modifier,
    authProfiles: AuthProfilesState = rememberSettingsProfiles(repository),
) {
    val base = MaterialTheme.colorScheme
    val mode = com.remodex.mobile.ui.theme.LocalAppPalette.current.mode
    val colors = remember(base, mode) { settingsColorScheme(base, mode) }
    MaterialTheme(colorScheme = colors) {
        SettingsContent(repository, onNavigateBack, onNavigateToAbout, onNavigateToWhatsNew, onNavigateToTesterHq, modifier, authProfiles)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsContent(
    repository: CodexRepository,
    onNavigateBack: () -> Unit,
    onNavigateToAbout: () -> Unit,
    onNavigateToWhatsNew: () -> Unit,
    onNavigateToTesterHq: () -> Unit,
    modifier: Modifier,
    authProfiles: AuthProfilesState,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var page by rememberSaveable { mutableStateOf(SettingsPage.HOME) }
    var updateOpen by rememberSaveable { mutableStateOf(false) }
    // Restore an update page saved by an earlier build into the new dialog.
    LaunchedEffect(page) {
        if (page == SettingsPage.UPDATES) {
            page = SettingsPage.APP
            updateOpen = true
        }
    }
    if (updateOpen) AppUpdateDialog(onDismiss = { updateOpen = false })
    var usageOpen by rememberSaveable { mutableStateOf(false) }
    val pageStates = rememberSaveableStateHolder()
    fun back() { page = when (page) {
        SettingsPage.TERMINAL -> SettingsPage.COMPONENTS
        SettingsPage.COMPONENTS, SettingsPage.UPDATES -> SettingsPage.APP
        else -> SettingsPage.HOME
    } }
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
    LaunchedEffect(page, deviceId, ready, localRelayHostOverride) {
        if (page == SettingsPage.HOME) AppContainer.appUpdates.checkOnSettingsEntry()
    }
    val versionName = BuildConfig.VERSION_NAME
    var host by remember(repository, deviceId) { mutableStateOf<SettingsHostStatus?>(null) }
    var hostError by remember(repository, deviceId) { mutableStateOf<Exception?>(null) }
    var hostLoading by remember(repository, deviceId) { mutableStateOf(false) }
    var hostRequestRevision by remember(repository, deviceId) { mutableStateOf(0L) }
    suspend fun refreshHost(forceNew: Boolean = false) {
        if ((hostLoading && !forceNew) || authProfiles.switching || !ready || conn !is ConnectionState.Connected) return
        hostLoading = true
        val requestRevision = ++hostRequestRevision
        var stage = "request"
        try {
            val response = kotlinx.coroutines.withTimeoutOrNull(30_000L) {
                repository.sendRequest("account/status/read", com.remodex.mobile.core.model.JSONValue.Obj(emptyMap()))
            } ?: throw java.util.concurrent.TimeoutException("account/status/read stage=request timeout=30s")
            response.error?.let { throw com.remodex.mobile.core.error.CodexServiceError.RpcFailure(it) }
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
    val connected = ready && conn is ConnectionState.Connected
    ObserveAuthProfiles(authProfiles, connected)
    LaunchedEffect(page, deviceId, ready, conn) {
        if (page in setOf(SettingsPage.HOME, SettingsPage.ACCOUNT, SettingsPage.COMPONENTS) && host == null && hostError == null) refreshHost()
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
    val currentFiles = authProfiles.currentLibraryProfiles
    val currentFile = authProfiles.currentProfile ?: authProfiles.management?.current
    val readingAccount = connected && !authProfiles.initialListSettled && !authProfiles.verifyingIdentity
    val identity = if (readingAccount) null else currentFile?.accountIdentity() ?: host?.email?.takeUnless { authProfiles.verifyingIdentity }
    val currentName = if (readingAccount) "—" else (currentFiles.firstOrNull() ?: currentFile)?.let { profileTitle(it) } ?: identity
        ?: stringResource(R.string.settings_current_account)
    val verification = when {
        authProfiles.switching -> stringResource(R.string.settings_checking_authorization)
        authProfiles.failedSwitch != null -> stringResource(R.string.auth_disk_file)
        authProfiles.verifyingIdentity -> stringResource(R.string.settings_verifying_identity)
        else -> null
    }
    var checkingRuntime by remember(deviceId) { mutableStateOf(false) }
    var currentDetailId by rememberSaveable(deviceId) { mutableStateOf<String?>(null) }
    val files = androidx.compose.runtime.key(deviceId) { rememberAuthFileActions(authProfiles) }
    var addOpen by rememberSaveable(deviceId) { mutableStateOf(false) }
    val authScope = androidx.compose.runtime.key(authProfiles) { rememberCoroutineScope() }
    if (usageOpen) com.remodex.mobile.ui.turn.ThreadUsageStatusBottomSheet(true, { usageOpen = false }, activeThread.orEmpty(), repository)
    Scaffold(modifier = modifier, containerColor = MaterialTheme.colorScheme.conversationColors().reading, topBar = {
        RemodexPageHeader(stringResource(page.title), navigateBack, actions = {
            if (page == SettingsPage.HOME) SettingsPaletteButton()
            if (page == SettingsPage.ACCOUNT) {
                androidx.compose.material3.IconButton(onClick = { authScope.launch { authProfiles.refresh(forceLiveQuota = true) } },
                    enabled = connected && authProfiles.busy == null) {
                    if (authProfiles.busy == "list" || authProfiles.busy == "runtime-quota") {
                        val label = stringResource(R.string.auth_profiles_refresh)
                        androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp).semantics { contentDescription = label },
                            strokeWidth = 2.dp)
                    } else Icon(painterResource(LucideR.drawable.lucide_ic_refresh_cw), stringResource(R.string.auth_profiles_refresh))
                }
            }
            if (page == SettingsPage.ACCOUNT && authProfiles.supportsManagement) {
                androidx.compose.material3.IconButton(onClick = { addOpen = true },
                    enabled = connected && !authProfiles.switching && authProfiles.busy == null && authProfiles.management != null) {
                    Icon(painterResource(LucideR.drawable.lucide_ic_plus), stringResource(R.string.auth_library_add))
                }
            }
        })
    }) { innerPadding ->
        RemodexPageStack(page, depth = {
            when (it) {
                SettingsPage.HOME -> 0
                SettingsPage.TERMINAL -> 3
                SettingsPage.COMPONENTS, SettingsPage.UPDATES -> 2
                else -> 1
            }
        }, modifier = Modifier.padding(innerPadding)) { destination ->
            pageStates.SaveableStateProvider(destination.name) {
                Column(Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    when (destination) {
                        SettingsPage.HOME -> {
                            CurrentAccountCard(currentName, identity, verification, currentWeeklyQuota()?.remainingPercent?.takeUnless { authProfiles.verifyingIdentity }) { page = SettingsPage.ACCOUNT }
                            SettingsDestinationGroup(stringResource(R.string.settings_group_application)) {
                                SettingsUpdateEntry(versionName) { updateOpen = true }
                                SettingsDestination(LucideR.drawable.lucide_ic_info, stringResource(R.string.settings_app_hub),
                                    stringResource(R.string.settings_components), { page = SettingsPage.APP })
                            }
                            SettingsDestinationGroup(stringResource(R.string.settings_environment)) {
                                SettingsDestination(LucideR.drawable.lucide_ic_plug, stringResource(R.string.settings_section_connection), stringResource(if (conn is ConnectionState.Connected) R.string.sidebar_bridge_connected else R.string.settings_connection_summary), { page = SettingsPage.CONNECTION })
                                if (AppContainer.subscriptionService.isConfigured) SettingsDestination(LucideR.drawable.lucide_ic_credit_card, stringResource(R.string.settings_section_subscription), "", { page = SettingsPage.SUBSCRIPTION })
                            }
                            SettingsDestinationGroup(stringResource(R.string.settings_group_preferences)) {
                                SettingsDestination(LucideR.drawable.lucide_ic_palette, stringResource(R.string.settings_section_appearance),
                                    stringResource(R.string.settings_appearance_summary, stringResource(settingsThemeTitleRes(themePreference)), stringResource(settingsLanguageTitleRes(languagePreference))),
                                    { page = SettingsPage.APPEARANCE })
                                SettingsDestination(LucideR.drawable.lucide_ic_bell, stringResource(R.string.settings_section_notifications), stringResource(R.string.settings_notifications_run_completion_title),
                                    { page = SettingsPage.NOTIFICATIONS })
                                SettingsDestination(LucideR.drawable.lucide_ic_paw_print, stringResource(R.string.settings_section_pet), stringResource(R.string.settings_pet_summary), { page = SettingsPage.PETS })
                            }
                        }
                        SettingsPage.APP, SettingsPage.UPDATES -> {
                            SettingsDestinationGroup(stringResource(R.string.settings_installed_version)) {
                                SettingsUpdateEntry(versionName) { updateOpen = true }
                            }
                            SettingsDestinationGroup(stringResource(R.string.settings_help_tools)) {
                                SettingsDestination(LucideR.drawable.lucide_ic_blocks, stringResource(R.string.settings_components), stringResource(R.string.settings_components_summary), { page = SettingsPage.COMPONENTS })
                            }
                            SettingsDestinationGroup(stringResource(R.string.settings_group_application)) {
                                SettingsDestination(LucideR.drawable.lucide_ic_info, stringResource(R.string.nav_about_remodex), stringResource(R.string.settings_about_remodex_hint), {
                                    scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("about_screen_opened", "settings") }
                                    onNavigateToAbout()
                                })
                                SettingsDestination(LucideR.drawable.lucide_ic_sparkles, stringResource(R.string.nav_whats_new), "", {
                                    scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("settings_whats_new_opened", "settings") }
                                    onNavigateToWhatsNew()
                                })
                                if (FeatureFlags.betaEngagementEnabled) SettingsDestination(LucideR.drawable.lucide_ic_flask_conical, stringResource(R.string.nav_tester_hq), "", {
                                    scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("settings_tester_hq_entry_opened", "settings") }
                                    onNavigateToTesterHq()
                                })
                            }
                        }
                        SettingsPage.APPEARANCE -> {
                            SettingsSummary(null, stringResource(R.string.settings_palette),
                                stringResource(com.remodex.mobile.ui.theme.LocalAppPalette.current.mode.label),
                                trailing = { SettingsPaletteButton() })
                            SettingsGroup(stringResource(R.string.settings_display)) {
                                SettingsPickerRow(stringResource(R.string.settings_theme_title), AppThemePreference.entries, themePreference,
                                    { stringResource(settingsThemeTitleRes(it)) }, { stringResource(settingsThemeSubtitleRes(it)) }) {
                                    themePreference = it; ThemePreferences.write(context, it)
                                }
                                SettingsPickerRow(stringResource(R.string.settings_user_bubble_title), UserBubbleColor.entries, userBubbleColor,
                                    { stringResource(settingsUserBubbleTitleRes(it)) }) { color ->
                                    val changed = color != userBubbleColor
                                    userBubbleColor = color; UserBubblePreferences.write(context, color)
                                    if (changed) scope.launch { AppContainer.betaEngagementRepository.recordMissionEvent("user_bubble_color_changed", "settings", refreshAfter = false) }
                                }
                            }
                            SettingsGroup(stringResource(R.string.settings_language_and_fonts)) {
                                SettingsPickerRow(stringResource(R.string.settings_language_title), AppLanguagePreference.entries, languagePreference,
                                    { stringResource(settingsLanguageTitleRes(it)) }) { languagePreference = it; LanguagePreferences.write(context, it) }
                                val customFonts by com.remodex.mobile.data.CustomFonts.slots.collectAsStateWithLifecycle()
                                val hasBundledPair = AppFontStyle.bundled in AppFontPreferences.availableStyles
                                if (hasBundledPair || customFonts[com.remodex.mobile.data.FontSlot.Interface]?.font == null)
                                    SettingsPickerRow(stringResource(R.string.settings_font_title), AppFontPreferences.availableStyles, fontStyle,
                                    { when (it) {
                                        AppFontStyle.system -> stringResource(R.string.settings_theme_system_title)
                                        AppFontStyle.bundled -> stringResource(R.string.font_builtin)
                                        else -> it.title
                                    } },
                                    { when (it) {
                                        AppFontStyle.system -> stringResource(R.string.font_system_subtitle)
                                        AppFontStyle.geist -> stringResource(R.string.font_geist_subtitle)
                                        AppFontStyle.jetBrainsMono -> stringResource(R.string.font_mono_subtitle)
                                        AppFontStyle.bundled -> ""
                                    } }) {
                                    fontStyle = it; AppFontPreferences.writeFontStyle(context, it)
                                }
                                CustomFontSetting(com.remodex.mobile.data.FontSlot.Interface)
                                CustomFontSetting(com.remodex.mobile.data.FontSlot.Reply)
                            }
                        }
                        SettingsPage.NOTIFICATIONS -> SettingsNotificationSection(context)
                        SettingsPage.PETS -> SettingsGroup { SettingsPetCompanionSection(repository, AppContainer.petCompanionStore) }
                        SettingsPage.SUBSCRIPTION -> SettingsGroup { SettingsSubscriptionSection(AppContainer.subscriptionService) }
                        SettingsPage.CONNECTION -> {
                            SettingsSummary(LucideR.drawable.lucide_ic_plug, stringResource(R.string.settings_current_connection),
                                connectionStatusLabel(conn))
                            SettingsGroup {
                                RelayAddressSetting(localRelayHostOverride) { address ->
                                    AppContainer.sessionPersistence.saveLocalRelayHostOverride(address)
                                    localRelayHostOverride = address
                                }
                            }
                            SettingsGroup { SettingsRelayHealthSection(AppContainer.relayHealthClient, localRelayHostOverride) }
                        }
                        SettingsPage.ACCOUNT -> {
                            androidx.compose.runtime.key(deviceId) {
                                AuthProfilesSection(authProfiles, files, connected, authScope, addOpen, { addOpen = false },
                                    currentFiles, currentName, identity, verification,
                                    currentFile != null || host?.email != null, currentFile, host?.plan?.takeUnless { authProfiles.verifyingIdentity },
                                    openCurrent = { id -> checkingRuntime = false; currentDetailId = id },
                                    verifyCurrent = {
                                        checkingRuntime = true
                                        currentDetailId = "current"
                                        host = null
                                        hostError = null
                                        authScope.launch {
                                            authProfiles.refresh()
                                            refreshHost(forceNew = true)
                                        }
                                    })
                            }
                            if (currentDetailId != null) AccountDetailSheet({ currentDetailId = null }) {
                                val selectedFile = currentFiles.firstOrNull { it.id == currentDetailId }
                                var fileDetails by rememberSaveable(currentDetailId) { mutableStateOf(false) }
                                var loginDetails by rememberSaveable(currentDetailId) { mutableStateOf(false) }
                                val enabled = connected && authProfiles.busy == null && !authProfiles.switching
                                AccountDetailHeader(verification ?: stringResource(R.string.account_current_heading),
                                    selectedFile?.let { profileTitle(it) } ?: currentName, selectedFile?.accountIdentity() ?: identity,
                                    host?.plan?.takeUnless { authProfiles.verifyingIdentity },
                                    selectedFile?.takeIf { authProfiles.management?.nextProfileId != null }, enabled) { file, name ->
                                    authScope.launch { authProfiles.rename(file, name) }
                                }
                                if (!fileDetails && selectedFile != null) {
                                    AuthActionFeedback(authProfiles, "file:${selectedFile.id}", connected, authScope)
                                }
                                if (checkingRuntime && verification != null && !hostLoading && hostError == null && host != null) {
                                    val email = host?.email
                                    val expected = currentFile?.email
                                    val hint = when {
                                        host?.status == "not_logged_in" -> R.string.auth_runtime_signed_out
                                        host?.status != "authenticated" || email.isNullOrBlank() || expected.isNullOrBlank() -> R.string.auth_runtime_unknown
                                        email.equals(expected, ignoreCase = true) -> R.string.auth_runtime_matches
                                        else -> R.string.auth_runtime_differs
                                    }
                                    Text(stringResource(hint), style = MaterialTheme.typography.bodyMedium)
                                }
                                AccountDetailQuota(currentWeeklyQuota().takeUnless { authProfiles.verifyingIdentity }, null,
                                    stringResource(R.string.weekly_quota_unknown)) {
                                    TextButton(onClick = { currentDetailId = null; usageOpen = true }) { Text(stringResource(R.string.settings_section_usage)) }
                                }
                                if (hostLoading || hostError != null || !connected) {
                                    HostRefreshStatus(hostLoading, hostError, connected) { scope.launch { refreshHost() } }
                                }
                                AccountDetailSection(stringResource(R.string.account_sign_in_details), loginDetails, { loginDetails = !loginDetails }) {
                                    if (!hostLoading && hostError == null && connected) {
                                        HostRefreshStatus(false, null, connected) { scope.launch { refreshHost() } }
                                    }
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
                                AccountDetailSection(stringResource(R.string.auth_profiles_token_details), fileDetails, { fileDetails = !fileDetails }) {
                                    SettingsAuthFileContent(host?.authFile, host != null, hostLoading)
                                    authProfiles.management?.current?.let { file ->
                                        SettingsValueRow(stringResource(R.string.auth_library_account),
                                            file.accountIdentity() ?: stringResource(R.string.auth_library_unknown))
                                    }
                                    authProfiles.currentProfile?.takeIf { authProfiles.supportsTokenRefresh }?.let { current ->
                                        RefreshTokenAction(current, authProfiles, connected && !hostLoading, authScope) {
                                            refreshHost()
                                        }
                                    }
                                    if (authProfiles.supportsManagement) {
                                        authProfiles.management?.current?.let { file ->
                                            SettingsFileRow(stringResource(R.string.auth_manage_current_file), file, authProfiles, files, enabled, authScope)
                                        }
                                        selectedFile?.let { file -> SettingsFileRow(profileTitle(file), file, authProfiles, files, enabled, authScope) }
                                    }
                                }
                                selectedFile?.let { file -> AuthMaintenanceFailure(authProfiles.management?.entries?.firstOrNull { it.profile.id == file.id }) }
                            }
                        }
                        SettingsPage.COMPONENTS -> {
                            SettingsSummary(LucideR.drawable.lucide_ic_blocks, stringResource(R.string.settings_phone_component),
                                "Remodex $versionName", "Android ${Build.VERSION.RELEASE}")
                            SettingsGroup(stringResource(R.string.settings_computer_components)) {
                                val platform = when (val name = host?.hostPlatform) {
                                    "linux" -> "Linux"; "macos", "darwin" -> "macOS"; "windows" -> "Windows"; else -> name
                                }
                                platform?.let { Text(it, style = MaterialTheme.typography.titleLarge) }
                                SettingsValueRow(stringResource(R.string.settings_bridge_version), host?.bridgeVersion, host != null)
                                SettingsValueRow(stringResource(R.string.settings_host_transport), host?.transport, host != null)
                                HostRefreshStatus(hostLoading, hostError, connected) { scope.launch { refreshHost() } }
                                if (host?.hostPlatform in setOf("macos", "darwin")) MacBridgeControls(repository, deviceId, ready)
                            }
                            SettingsGroup(stringResource(R.string.settings_help_tools)) {
                                SettingsNavigationRow(stringResource(R.string.terminal_windows_guide_title), stringResource(R.string.settings_terminal_summary), { page = SettingsPage.TERMINAL })
                            }
                        }
                        SettingsPage.TERMINAL -> SettingsGroup { TerminalWindowsSetupGuide() }
                    }
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
private fun SettingsDestinationGroup(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, Modifier.padding(start = 12.dp), style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        androidx.compose.material3.Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface, contentColor = MaterialTheme.colorScheme.onSurface) {
            Column(content = content)
        }
    }
}

@Composable
private fun SettingsUpdateEntry(installed: String, onOpen: () -> Unit) {
    val state = AppContainer.appUpdates.state.collectAsStateWithLifecycle()
    val summary by remember(state) { derivedStateOf {
        val update = state.value
        Triple(update.phase, update.retry, update.newerRelease?.versionName)
    } }
    val (phase, retry, target) = summary
    val versions = if (target == null) stringResource(R.string.app_update_current, installed)
        else stringResource(R.string.update_versions, installed, target)
    val subtitle = when (phase) {
        UpdatePhase.IDLE, UpdatePhase.AVAILABLE -> versions
        else -> versions + "\n" + stringResource(updateTitle(phase, retry))
    }
    SettingsDestination(LucideR.drawable.lucide_ic_download, stringResource(R.string.app_update_check), subtitle, onOpen) {
        if (target != null) {
            Badge(containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer) {
                Text(stringResource(R.string.update_new_badge))
            }
        }
    }
}

@Composable
private fun SettingsDestination(icon: Int, title: String, subtitle: String, onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null) {
    SettingsNavigationRow(title, subtitle, onClick, trailing, leading = {
        Icon(painterResource(icon), null, Modifier.size(24.dp))
    })
}

@Composable
internal fun SettingsGroup(title: String? = null, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        title?.let { Text(it, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        com.remodex.mobile.ui.theme.RemodexGroup() {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun <T> SettingsPickerRow(title: String, choices: List<T>, selected: T,
    label: @Composable (T) -> String, description: @Composable (T) -> String = { "" }, onSelect: (T) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsNavigationRow(title, label(selected), { open = true })
    if (open) RemodexModalBottomSheet(onDismissRequest = { open = false }, sheetGesturesEnabled = true) {
        val closeSheet = LocalSheetClose.current
        Text(title, Modifier.padding(horizontal = 24.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge)
        Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
            choices.forEach { choice ->
                val hint = description(choice)
                ListItem(
                    modifier = Modifier.selectable(choice == selected, role = Role.RadioButton, onClick = {
                        onSelect(choice)
                        closeSheet()
                    }),
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    headlineContent = { Text(label(choice)) },
                    supportingContent = if (hint.isBlank()) null else ({ Text(hint) }),
                    trailingContent = { RadioButton(choice == selected, onClick = null) },
                )
            }
        }
    }
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
        if (showDetails) androidx.compose.foundation.text.selection.SelectionContainer {
            Text(remember(error) { authProfileDiagnostic(error) }, Modifier.heightIn(max = 200.dp).verticalScroll(rememberScrollState()),
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
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

    SettingsSummary(LucideR.drawable.lucide_ic_bell, stringResource(R.string.settings_notifications_run_completion_title),
        stringResource(if (notificationsEnabled) R.string.settings_notifications_status_enabled else R.string.settings_notifications_status_disabled),
        stringResource(if (notificationsEnabled) R.string.settings_notifications_enabled_hint else R.string.settings_notifications_disabled_hint),
        actions = {
            if (
                !notificationsEnabled && Build.VERSION.SDK_INT >= 33 &&
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
    })
    Text(stringResource(R.string.settings_notifications_hint), Modifier.padding(horizontal = 12.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
internal fun SettingsNavigationRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    ListItem(
        modifier = Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = leading,
        headlineContent = { Text(title, style = MaterialTheme.typography.bodyLarge) },
        supportingContent = if (subtitle.isBlank()) null else { { Text(subtitle, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                trailing?.invoke()
                Icon(painterResource(LucideR.drawable.lucide_ic_chevron_right), null, Modifier.size(16.dp))
            }
        },
    )
}

@Composable
private fun connectionStatusLabel(conn: ConnectionState): String = when (conn) {
    ConnectionState.Offline -> stringResource(R.string.sidebar_bridge_offline)
    ConnectionState.Connecting -> stringResource(R.string.sidebar_bridge_connecting)
    ConnectionState.Connected -> stringResource(R.string.sidebar_bridge_connected)
    is ConnectionState.Error -> stringResource(R.string.sidebar_bridge_error, conn.message)
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
    var relayHealth by remember(localRelayHostOverride) { mutableStateOf<RelayHealthSnapshot?>(null) }
    var refreshNonce by remember { mutableStateOf(0) }

    var checking by remember(localRelayHostOverride, refreshNonce) { mutableStateOf(true) }
    LaunchedEffect(localRelayHostOverride, refreshNonce) {
        relayHealth = relayHealthClient.fetchHealth(localRelayHostOverride)
        checking = false
    }

    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_relay_health_title), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        TextButton(onClick = { refreshNonce++ }, enabled = !checking) { Text(stringResource(R.string.settings_relay_health_refresh)) }
    }
    when {
        checking -> Text(stringResource(R.string.settings_checking_relay), style = MaterialTheme.typography.bodySmall)
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
