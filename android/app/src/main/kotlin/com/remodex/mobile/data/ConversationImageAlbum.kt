package com.remodex.mobile.data

import com.mikepenz.markdown.utils.getUnescapedTextInNode
import com.remodex.mobile.core.model.CodexImageAttachment
import com.remodex.mobile.core.model.CodexMessage
import com.remodex.mobile.core.model.CodexMessageKind
import com.remodex.mobile.core.model.CodexMessageRole
import com.remodex.mobile.core.model.ConversationMediaType
import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.parser.MarkdownParser

internal data class MarkdownAlbum(val body: String, val images: List<CodexImageAttachment>)
internal data class PreparedImageAlbum(val message: CodexMessage, val explicitOrder: List<String> = emptyList())
private val numberedImageName = Regex("^(.*?)([0-9]+)(\\.[A-Za-z0-9]+)$")

internal fun albumImageKey(image: CodexImageAttachment): String =
    image.sourceURL?.takeIf { it.isNotBlank() } ?: image.payloadDataURL ?: image.id

/** Only infer numeric order for one recognisable filename series, never UUIDs or unrelated files. */
internal fun orderedAlbumImages(images: List<CodexImageAttachment>, explicitOrder: List<String>): List<CodexImageAttachment> {
    val unique = images.distinctBy(::albumImageKey)
    if (explicitOrder.size > 1) {
        val ranks = explicitOrder.distinct().withIndex().associate { it.value to it.index }
        val mentioned = unique.filter { albumImageKey(it) in ranks }.sortedBy { ranks.getValue(albumImageKey(it)) }.iterator()
        // A caption may repeat just one photo. Only reorder the mentioned slots;
        // do not pull that photo ahead of the rest of the response.
        return unique.map { if (albumImageKey(it) in ranks) mentioned.next() else it }
    }
    if (unique.size < 2) return unique
    val numbered = unique.map { image ->
        val source = image.sourceURL ?: return unique
        val match = numberedImageName.matchEntire(source.substringBefore('?').substringBefore('#'))
            ?: return unique
        Triple(match.groupValues[1] + "|" + match.groupValues[3], match.groupValues[2].trimStart('0').ifEmpty { "0" }, image)
    }
    if (numbered.map { it.first }.distinct().size != 1) return unique
    return numbered.sortedWith(compareBy<Triple<String, String, CodexImageAttachment>> { it.second.length }
        .thenBy { it.second }.thenBy { albumImageKey(it.third) })
        .map { it.third }
}

private fun ASTNode.descendant(type: org.intellij.markdown.IElementType): ASTNode? =
    if (this.type == type) this else children.firstNotNullOfOrNull { it.descendant(type) }

/** Parse real Markdown nodes so code samples, ordinary links and video cards remain intact. */
internal fun markdownImageAlbum(markdown: String): MarkdownAlbum {
    if (!markdown.contains("![")) return MarkdownAlbum(markdown, emptyList())
    val tree = MarkdownParser(GFMFlavourDescriptor()).buildMarkdownTreeFromString(markdown)
    val images = linkedMapOf<String, CodexImageAttachment>()
    val removed = mutableListOf<IntRange>()
    fun add(image: ASTNode, range: ASTNode): Boolean {
        val source = image.descendant(MarkdownElementTypes.LINK_DESTINATION)?.getUnescapedTextInNode(markdown) ?: return false
        if (ConversationMediaType.isVideo(source)) return false
        images.putIfAbsent(source, CodexImageAttachment(id = source, thumbnailBase64JPEG = "", sourceURL = source))
        removed += range.startOffset until range.endOffset
        return true
    }
    fun visit(node: ASTNode) {
        if (node.type == MarkdownElementTypes.IMAGE) {
            add(node, node)
            return
        }
        if (node.type == MarkdownElementTypes.INLINE_LINK) {
            // [![thumbnail](image.png)](target) is one image, not an empty leftover link.
            val label = node.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            val image = label?.descendant(MarkdownElementTypes.IMAGE)
            if (label != null && image != null &&
                markdown.substring(label.startOffset, label.endOffset).removeSurrounding("[", "]").trim() ==
                markdown.substring(image.startOffset, image.endOffset) && add(image, node)
            ) return
        }
        node.children.forEach(::visit)
    }
    visit(tree)
    if (removed.isEmpty()) return MarkdownAlbum(markdown, emptyList())
    val body = buildString {
        var end = 0
        removed.forEach { range -> append(markdown, end, range.first); end = range.last + 1 }
        append(markdown, end, markdown.length)
    }
    return MarkdownAlbum(body, images.values.toList())
}

