package com.remodex.mobile.ui.onboarding

import com.remodex.mobile.ui.theme.conversationColors
import com.remodex.mobile.ui.theme.RemodexAlertDialog
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import com.remodex.mobile.ui.theme.RemodexPageHeader
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.remodex.mobile.AppContainer
import com.remodex.mobile.R
import com.remodex.mobile.core.config.AppEnvironment
import com.remodex.mobile.core.model.CodexPairingQRPayload
import com.remodex.mobile.core.model.CodexTrustedMacRegistry
import com.remodex.mobile.core.security.CodexSecureKeys
import com.remodex.mobile.core.transport.validateRelayUrl
import com.remodex.mobile.data.CodexRepository
import com.remodex.mobile.data.QrPairingValidationResult
import com.remodex.mobile.data.resolvePairingCode
import com.remodex.mobile.data.validatePairingQrCode
import com.remodex.mobile.pairing.LoopbackRelayException
import com.remodex.mobile.pairing.applyQrPayloadAndConnect
import com.remodex.mobile.ui.dev.PairingQrScanner
import com.remodex.mobile.ui.home.BridgeUpdateSheet
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
fun QrScannerScreen(
    repository: CodexRepository,
    onBack: () -> Unit,
    onPairingComplete: () -> Unit,
    openManualPairingInitially: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var bridgeUpdateTitle by remember { mutableStateOf("") }
    var bridgeUpdateMessage by remember { mutableStateOf("") }
    var bridgeUpdateCommand by remember { mutableStateOf<String?>(null) }
    var bridgeUpdateVisible by remember { mutableStateOf(false) }
    var scanEnabled by remember { mutableStateOf(true) }
    var pendingPayload by remember { mutableStateOf<CodexPairingQRPayload?>(null) }
    var scannerResetNonce by remember { mutableStateOf(0) }
    var manualPairingDialogVisible by remember { mutableStateOf(false) }
    var manualPairingText by remember { mutableStateOf("") }

    LaunchedEffect(openManualPairingInitially) {
        if (openManualPairingInitially) {
            manualPairingDialogVisible = true
        }
    }

    fun resetScanner() {
        pendingPayload = null
        scanEnabled = true
        statusMessage = null
        errorMessage = null
        bridgeUpdateTitle = ""
        bridgeUpdateMessage = ""
        bridgeUpdateCommand = null
        bridgeUpdateVisible = false
        scannerResetNonce += 1
    }

    fun runConnect(payload: CodexPairingQRPayload) {
        scope.launch {
            connecting = true
            errorMessage = null
            statusMessage = context.getString(R.string.qr_scanner_valid)
            try {
                applyQrPayloadAndConnect(
                    repository = repository,
                    sessionPersistence = AppContainer.sessionPersistence,
                    secureStore = AppContainer.secureStore,
                    payload = payload,
                    relayHostOverride = "",
                    tokenOverride = "",
                )
                runCatching {
                    AppContainer.betaEngagementRepository.recordMissionEvent(
                        eventType = "qr_pairing_completed",
                        screen = "qr_pairing",
                    )
                }
                onPairingComplete()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: LoopbackRelayException) {
                errorMessage = e.message
                pendingPayload = null
            } catch (e: Exception) {
                errorMessage = e.message ?: e::class.simpleName
                pendingPayload = null
            } finally {
                connecting = false
            }
        }
    }

    fun handlePairingResult(result: QrPairingValidationResult) {
        when (result) {
            is QrPairingValidationResult.Success -> {
                pendingPayload = result.payload
                scanEnabled = false
                manualPairingDialogVisible = false
                runConnect(result.payload)
            }
            is QrPairingValidationResult.ShortCode -> {
                scanEnabled = false
                errorMessage = "Use Enter pairing code for the short code from your desktop."
            }
            is QrPairingValidationResult.ScanError -> {
                scanEnabled = false
                errorMessage = result.message
            }
            is QrPairingValidationResult.BridgeUpdateRequired -> {
                scanEnabled = false
                bridgeUpdateTitle = result.title
                bridgeUpdateMessage = result.message
                bridgeUpdateCommand = result.command
                bridgeUpdateVisible = true
            }
        }
    }

    fun submitManualPairing() {
        scope.launch {
            val manualInput = parseManualPairingInput(manualPairingText)
            val raw = manualInput.codeOrPayload
            if (raw.isEmpty()) {
                errorMessage = "Enter the pairing code from your desktop."
                return@launch
            }
            errorMessage = null
            statusMessage = "Resolving pairing code"
            connecting = true
            try {
                when (val directResult = validatePairingQrCode(raw)) {
                    is QrPairingValidationResult.Success -> {
                        connecting = false
                        handlePairingResult(directResult)
                    }
                    is QrPairingValidationResult.BridgeUpdateRequired -> {
                        connecting = false
                        handlePairingResult(directResult)
                    }
                    is QrPairingValidationResult.ShortCode -> {
                        val resolved = resolveManualPairingCode(context, directResult.code)
                        connecting = false
                        handlePairingResult(resolved)
                    }
                    is QrPairingValidationResult.ScanError -> {
                        val resolved = resolveManualPairingCode(context, raw, manualInput.relayUrl)
                        connecting = false
                        handlePairingResult(resolved)
                    }
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                connecting = false
                errorMessage = e.message ?: e::class.simpleName
            }
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = MaterialTheme.colorScheme.conversationColors().reading,
        topBar = { RemodexPageHeader(stringResource(R.string.qr_scanner_title), onBack) },
    ) { insets ->
        Column(
            modifier = Modifier.fillMaxSize().padding(insets)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.conversationColors().dialog,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ) {
                Column {
                    if (scanEnabled && pendingPayload == null && !manualPairingDialogVisible) {
                        key(scannerResetNonce) {
                            PairingQrScanner(
                                modifier = Modifier.fillMaxWidth(),
                                onDecodedPayload = { raw ->
                                    errorMessage = null
                                    handlePairingResult(validatePairingQrCode(raw))
                                },
                            )
                        }
                    }
                    if (connecting) {
                        Row(
                            Modifier.padding(20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            Text(statusMessage ?: stringResource(R.string.qr_connecting))
                        }
                    }
                    errorMessage?.let { error ->
                        Column(Modifier.padding(20.dp)) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = ::resetScanner, enabled = !connecting) {
                                Text(stringResource(R.string.qr_scan_again))
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            androidx.compose.material3.FilledTonalButton(
                onClick = { manualPairingDialogVisible = true },
                enabled = !connecting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.pairing_enter_code)) }

        }
    }

    BridgeUpdateSheet(
        visible = bridgeUpdateVisible,
        title = bridgeUpdateTitle,
        message = bridgeUpdateMessage,
        installCommand = bridgeUpdateCommand,
        onDismiss = {
            bridgeUpdateVisible = false
            bridgeUpdateCommand = null
            resetScanner()
        },
        onRetry = {
            bridgeUpdateVisible = false
            bridgeUpdateCommand = null
            resetScanner()
        },
        onScanNewQr = {
            bridgeUpdateVisible = false
            bridgeUpdateCommand = null
            resetScanner()
        },
    )

    if (manualPairingDialogVisible) {
        RemodexAlertDialog(
            onDismissRequest = { if (!connecting) manualPairingDialogVisible = false },
            title = { Text(stringResource(R.string.pairing_manual)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = manualPairingText,
                        onValueChange = { manualPairingText = it },
                        label = { Text(stringResource(R.string.pairing_code_label)) },
                        placeholder = { Text("ABCDEFGHJK") },
                        singleLine = false,
                        minLines = 2,
                        enabled = !connecting,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = ::submitManualPairing,
                    enabled = !connecting,
                ) {
                    Text(stringResource(R.string.pairing_connect))
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { manualPairingDialogVisible = false },
                    enabled = !connecting,
                ) {
                    Text(stringResource(R.string.git_branch_create_cancel))
                }
            },
        )
    }
}

private data class ManualPairingInput(
    val codeOrPayload: String,
    val relayUrl: String?,
)

private val manualPairingRelayUrlRegex = Regex("""(?i)\b(?:wss?|https?)://[^\s,;)"']+""")
private val manualPairingCodeLabelRegex =
    Regex("""(?im)^\s*(?:pairing\s+)?code\s*[:=]\s*([A-Za-z0-9._:-]+)\s*$""")
private val manualPairingShortCodeRegex = Regex("""\b[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{8,12}\b""")
private val manualPairingTokenRegex = Regex("""[A-Za-z0-9._:-]{4,256}""")

private fun parseManualPairingInput(rawText: String): ManualPairingInput {
    val trimmed = rawText.trim()
    if (trimmed.isEmpty()) return ManualPairingInput(codeOrPayload = "", relayUrl = null)

    val relayUrl = extractManualRelayUrl(trimmed)
    if (trimmed.startsWith("{")) {
        return ManualPairingInput(codeOrPayload = trimmed, relayUrl = relayUrl)
    }

    val labeledCode = manualPairingCodeLabelRegex.find(trimmed)?.groups?.get(1)?.value
    if (!labeledCode.isNullOrBlank()) {
        return ManualPairingInput(codeOrPayload = labeledCode.trim(), relayUrl = relayUrl)
    }

    val textWithoutRelay =
        relayUrl?.let { trimmed.replace(it, " ") } ?: trimmed
    val shortCode = manualPairingShortCodeRegex.find(textWithoutRelay.uppercase())?.value
    if (!shortCode.isNullOrBlank()) {
        return ManualPairingInput(codeOrPayload = shortCode, relayUrl = relayUrl)
    }

    val token =
        manualPairingTokenRegex
            .findAll(textWithoutRelay)
            .map { it.value.trim() }
            .firstOrNull { token ->
                !token.equals("code", ignoreCase = true) &&
                    !token.equals("pairing", ignoreCase = true)
            }
    return ManualPairingInput(codeOrPayload = token ?: textWithoutRelay.trim(), relayUrl = relayUrl)
}

private fun extractManualRelayUrl(rawText: String): String? =
    manualPairingRelayUrlRegex
        .find(rawText)
        ?.value
        ?.trim()
        ?.trimEnd('.', ',', ';', ')', ']', '}', '"', '\'')
        ?.takeIf { it.isNotBlank() }

private fun manualRelayCandidates(
    context: android.content.Context,
    pastedRelayUrl: String?,
): List<String> {
    val snapshot = AppContainer.sessionPersistence.loadRelaySnapshot()
    val registry =
        AppContainer.secureStore.readCodable<CodexTrustedMacRegistry>(CodexSecureKeys.trustedMacRegistry)
    val preferredTrustedRelay =
        snapshot.lastTrustedMacDeviceId
            ?.let { registry?.records?.get(it) }
            ?.relayURL
            ?: registry?.records?.values?.firstOrNull { !it.relayURL.isNullOrBlank() }?.relayURL

    return listOf(
        pastedRelayUrl,
        snapshot.relayUrl,
        preferredTrustedRelay,
        AppEnvironment.relayBaseURL(context),
    )
        .mapNotNull { it?.trim()?.takeIf(String::isNotEmpty) }
        .filter { validateRelayUrl(it) != null }
        .distinct()
}

private suspend fun resolveManualPairingCode(
    context: android.content.Context,
    code: String,
    pastedRelayUrl: String? = null,
): QrPairingValidationResult {
    val relayCandidates = manualRelayCandidates(context, pastedRelayUrl)
    if (relayCandidates.isEmpty()) {
        return QrPairingValidationResult.ScanError(
            "This device does not know which relay to ask for that pairing code yet. Scan the QR code instead.",
        )
    }
    return resolvePairingCodeWithCandidates(relayCandidates, code)
}

private suspend fun resolvePairingCodeWithCandidates(
    relayCandidates: List<String>,
    code: String,
): QrPairingValidationResult {
    var lastScanError: QrPairingValidationResult.ScanError? = null
    for (relayUrl in relayCandidates) {
        when (
            val result =
                resolvePairingCode(
                    httpClient = AppContainer.httpCallClient,
                    relayUrl = relayUrl,
                    code = code,
                )
        ) {
            is QrPairingValidationResult.Success -> return result
            is QrPairingValidationResult.BridgeUpdateRequired -> return result
            is QrPairingValidationResult.ShortCode ->
                lastScanError = QrPairingValidationResult.ScanError(
                    "The relay returned another short pairing code instead of pairing metadata.",
                )
            is QrPairingValidationResult.ScanError -> lastScanError = result
        }
    }
    return lastScanError
        ?: QrPairingValidationResult.ScanError(
            "Pairing code could not be resolved. Generate a fresh code from your desktop.",
        )
}
