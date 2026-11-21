package com.remodex.mobile.ui.dev

import com.remodex.mobile.ui.theme.RemodexAlertDialog
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.remodex.mobile.R
import com.remodex.mobile.pairing.GooglePairingScanner
import com.remodex.mobile.pairing.PairingScannerController
import com.remodex.mobile.pairing.PlayServicesUnavailable
import com.remodex.mobile.pairing.ScannerPhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Explicit launch: returning from Google's scanner never triggers another scan automatically. */
@Composable
fun PairingQrScanner(modifier: Modifier = Modifier, onDecodedPayload: (String) -> Unit) {
    val context = LocalContext.current
    val controller = remember(context) { PairingScannerController(GooglePairingScanner(context)) }
    val state by controller.state.collectAsStateWithLifecycle()
    val onDecoded by rememberUpdatedState(onDecodedPayload)
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var showDetails by remember { mutableStateOf(false) }
    val failure = state.failure
    val diagnostic = remember(failure) {
        failure?.diagnostic()?.let { if (it.length > 32_000) it.take(32_000) + "\n[diagnostic truncated]" else it }
    }
    LaunchedEffect(failure) {
        if (failure != null && diagnostic != null) {
            Log.e("RemodexScanner", diagnostic)
            try {
                withContext(Dispatchers.IO) {
                    File(context.filesDir, "pairing-scanner-last-error.txt").writeText(diagnostic)
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (secondary: Exception) {
                failure.cause.addSuppressed(secondary)
                Log.e("RemodexScanner", "Could not save scan diagnostic; primary failure retained", failure.cause)
            }
        }
    }
    Column(modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.google_scan_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(when (state.phase) {
                ScannerPhase.PREPARING -> R.string.google_scan_preparing
                ScannerPhase.DOWNLOADING -> R.string.google_scan_downloading
                ScannerPhase.PAUSED -> R.string.google_scan_paused
                ScannerPhase.INSTALLING -> R.string.google_scan_installing
                ScannerPhase.SCANNING -> R.string.google_scan_opened
                ScannerPhase.CANCELED -> R.string.google_scan_canceled
                ScannerPhase.FAILED -> if (failure?.cause is PlayServicesUnavailable) R.string.google_scan_no_services else R.string.google_scan_failed
                ScannerPhase.IDLE -> R.string.google_scan_hint
            }),
            style = MaterialTheme.typography.bodyMedium,
            color = LocalContentColor.current.copy(alpha = 0.72f),
        )
        if (state.busy && state.phase != ScannerPhase.SCANNING) {
            val progress = state.progress
            if (progress != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.google_scan_percent, (progress * 100).toInt()))
            } else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            TextButton(onClick = { job?.cancel() }) { Text(stringResource(R.string.google_scan_cancel_wait)) }
        }
        Button(
            onClick = {
                if (job?.isActive != true) job = scope.launch {
                    controller.scan()?.let { onDecoded(it) }
                }
            },
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(stringResource(if (failure != null) R.string.google_scan_retry else R.string.google_scan_start)) }
        if (failure != null) TextButton(onClick = { showDetails = true }) {
            Text(stringResource(R.string.google_scan_details))
        }
    }
    if (showDetails && diagnostic != null) RemodexAlertDialog(
        onDismissRequest = { showDetails = false },
        title = { Text(stringResource(R.string.google_scan_details)) },
        text = { SelectionContainer { Text(diagnostic, modifier = Modifier.verticalScroll(rememberScrollState())) } },
        confirmButton = { TextButton(onClick = { showDetails = false }) { Text(stringResource(R.string.google_scan_close)) } },
    )
}