/** Render-only copy; history, clipboard and persisted messages retain their original Markdown. */
internal fun prepareConversationImageAlbum(message: CodexMessage): CodexMessage = prepareImageAlbum(message).message

internal fun prepareImageAlbum(message: CodexMessage): PreparedImageAlbum {
    if (message.kind != CodexMessageKind.chat || message.role == CodexMessageRole.system) return PreparedImageAlbum(message)
    val album = markdownImageAlbum(message.text)
    if (album.images.isEmpty()) return PreparedImageAlbum(message)
    val attachments = message.attachments.toMutableList()
    val sources = attachments.mapNotNullTo(hashSetOf()) { it.sourceURL ?: it.payloadDataURL }
    album.images.forEach { image -> if (sources.add(image.sourceURL!!)) attachments += image }
    val explicitOrder = if (message.role == CodexMessageRole.assistant) album.images.map(::albumImageKey) else emptyList()
    val ordered = if (message.role == CodexMessageRole.assistant) orderedAlbumImages(attachments, explicitOrder) else attachments
    return PreparedImageAlbum(message.copy(text = album.body, attachments = ordered), explicitOrder)
}

/** Generated images are separate Codex items. Keep one album within a response boundary. */
internal fun groupResponseImageAlbums(
    messages: List<CodexMessage>,
    explicitOrders: Map<String, List<String>> = emptyMap(),
): List<CodexMessage> {
    val result = mutableListOf<CodexMessage>()
    var albumIndex: Int? = null
    var albumTurn: String? = null
    var thread: String? = null
    var previousWasUnscopedImage = false
    var explicitOrder = emptyList<String>()
    fun finishAlbum() {
        val index = albumIndex ?: return
        val first = result[index]
        val photos = first.attachments.filter { ConversationMediaType.videoSource(it) == null }
        val ordered = orderedAlbumImages(photos, explicitOrder).iterator()
        // Keep video slots and all message identities intact.
        result[index] = first.copy(attachments = first.attachments.mapNotNull {
            if (ConversationMediaType.videoSource(it) == null) {
                if (ordered.hasNext()) ordered.next() else null
            } else it
        })
    }
    messages.forEach { message ->
        val turn = message.turnId?.trim()?.takeIf { it.isNotEmpty() }
        if (thread != message.threadId || message.role == CodexMessageRole.user ||
            (albumIndex != null && turn != null && albumTurn != turn)
        ) {
            finishAlbum()
            albumIndex = null
            albumTurn = null
            explicitOrder = emptyList()
        }
        thread = message.threadId
        val photos = if (message.role == CodexMessageRole.assistant && message.kind == CodexMessageKind.chat) {
            message.attachments.filter { ConversationMediaType.videoSource(it) == null }
        } else emptyList()
        val unscopedImage = turn == null && photos.isNotEmpty() && message.text.isBlank()
        if (photos.isEmpty()) {
            result += message
        } else {
            val index = albumIndex
            val sameResponse = index != null && ((turn != null && turn == albumTurn) ||
                (turn == null && albumTurn == null && previousWasUnscopedImage && unscopedImage))
            if (sameResponse) {
                explicitOrder = (explicitOrder + explicitOrders[message.id].orEmpty()).distinct()
                val first = result[index!!]
                val combined = (first.attachments + photos).distinctBy(::albumImageKey)
                result[index] = first.copy(attachments = combined)
                val remainder = message.copy(attachments = message.attachments.filter { ConversationMediaType.videoSource(it) != null })
                if (remainder.text.isNotBlank() || remainder.attachments.isNotEmpty()) result += remainder
            } else {
                finishAlbum()
                albumIndex = result.size
                albumTurn = turn
                explicitOrder = explicitOrders[message.id].orEmpty()
                result += message
            }
        }
        previousWasUnscopedImage = unscopedImage
    }
    finishAlbum()
    return result
}
