package com.remodex.mobile.terminal

import com.remodex.mobile.ui.theme.singleOwnerPageEntry
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.DropdownMenuItem
import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import com.remodex.mobile.ui.theme.RemodexPageHeader
import com.remodex.mobile.ui.theme.conversationColors
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.ui.sidebar.rememberSidebarColorPalette
import kotlinx.coroutines.launch

@Composable
fun TerminalRoute(
    cdHint: String? = null,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: TerminalViewModel =
        viewModel(
            factory =
                TerminalViewModel.Factory(
                    profileRepository = AppContainer.terminalProfileRepository,
                    trustedHostRepository = AppContainer.terminalTrustedHostRepository,
                ),
        )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val missionScope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        AppContainer.betaEngagementRepository.recordMissionEvent(
            eventType = "terminal_opened",
            screen = "terminal",
        )
    }
    TerminalScreen(
        state = state,
        output = viewModel.outputForSession(state.selectedSessionId),
        cdHint = cdHint,
        onCreateProfile = viewModel::startCreateProfile,
        onEditProfile = viewModel::startEditProfile,
        onDeleteProfile = viewModel::deleteProfile,
        onOpenProfile = viewModel::openProfile,
        onShowProfiles = viewModel::showProfiles,
        onSelectSession = viewModel::selectSession,
        onCloseSession = viewModel::closeSession,
        onCancelEditor = viewModel::cancelEditor,
        onSaveEditor = {
            if (viewModel.saveEditor()) {
                missionScope.launch {
                    AppContainer.betaEngagementRepository.recordMissionEvent(
                        eventType = "terminal_profile_saved",
                        screen = "terminal",
                    )
                }
            }
        },
        onEditorLabelChange = viewModel::updateEditorLabel,
        onEditorHostChange = viewModel::updateEditorHost,
        onEditorPortChange = viewModel::updateEditorPort,
        onEditorUsernameChange = viewModel::updateEditorUsername,
        onEditorPrivateKeyChange = viewModel::updateEditorPrivateKey,
        onEditorAllowUnencryptedKeyChange = viewModel::updateEditorAllowUnencryptedKey,
        onPassphraseChange = viewModel::updatePassphrase,
        onSelectedProfileAllowUnencryptedKeyChange = viewModel::updateSelectedProfileAllowUnencryptedKey,
        onConnect = {
            viewModel.connect()
            missionScope.launch {
                AppContainer.betaEngagementRepository.recordMissionEvent(
                    eventType = "terminal_connect_started",
                    screen = "terminal",
                )
            }
        },
        onTrustHost = viewModel::trustPendingHostAndConnect,
        onDisconnect = viewModel::disconnect,
        onCloseSelectedSession = viewModel::closeSelectedSession,
        onSend = viewModel::send,
        onResize = viewModel::resize,
        onClear = viewModel::clearTerminal,
        onNavigateBack = onNavigateBack,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(
    state: TerminalState,
    output: kotlinx.coroutines.flow.Flow<ByteArray>,
    cdHint: String? = null,
    onCreateProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    onShowProfiles: () -> Unit,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onCancelEditor: () -> Unit,
    onSaveEditor: () -> Unit,
    onEditorLabelChange: (String) -> Unit,
    onEditorHostChange: (String) -> Unit,
    onEditorPortChange: (String) -> Unit,
    onEditorUsernameChange: (String) -> Unit,
    onEditorPrivateKeyChange: (String) -> Unit,
    onEditorAllowUnencryptedKeyChange: (Boolean) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onSelectedProfileAllowUnencryptedKeyChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onTrustHost: () -> Unit,
    onDisconnect: () -> Unit,
    onCloseSelectedSession: () -> Unit,
    onSend: (ByteArray) -> Unit,
    onResize: (TerminalSize) -> Unit,
    onClear: () -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedProfile = state.selectedProfile
    val selectedSession = state.selectedSession
    val connected = selectedSession?.status == TerminalStatus.Connected
    var showActions by remember(state.surface) { mutableStateOf(false) }
    var showSessions by remember { mutableStateOf(false) }
    var showWindowsHelp by remember { mutableStateOf(false) }
    val colors = rememberSidebarColorPalette()
    val title =
        when (state.surface) {
            TerminalSurface.Profiles -> stringResource(R.string.sidebar_terminal)
            TerminalSurface.Editor -> stringResource(if (state.editor?.id == null) R.string.terminal_profile_create else R.string.terminal_profile_edit)
            TerminalSurface.Session -> selectedProfile?.displayLabel ?: "Terminal"
        }
    val onBack =
        when (state.surface) {
            TerminalSurface.Profiles -> onNavigateBack
            TerminalSurface.Editor -> onCancelEditor
            TerminalSurface.Session -> onCloseSelectedSession
        }

    Scaffold(
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.conversationColors().reading,
        topBar = {
            RemodexPageHeader(
                title = title,
                onBack = onBack,
                subtitle = if (state.surface == TerminalSurface.Session && selectedProfile != null)
                    "${selectedSession?.status?.name ?: TerminalStatus.Idle.name} · ${selectedProfile.username}@${selectedProfile.host}:${selectedProfile.port}" else null,
                actions = {
                    if (state.surface == TerminalSurface.Session) {
                        val canDisconnect = connected || selectedSession?.status == TerminalStatus.Connecting
                        TerminalTopBarAction(
                            text = stringResource(if (canDisconnect) R.string.terminal_action_disconnect else R.string.terminal_action_connect),
                            onClick = if (canDisconnect) onDisconnect else onConnect,
                            destructive = canDisconnect,
                            emphasis = !canDisconnect,
                        )
                    }
                    if (state.surface != TerminalSurface.Editor) {
                        Box {
                            IconButton(onClick = { showActions = true }) {
                                Icon(Icons.Outlined.MoreVert, stringResource(R.string.sidebar_more_menu_cd))
                            }
                            RemodexDropdownMenu(expanded = showActions, onDismissRequest = { showActions = false }) {
                                if (state.surface == TerminalSurface.Session) {
                                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_action_sessions)) }, onClick = {
                                        showActions = false
                                        showSessions = !showSessions
                                    })
                                }
                                DropdownMenuItem(text = { Text(stringResource(R.string.terminal_action_help)) }, onClick = {
                                    showActions = false
                                    showWindowsHelp = true
                                })
                            }
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .singleOwnerPageEntry(state.surface) { _, to -> to == TerminalSurface.Profiles },
        ) {
            cdHint?.trim()?.takeIf { it.isNotEmpty() }?.let { path ->
                Text(
                    text = stringResource(R.string.new_chat_draft_terminal_cd_hint, path),
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.secondaryText,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            when (state.surface) {
            TerminalSurface.Profiles ->
                TerminalProfilesContent(
                    profiles = state.profiles,
                    onCreateProfile = onCreateProfile,
                    onEditProfile = onEditProfile,
                    onDeleteProfile = onDeleteProfile,
                    onOpenProfile = onOpenProfile,
                    modifier = Modifier.fillMaxSize(),
                )
            TerminalSurface.Editor ->
                TerminalProfileEditorContent(
                    editor = state.editor ?: TerminalProfileDraft(),
                    errorMessage = state.editorErrorMessage,
                    onLabelChange = onEditorLabelChange,
                    onHostChange = onEditorHostChange,
                    onPortChange = onEditorPortChange,
                    onUsernameChange = onEditorUsernameChange,
                    onPrivateKeyChange = onEditorPrivateKeyChange,
                    onAllowUnencryptedKeyChange = onEditorAllowUnencryptedKeyChange,
                    onSave = onSaveEditor,
                    modifier = Modifier.fillMaxSize(),
                )
            TerminalSurface.Session ->
                Row(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    if (showSessions) {
                        TerminalSessionSidebar(
                            sessions = state.sessions,
                            profiles = state.profiles,
                            selectedSessionId = state.selectedSessionId,
                            onSelectSession = {
                                onSelectSession(it)
                                showSessions = false
                            },
                            onCloseSession = onCloseSession,
                            onShowProfiles = {
                                showSessions = false
                                onShowProfiles()
                            },
                        )
                    }
                    TerminalSessionContent(
                        state = state,
                        output = output,
                        onPassphraseChange = onPassphraseChange,
                        onAllowUnencryptedKeyChange = onSelectedProfileAllowUnencryptedKeyChange,
                        onConnect = onConnect,
                        onTrustHost = onTrustHost,
                        onSend = onSend,
                        onResize = onResize,
                        onClear = onClear,
                        modifier = Modifier.weight(1f).fillMaxSize(),
                    )
                }
            }
        }
    }

    if (showWindowsHelp) {
        RemodexAlertDialog(
            onDismissRequest = { showWindowsHelp = false },
            text = { TerminalWindowsSetupGuide(scrollable = true) },
            confirmButton = { TextButton(onClick = { showWindowsHelp = false }) {
                Text(stringResource(android.R.string.ok))
            } },
        )
    }
}

@Composable
private fun TerminalSessionSidebar(
    sessions: List<TerminalSessionSummary>,
    profiles: List<TerminalProfile>,
    selectedSessionId: String?,
    onSelectSession: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onShowProfiles: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberSidebarColorPalette()
    Column(
        modifier =
            modifier
                .width(120.dp)
                .fillMaxSize()
                .background(colors.background)
                .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TerminalSectionTitle(text = "Sessions")
        sessions.forEach { session ->
            val profile = profiles.firstOrNull { it.id == session.profileId }
            val selected = session.id == selectedSessionId
            Surface(
                selected = selected,
                onClick = { onSelectSession(session.id) },
                shape = MaterialTheme.shapes.medium,
                color = if (selected) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface,
            ) {
                Column(Modifier.fillMaxWidth().padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(profile?.displayLabel ?: "Terminal", style = MaterialTheme.typography.labelLarge, maxLines = 2)
                    Text(session.status.name, style = MaterialTheme.typography.bodySmall)
                    TerminalTextAction(text = "Close", onClick = { onCloseSession(session.id) }, destructive = true)
                }
            }
        }
        TerminalSecondaryButton(
            text = "+",
            onClick = onShowProfiles,
            enabled = profiles.isNotEmpty(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun TerminalProfilesContent(
    profiles: List<TerminalProfile>,
    onCreateProfile: () -> Unit,
    onEditProfile: (String) -> Unit,
    onDeleteProfile: (String) -> Unit,
    onOpenProfile: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalPrimaryButton(
            text = stringResource(R.string.terminal_profile_create),
            onClick = onCreateProfile,
        )
        if (profiles.isEmpty()) {
            TerminalEmptyState(text = stringResource(R.string.terminal_profile_empty))
        } else {
            TerminalSectionTitle(text = stringResource(R.string.terminal_profile_saved))
        }
        profiles.forEach { profile ->
            TerminalSavedProfileRow(
                profile = profile,
                onOpen = { onOpenProfile(profile.id) },
                onEdit = { onEditProfile(profile.id) },
                onDelete = { onDeleteProfile(profile.id) },
            )
        }
    }
}

@Composable
private fun TerminalSavedProfileRow(
    profile: TerminalProfile,
    onOpen: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember(profile.id) { mutableStateOf(false) }
    ListItem(
        modifier = modifier.clickable(onClick = onOpen),
        headlineContent = { Text(profile.displayLabel) },
        supportingContent = { Text("${profile.username}@${profile.host}:${profile.port}") },
        trailingContent = {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Outlined.MoreVert, stringResource(R.string.sidebar_more_menu_cd))
                }
                RemodexDropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_profile_edit)) },
                        onClick = { menuOpen = false; onEdit() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.terminal_profile_delete)) },
                        onClick = { menuOpen = false; onDelete() })
                }
            }
        },
    )
}

