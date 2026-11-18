package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexFileAttachment
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class UploadedAttachmentDraftTest {
    @Test fun completeFileReferenceReplacesTruncatedTextOrBinaryPlaceholder() {
        val path = "/computer/uploads/archive with spaces.zip"
        val result = appendFileAttachmentsToDraft("Read this file", listOf(CodexFileAttachment(
            fileName = "archive.zip", mimeType = "application/zip", sizeBytes = 50 * 1024 * 1024,
            textContent = "old truncated preview", remotePath = path,
        )), "binary placeholder")
        assertTrue(result.contains("\"$path\""))
        assertTrue(result.startsWith("Read this file"))
        assertFalse(result.contains("old truncated preview"))
        assertFalse(result.contains("binary placeholder"))
    }
}
