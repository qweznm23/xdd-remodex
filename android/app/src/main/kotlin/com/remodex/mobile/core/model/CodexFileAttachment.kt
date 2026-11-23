package com.remodex.mobile.core.model

import java.util.UUID
import kotlinx.serialization.Serializable

/**
 * Local non-image attachment prepared by the composer.
 * Completed uploads are referenced by their computer path in the turn/start text payload.
 */
@Serializable
data class CodexFileAttachment(
    val id: String = UUID.randomUUID().toString(),
    val fileName: String,
    val mimeType: String?,
    val sizeBytes: Int,
    val textContent: String?,
    val sourceUri: String? = null,
    val remotePath: String? = null,
)