@Composable
private fun TerminalProfileEditorContent(
    editor: TerminalProfileDraft,
    errorMessage: String?,
    onLabelChange: (String) -> Unit,
    onHostChange: (String) -> Unit,
    onPortChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPrivateKeyChange: (String) -> Unit,
    onAllowUnencryptedKeyChange: (Boolean) -> Unit,
    onSave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = rememberSidebarColorPalette()
    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TerminalFormTextField(
            value = editor.label,
            onValueChange = onLabelChange,
            label = "Profile name (optional)",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TerminalFormTextField(
                value = editor.host,
                onValueChange = onHostChange,
                label = "Host",
                modifier = Modifier.weight(1f),
            )
            TerminalFormTextField(
                value = editor.port,
                onValueChange = onPortChange,
                label = "Port",
                modifier = Modifier.weight(0.42f),
            )
        }
        TerminalFormTextField(
            value = editor.username,
            onValueChange = onUsernameChange,
            label = "Username",
        )
        TerminalFormTextField(
            value = editor.privateKey,
            onValueChange = onPrivateKeyChange,
            label = if (editor.allowUnencryptedKey) "Private key" else "Encrypted private key",
            singleLine = false,
            minLines = 5,
            maxLines = 8,
            fieldHeight = Modifier.heightIn(min = 132.dp),
        )
        TerminalFormCheckboxRow(
            checked = editor.allowUnencryptedKey,
            onCheckedChange = onAllowUnencryptedKeyChange,
            label = "Allow unencrypted key",
        )
        Spacer(modifier = Modifier.height(4.dp))
        TerminalPrimaryButton(
            text = "Save profile",
            onClick = onSave,
        )
        errorMessage?.let { message ->
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = colors.red,
            )
        }
    }
}

