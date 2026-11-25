package com.remodex.mobile.ui.mydevices

import com.remodex.mobile.ui.theme.RemodexDropdownMenu
import com.remodex.mobile.ui.theme.conversationColors
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.ListItemDefaults
import androidx.compose.ui.graphics.Color
import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.ui.theme.RemodexPageHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyDevicesScreen(
    repository: CodexRepository,
    onNavigateBack: () -> Unit,
    onScanQrCode: () -> Unit,
    onPairWithCode: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateBack)
    val scope = rememberCoroutineScope()

    val trustedDevices by repository.trustedDevices.collectAsStateWithLifecycle()
    val switchingDeviceId by repository.switchingDeviceId.collectAsStateWithLifecycle()
    val deviceSwitchNotice by repository.deviceSwitchNotice.collectAsStateWithLifecycle()
    val currentTrustedMacDeviceId by repository.currentTrustedMacDeviceId.collectAsStateWithLifecycle()
    val previousTrustedMacDeviceId by repository.previousTrustedMacDeviceId.collectAsStateWithLifecycle()
    val relayMacDeviceId by repository.relayMacDeviceId.collectAsStateWithLifecycle()
    val connectionState by repository.connectionState.collectAsStateWithLifecycle()
    val runningTurnByThread by repository.runningTurnIdByThread.collectAsStateWithLifecycle()
    val protectedRunningFallback by repository.protectedRunningFallbackThreadIds.collectAsStateWithLifecycle()

    var visibilityRevision by remember { mutableStateOf(0) }
    var pendingForgetDeviceId by remember { mutableStateOf<String?>(null) }
    var pendingSwitchDeviceId by remember { mutableStateOf<String?>(null) }
    var menuDeviceId by remember { mutableStateOf<String?>(null) }

    val trustedDeviceContext =
        remember(
            trustedDevices,
            currentTrustedMacDeviceId,
            previousTrustedMacDeviceId,
            relayMacDeviceId,
            connectionState,
            switchingDeviceId,
            visibilityRevision,
        ) {
            TrustedDevicePresentationContext(
                records = trustedDevices,
                currentTrustedMacDeviceId = currentTrustedMacDeviceId,
                previousTrustedMacDeviceId = previousTrustedMacDeviceId,
                relayMacDeviceId = relayMacDeviceId,
                isConnected = connectionState.isConnectedNow(),
                switchingDeviceId = switchingDeviceId,
            )
        }
    val devices = remember(trustedDeviceContext) { MyDevicesPresentation.rowModels(trustedDeviceContext) }
    val currentDevice = remember(devices) { devices.firstOrNull { it.isCurrent } }
    val pairedDevices = remember(devices) { devices.filterNot { it.isCurrent } }
    val isSwitching = switchingDeviceId != null
    val requiresSwitchConfirmation =
        remember(runningTurnByThread, protectedRunningFallback) {
            runningTurnByThread.isNotEmpty() || protectedRunningFallback.isNotEmpty()
        }

    fun requestSwitch(deviceId: String) {
        if (isSwitching || deviceId == currentTrustedMacDeviceId) return
        if (requiresSwitchConfirmation) {
            pendingSwitchDeviceId = deviceId
        } else {
            scope.launch { runCatching { repository.switchToTrustedDevice(deviceId) } }
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            RemodexPageHeader(stringResource(R.string.my_devices_title), onNavigateBack)
        },
        containerColor = MaterialTheme.colorScheme.conversationColors().reading,
    ) { innerPadding ->
        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                deviceSwitchNotice?.takeIf { it.isNotBlank() }?.let { notice ->
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                currentDevice?.let { device ->
                    MyDevicesSectionHeader(text = stringResource(R.string.my_devices_current_device))
                    MyDevicesGroupedCard {
                        CurrentDeviceRow(device = device)
                    }
                }

                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(enabled = !isSwitching, onClick = {
                        onNavigateBack()
                        onScanQrCode()
                    }) { Text(stringResource(R.string.my_devices_scan_qr_code)) }
                    TextButton(enabled = !isSwitching, onClick = {
                        onNavigateBack()
                        onPairWithCode()
                    }) { Text(stringResource(R.string.my_devices_pair_code)) }
                }
                MyDevicesSectionHeader(text = stringResource(R.string.my_devices_paired_devices))
                MyDevicesGroupedCard {
                    if (pairedDevices.isEmpty()) {
                        Text(
                            text = stringResource(R.string.my_devices_no_paired),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                        )
                    } else {
                        pairedDevices.forEachIndexed { index, device ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(start = 64.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                                )
                            }
                            PairedDeviceRow(
                                device = device,
                                enabled = !isSwitching,
                                onSwitch = { requestSwitch(device.deviceId) },
                                menuExpanded = menuDeviceId == device.deviceId,
                                onMenuExpandedChange = { expanded ->
                                    menuDeviceId = if (expanded) device.deviceId else null
                                },
                                onScanQr = {
                                    menuDeviceId = null
                                    onNavigateBack()
                                    onScanQrCode()
                                },
                                onPairWithCode = {
                                    menuDeviceId = null
                                    onNavigateBack()
                                    onPairWithCode()
                                },
                                onToggleMenuVisibility = { visible ->
                                    MyDeviceMenuVisibilityStore.setVisible(visible, device.deviceId)
                                    visibilityRevision++
                                },
                                onForget = {
                                    menuDeviceId = null
                                    pendingForgetDeviceId = device.deviceId
                                },
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.size(8.dp))
            }

            if (isSwitching) {
                DeviceSwitchingOverlay(
                    deviceName = devices.firstOrNull { it.deviceId == switchingDeviceId }?.compactDisplayName,
                    onCancel = {
                        scope.launch { runCatching { repository.cancelDeviceSwitch() } }
                    },
                    modifier = Modifier.matchParentSize(),
                )
            }
        }
    }

    pendingSwitchDeviceId?.let { deviceId ->
        RemodexAlertDialog(
            onDismissRequest = { pendingSwitchDeviceId = null },
            title = { Text(stringResource(R.string.my_devices_switch_confirm_title)) },
            text = { Text(stringResource(R.string.my_devices_switch_confirm_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch { runCatching { repository.switchToTrustedDevice(deviceId) } }
                        pendingSwitchDeviceId = null
                    },
                ) {
                    Text(stringResource(R.string.my_devices_switch))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSwitchDeviceId = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    pendingForgetDeviceId?.let { deviceId ->
        RemodexAlertDialog(
            onDismissRequest = { pendingForgetDeviceId = null },
            title = { Text(stringResource(R.string.my_devices_forget_title)) },
            text = { Text(stringResource(R.string.my_devices_forget_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        repository.forgetTrustedDevice(deviceId)
                        pendingForgetDeviceId = null
                        visibilityRevision++
                    },
                ) {
                    Text(stringResource(R.string.my_devices_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingForgetDeviceId = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun MyDevicesSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(start = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun MyDevicesGroupedCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    com.remodex.mobile.ui.theme.RemodexGroup(modifier) { content() }
}

@Composable
private fun CurrentDeviceRow(
    device: MyDeviceRowModel,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier,
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        leadingContent = { DeviceAvatar(device = device, diameter = 40.dp) },
        headlineContent = { Text(device.primaryName, style = MaterialTheme.typography.titleMedium) },
        supportingContent = { DeviceStatusSubtitle(device = device) },
    )
}

@Composable
private fun PairedDeviceRow(
    device: MyDeviceRowModel,
    enabled: Boolean,
    onSwitch: () -> Unit,
    menuExpanded: Boolean,
    onMenuExpandedChange: (Boolean) -> Unit,
    onScanQr: () -> Unit,
    onPairWithCode: () -> Unit,
    onToggleMenuVisibility: (Boolean) -> Unit,
    onForget: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        modifier = modifier.fillMaxWidth().clickable(enabled = enabled, onClick = onSwitch),
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        headlineContent = { Text(device.primaryName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { DeviceStatusSubtitle(device = device) },
        leadingContent = { DeviceAvatar(device = device, diameter = 40.dp) },
        trailingContent = {
            Box {
                IconButton(onClick = { onMenuExpandedChange(true) }, enabled = enabled) {
                    Icon(Icons.Outlined.MoreHoriz, stringResource(R.string.my_devices_device_actions_cd))
                }
                val showInMenu = MyDeviceMenuVisibilityStore.isVisible(device.deviceId)
                RemodexDropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { onMenuExpandedChange(false) },
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.my_devices_scan_qr)) },
                        onClick = onScanQr,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.my_devices_pair_code)) },
                        onClick = onPairWithCode,
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                if (showInMenu) {
                                    stringResource(R.string.my_devices_hide_from_menu)
                                } else {
                                    stringResource(R.string.my_devices_show_in_menu)
                                },
                            )
                        },
                        onClick = {
                            onToggleMenuVisibility(!showInMenu)
                            onMenuExpandedChange(false)
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.my_devices_forget),
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = onForget,
                    )
                }
            }
        },
    )
}

