package com.remodex.mobile.ui.turn

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.R
import com.remodex.mobile.core.transport.ConnectionState
import com.remodex.mobile.ui.home.RootReconnectAttempt
import com.remodex.mobile.ui.home.RootReconnectRecoveryAction
import com.remodex.mobile.ui.home.RootReconnectUiState

internal enum class TurnConnectionRecoveryStatus(val label: String) {
    Interrupted("Interrupted"),
    Reconnecting("Reconnecting"),
}

internal sealed interface TurnConnectionRecoveryTrailing {
    data class Action(val label: String) : TurnConnectionRecoveryTrailing
    data object Progress : TurnConnectionRecoveryTrailing
}

internal data class TurnConnectionRecoverySnapshot(
    val summary: String,
    val status: TurnConnectionRecoveryStatus,
    val trailing: TurnConnectionRecoveryTrailing,
    val summaryResId: Int? = null,
) {
    val isActionable: Boolean get() = trailing is TurnConnectionRecoveryTrailing.Action
}

internal object TurnConnectionRecoverySnapshotBuilder {
    fun makeSnapshot(
        hasReconnectCandidate: Boolean,
        connectionState: ConnectionState,
        reconnectUiState: RootReconnectUiState,
    ): TurnConnectionRecoverySnapshot? {
        if (!hasReconnectCandidate || connectionState is ConnectionState.Connected) return null

        val trimmedError = reconnectUiState.lastErrorMessage?.trim()?.takeIf { it.isNotEmpty() }
        if (reconnectUiState.attempt == RootReconnectAttempt.WakeDisplay) {
            return TurnConnectionRecoverySnapshot(
                summary = trimmedError ?: "Trying to wake the computer display.",
                summaryResId = if (trimmedError == null) R.string.connection_waking_hint else null,
                status = TurnConnectionRecoveryStatus.Reconnecting,
                trailing = TurnConnectionRecoveryTrailing.Progress,
            )
        }

        if (connectionState is ConnectionState.Connecting || reconnectUiState.isAttempting) {
            return TurnConnectionRecoverySnapshot(
                summary = "Trying to reconnect to your computer.",
                summaryResId = R.string.connection_reconnecting_hint,
                status = TurnConnectionRecoveryStatus.Reconnecting,
                trailing = TurnConnectionRecoveryTrailing.Progress,
            )
        }

        if (reconnectUiState.recoveryAction == RootReconnectRecoveryAction.ScanNewQr) {
            return TurnConnectionRecoverySnapshot(
                summary = trimmedError ?: "Scan a new QR code to reconnect this chat.",
                summaryResId = if (trimmedError == null) R.string.connection_scan_hint else null,
                status = TurnConnectionRecoveryStatus.Interrupted,
                trailing = TurnConnectionRecoveryTrailing.Action("Scan QR"),
            )
        }

        if (reconnectUiState.wakeDisplayAvailable) {
            return TurnConnectionRecoverySnapshot(
                summary = trimmedError ?: "Wake the computer screen to keep this chat in sync.",
                summaryResId = if (trimmedError == null) R.string.connection_wake_hint else null,
                status = TurnConnectionRecoveryStatus.Interrupted,
                trailing = TurnConnectionRecoveryTrailing.Action("Wake"),
            )
        }

        return TurnConnectionRecoverySnapshot(
            summary = trimmedError ?: "Reconnect to your computer to keep this chat in sync.",
                summaryResId = if (trimmedError == null) R.string.connection_retry_hint else null,
            status = TurnConnectionRecoveryStatus.Interrupted,
            trailing = TurnConnectionRecoveryTrailing.Action("Reconnect"),
        )
    }
}

@Composable
internal fun TurnConnectionRecoveryCard(
    snapshot: TurnConnectionRecoverySnapshot,
    onTap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        onClick = onTap,
        enabled = snapshot.isActionable,
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = stringResource(if (snapshot.status == TurnConnectionRecoveryStatus.Reconnecting) R.string.connection_reconnecting else R.string.connection_interrupted),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = snapshot.summaryResId?.let { stringResource(it) } ?: snapshot.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
            Spacer(Modifier.size(4.dp))
            when (val trailing = snapshot.trailing) {
                is TurnConnectionRecoveryTrailing.Action -> {
                    Text(
                        text = when (trailing.label) {
                            "Wake" -> stringResource(R.string.connection_wake_action)
                            "Scan QR" -> stringResource(R.string.my_devices_scan_qr)
                            "Reconnect" -> stringResource(R.string.nav_reconnect)
                            else -> trailing.label
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                TurnConnectionRecoveryTrailing.Progress -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                }
            }
        }
    }
}