@Composable
private fun TerminalSessionContent(
    state: TerminalState,
    output: kotlinx.coroutines.flow.Flow<ByteArray>,
    onPassphraseChange: (String) -> Unit,
    onAllowUnencryptedKeyChange: (Boolean) -> Unit,
    onConnect: () -> Unit,
    onTrustHost: () -> Unit,
    onSend: (ByteArray) -> Unit,
    onResize: (TerminalSize) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingModifier by remember { mutableStateOf<TerminalModifier?>(null) }
    val context = LocalContext.current
    val selectedSession = state.selectedSession ?: return
    val connected = selectedSession.status == TerminalStatus.Connected
    val colors = rememberSidebarColorPalette()

    Column(modifier = modifier) {
        if (!connected) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                state.selectedProfile?.let { profile ->
                    TerminalInfoCard(
                        text = "${profile.displayLabel}\n${profile.username}@${profile.host}:${profile.port}",
                    )
                }
                TerminalFormTextField(
                    value = selectedSession.passphrase,
                    onValueChange = onPassphraseChange,
                    label =
                        if (state.selectedProfile?.allowUnencryptedKey == true) {
                            "Passphrase (optional)"
                        } else {
                            "Passphrase"
                        },
                    visualTransformation = PasswordVisualTransformation(),
                )
                TerminalFormCheckboxRow(
                    checked = state.selectedProfile?.allowUnencryptedKey == true,
                    onCheckedChange = onAllowUnencryptedKeyChange,
                    label = "Allow unencrypted key for this profile",
                )
                selectedSession.pendingHostTrust?.let { pending ->
                    TerminalInfoCard(
                        text =
                            if (pending.replacesExistingTrust) {
                                "Changed host fingerprint: ${pending.fingerprint}"
                            } else {
                                "Host fingerprint: ${pending.fingerprint}"
                            },
                    )
                    TerminalPrimaryButton(
                        text =
                            if (pending.replacesExistingTrust) {
                                "Trust new host and connect"
                            } else {
                                "Trust host and connect"
                            },
                        onClick = onTrustHost,
                    )
                }
                if (selectedSession.pendingHostTrust == null) {
                    TerminalPrimaryButton(
                        text =
                            if (selectedSession.status == TerminalStatus.Connecting) {
                                "Connecting"
                            } else {
                                "Connect"
                            },
                        onClick = onConnect,
                        enabled = selectedSession.status != TerminalStatus.Connecting,
                    )
                }
                selectedSession.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                        color = colors.red,
                    )
                }
            }
            return@Column
        }
        TermuxTerminalSurface(
            output = output,
            onInput = onSend,
            onResize = onResize,
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth(),
        )
        TerminalAccessoryBar(
            pendingModifier = pendingModifier,
            onModifier = { modifierValue ->
                pendingModifier = if (pendingModifier == modifierValue) null else modifierValue
            },
            onSendText = { text ->
                onSend(TerminalInputEncoder.text(text, pendingModifier))
                pendingModifier = null
            },
            onDirection = { direction ->
                onSend(TerminalInputEncoder.direction(direction, pendingModifier))
                pendingModifier = null
            },
            onClear = onClear,
            onPaste = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                if (text.isNotEmpty()) onSend(text.encodeToByteArray())
            },
        )
    }
}

