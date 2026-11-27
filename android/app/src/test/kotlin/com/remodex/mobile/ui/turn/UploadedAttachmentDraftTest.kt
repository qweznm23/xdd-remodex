package com.remodex.mobile.ui.turn

import com.remodex.mobile.core.model.CodexFileAttachment
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse
import kotlin.test.assertEquals

class UploadedAttachmentDraftTest {
    @Test fun completeFileReferenceReplacesTruncatedTextOrBinaryPlaceholder() {
        val path = "/computer/uploads/archive with spaces.zip"
        val result = appendFileAttachmentsToDraft("Read this file", listOf(CodexFileAttachment(
            fileName = "archive.zip", mimeType = "application/zip", sizeBytes = 50 * 1024 * 1024,
            textContent = "old truncated preview", remotePath = path,
        )), "binary placeholder")
        assertEquals("Read this file\n\n[archive.zip](/computer/uploads/archive%20with%20spaces.zip)", result)
        assertTrue(result.startsWith("Read this file"))
        assertFalse(result.contains("old truncated preview"))
        assertFalse(result.contains("binary placeholder"))
    }

    @Test fun oldAndNewUploadsDisplayTheSameLinksAndResolveTheirOriginalPaths() {
        val root = "/Volumes/My Disk/remodex_uploads/12345678-1234-4234-8234-123456789abc/"
        val names = listOf("聊天记录_1.zip", "[(a)]+100%#3?.txt")
        val files = names.map { CodexFileAttachment(fileName = it, mimeType = "application/octet-stream",
            sizeBytes = 12, textContent = null, remotePath = root + it) }
        val original = "Look at these\n\nAttached files:\n" + names.mapIndexed { index, name ->
            "${index + 1}. $name (12B, application/octet-stream)\nComplete file uploaded to computer: \"$root$name\""
        }.joinToString("\n")
        val sent = appendFileAttachmentsToDraft("Look at these", files, "binary")
        assertEquals(sent, displayUploadedFiles(original))
        val links = sent.split("\n\n").drop(1)
        assertEquals(names.map { root + it }, links.map { RepoMarkdownFileLink.previewPath(it.substringAfterLast("](").dropLast(1)) })
        assertEquals(original + "\nadditional prose", displayUploadedFiles(original + "\nadditional prose"))
        assertEquals("unchanged", displayUploadedFiles("unchanged"))
    }
}
