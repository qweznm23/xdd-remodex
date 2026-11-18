package com.remodex.mobile.data

import com.remodex.mobile.core.model.CodexImageAttachment

internal object UserChatAttachmentMatcher {
    fun compatible(
        existing: List<CodexImageAttachment>,
        incoming: List<CodexImageAttachment>,
    ): Boolean =
        existing == incoming ||
            existing.isEmpty() ||
            incoming.isEmpty() ||
            existing.contentKeys() == incoming.contentKeys()

    fun merge(
        existing: List<CodexImageAttachment>,
        incoming: List<CodexImageAttachment>,
    ): List<CodexImageAttachment> =
        when {
            existing.isNotEmpty() -> existing.map { saved ->
                val refreshed = incoming.firstOrNull { candidate ->
                    (!saved.sourceURL.isNullOrBlank() && saved.sourceURL == candidate.sourceURL) ||
                        (!saved.payloadDataURL.isNullOrBlank() && saved.payloadDataURL == candidate.payloadDataURL)
                }
                if (!refreshed?.mimeType.isNullOrBlank()) saved.copy(mimeType = refreshed?.mimeType) else saved
            }
            incoming.isNotEmpty() -> incoming
            else -> emptyList()
        }

    private fun List<CodexImageAttachment>.contentKeys(): List<String> =
        map { attachment ->
            attachment.payloadDataURL?.trim()?.takeIf { it.isNotEmpty() }
                ?: attachment.sourceURL?.trim()?.takeIf { it.isNotEmpty() }
                ?: attachment.thumbnailBase64JPEG.trim()
        }
}
