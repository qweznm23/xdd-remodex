package com.remodex.mobile.ui.draft

import java.util.UUID

enum class NewChatDraftSource {
    generalChat,
    folderChat,
    recoveredChat,
}

data class NewChatDraftRoute(
    val id: String,
    val source: NewChatDraftSource,
    val preferredProjectPath: String? = null,
    val recoveryThreadId: String? = null,
    val recoveryDeviceId: String? = null,
) {
    companion object {
        fun create(
            source: NewChatDraftSource,
            preferredProjectPath: String? = null,
            recoveryThreadId: String? = null,
            recoveryDeviceId: String? = null,
        ): NewChatDraftRoute =
            NewChatDraftRoute(
                id = "new-chat-draft-${UUID.randomUUID()}",
                source = source,
                preferredProjectPath = preferredProjectPath?.trim()?.takeIf { it.isNotEmpty() },
                recoveryThreadId = recoveryThreadId,
                recoveryDeviceId = recoveryDeviceId,
            )
    }
}
