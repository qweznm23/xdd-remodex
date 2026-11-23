package com.remodex.mobile.ui.navigation

import android.net.Uri
import com.remodex.mobile.ui.draft.NewChatDraftRoute

/** Top-level destinations for [AppNavHost] (parity with ContentView navigation stack). */
object AppRoutes {
    const val Home = "home"
    const val Settings = "settings"
    const val MyDevices = "my_devices"
    const val Archived = "archived"
    const val About = "about"
    const val WhatsNew = "whats_new"
    const val TesterHq = "tester_hq"
    const val Terminal = "terminal"
    const val TerminalCdHintQuery = "cdHint"

    const val NewChatDraftRouteIdArg = "routeId"
    const val NewChatDraftSourceArg = "source"
    const val NewChatDraftProjectPathArg = "preferredProjectPath"

    const val NewChatDraftRecoveryThreadArg = "recoveryThreadId"
    const val NewChatDraftRecoveryDeviceArg = "recoveryDeviceId"

    const val NewChatDraft = "new_chat_draft/{routeId}/{source}?" +
        "$NewChatDraftProjectPathArg={$NewChatDraftProjectPathArg}&" +
        "$NewChatDraftRecoveryThreadArg={$NewChatDraftRecoveryThreadArg}&" +
        "$NewChatDraftRecoveryDeviceArg={$NewChatDraftRecoveryDeviceArg}"

    fun newChatDraftRoute(route: NewChatDraftRoute): String {
        val query = listOfNotNull(
            route.preferredProjectPath?.let { "$NewChatDraftProjectPathArg=${Uri.encode(it)}" },
            route.recoveryThreadId?.let { "$NewChatDraftRecoveryThreadArg=${Uri.encode(it)}" },
            route.recoveryDeviceId?.let { "$NewChatDraftRecoveryDeviceArg=${Uri.encode(it)}" },
        )
        val base = "new_chat_draft/${route.id}/${route.source.name}"
        return if (query.isEmpty()) base else query.joinToString("&", prefix = "$base?")
    }

    fun terminalRoute(cdHint: String? = null): String {
        val path = cdHint?.trim()?.takeIf { it.isNotEmpty() } ?: return Terminal
        return "$Terminal?$TerminalCdHintQuery=${Uri.encode(path)}"
    }
}
