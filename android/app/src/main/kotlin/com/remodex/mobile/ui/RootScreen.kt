package com.remodex.mobile.ui

import com.remodex.mobile.ui.theme.singleOwnerPageEntry
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.remodex.mobile.ui.home.RootPhase
import com.remodex.mobile.ui.home.RootViewModel
import com.remodex.mobile.ui.onboarding.OnboardingFlow
import com.remodex.mobile.ui.onboarding.QrScannerScreen
import com.remodex.mobile.ui.shell.MainShell

/**
 * App root: onboarding → QR pairing → main shell ([MainShell]).
 * Parity with [ContentView.swift](CodexMobile/CodexMobile/ContentView.swift) branching.
 */
@Composable
fun RootScreen(modifier: Modifier = Modifier) {
    val repository = LocalCodexRepository.current
    val viewModel: RootViewModel = viewModel(factory = RootViewModel.factory())
    val phase by viewModel.phase.collectAsStateWithLifecycle()

    val pageModifier = modifier.singleOwnerPageEntry(phase) { from, to ->
        from != RootPhase.Onboarding && (to == RootPhase.Main || to == RootPhase.Onboarding)
    }
    when (phase) {
        RootPhase.Onboarding ->
            OnboardingFlow(
                onFinish = viewModel::finishOnboarding,
                modifier = pageModifier,
            )
        RootPhase.PairingScan,
        RootPhase.PairingCode,
        ->
            QrScannerScreen(
                repository = repository,
                onBack = viewModel::closePairingScanner,
                onPairingComplete = viewModel::notifyPairingSuccess,
                openManualPairingInitially = phase == RootPhase.PairingCode,
                modifier = pageModifier,
            )
        RootPhase.Main -> androidx.compose.runtime.CompositionLocalProvider(
            LocalReconnectForMedia provides viewModel::onAppForegrounded,
        ) {
            MainShell(
                viewModel = viewModel,
                onOpenPairingScanner = viewModel::openPairingScanner,
                onOpenPairingCode = viewModel::openPairingCode,
                modifier = pageModifier,
            )
        }
    }
}