@Composable
private fun DeviceAvatar(
    device: MyDeviceRowModel,
    diameter: androidx.compose.ui.unit.Dp,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .size(diameter)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Outlined.Computer,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(diameter * 0.45f),
        )
        if (device.isConnected) {
            Box(
                modifier =
                    Modifier
                        .align(Alignment.BottomEnd)
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
}

@Composable
private fun DeviceStatusSubtitle(
    device: MyDeviceRowModel,
    modifier: Modifier = Modifier,
) {
    Text(
        text = listOfNotNull(
            when (device.status) {
                "Switching" -> stringResource(R.string.device_switching)
                "Connected" -> stringResource(R.string.my_devices_connected)
                "Selected" -> stringResource(R.string.device_selected)
                "Previous" -> stringResource(R.string.device_previous)
                "Saved" -> stringResource(R.string.device_saved)
                else -> device.status
            },
            device.detail?.let { detail ->
                val age = Regex("(\\d+)([mhdw]) ago").matchEntire(detail)
                when {
                    detail == "Reloading chats" -> stringResource(R.string.device_reloading)
                    detail == "just now" -> stringResource(R.string.device_just_now)
                    age != null -> stringResource(when (age.groupValues[2]) {
                        "m" -> R.string.device_minutes_ago
                        "h" -> R.string.device_hours_ago
                        "d" -> R.string.device_days_ago
                        else -> R.string.device_weeks_ago
                    }, age.groupValues[1])
                    else -> detail
                }
            },
        ).joinToString(" · "),
        style = MaterialTheme.typography.bodySmall,
        color =
            if (device.isConnected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
private fun DeviceSwitchingOverlay(
    deviceName: String?,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.35f)),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                Text(
                    text = stringResource(R.string.my_devices_switching_title),
                    style = MaterialTheme.typography.titleSmall,
                )
                deviceName?.let { name ->
                    Text(
                        text = name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onCancel) {
                    Text(stringResource(R.string.my_devices_cancel))
                }
            }
        }
    }
}