@Composable
private fun TerminalAccessoryBar(
    pendingModifier: TerminalModifier?,
    onModifier: (TerminalModifier) -> Unit,
    onSendText: (String) -> Unit,
    onDirection: (TerminalDirection) -> Unit,
    onClear: () -> Unit,
    onPaste: () -> Unit,
) {
    val colors = rememberSidebarColorPalette()
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(colors.background)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TerminalAccessoryKey(label = "Esc", onClick = { onSendText("\u001B") })
        TerminalAccessoryKey(
            label = if (pendingModifier == TerminalModifier.Ctrl) "Ctrl*" else "Ctrl",
            selected = pendingModifier == TerminalModifier.Ctrl,
            onClick = { onModifier(TerminalModifier.Ctrl) },
        )
        TerminalAccessoryKey(
            label = if (pendingModifier == TerminalModifier.Alt) "Alt*" else "Alt",
            selected = pendingModifier == TerminalModifier.Alt,
            onClick = { onModifier(TerminalModifier.Alt) },
        )
        TerminalAccessoryKey(label = "Tab", onClick = { onSendText("\t") })
        TerminalAccessoryKey(label = "Enter", onClick = { onSendText("\r") })
        listOf("/", "|", "~", "-").forEach { value ->
            TerminalAccessoryKey(label = value, onClick = { onSendText(value) })
        }
        TerminalAccessoryKey(
            label = "Up",
            leadingIcon = {
                Icon(
                    Icons.Outlined.KeyboardArrowUp,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primaryText,
                )
            },
            onClick = { onDirection(TerminalDirection.Up) },
        )
        TerminalAccessoryKey(
            label = "Down",
            leadingIcon = {
                Icon(
                    Icons.Outlined.KeyboardArrowDown,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primaryText,
                )
            },
            onClick = { onDirection(TerminalDirection.Down) },
        )
        TerminalAccessoryKey(
            label = "Left",
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowLeft,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primaryText,
                )
            },
            onClick = { onDirection(TerminalDirection.Left) },
        )
        TerminalAccessoryKey(
            label = "Right",
            leadingIcon = {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = colors.primaryText,
                )
            },
            onClick = { onDirection(TerminalDirection.Right) },
        )
        TerminalAccessoryKey(label = "Clear", onClick = onClear)
        TerminalAccessoryKey(label = "Paste", onClick = onPaste)
    }
}
