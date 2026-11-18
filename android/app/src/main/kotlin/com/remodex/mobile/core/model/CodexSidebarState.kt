package com.remodex.mobile.core.model

/** Server-owned read receipt. Revisions are opaque and cannot be ordered or synthesized. */
data class CodexThreadReadState(val revision: String, val unread: Boolean) {
    companion object {
        fun decode(value: JSONValue?): CodexThreadReadState? {
            if (value == null || value == JSONValue.Null) return null
            val row = value.objectValue ?: error("Invalid Codex read state")
            val revision = row["revision"]?.stringValue?.takeIf { it.isNotBlank() }
                ?: error("Codex read state missing revision")
            require(row.containsKey("firstUnread")) { "Codex read state missing firstUnread" }
            val first = row["firstUnread"]
            require(first == JSONValue.Null || first?.objectValue != null) { "Invalid Codex unread position" }
            if (first != JSONValue.Null) {
                val position = first!!.objectValue!!
                when (position["type"]?.stringValue) {
                    "threadStart" -> Unit
                    "turn" -> require(!position["turnId"]?.stringValue.isNullOrBlank()) { "Codex unread turn missing id" }
                    else -> error("Unknown Codex unread position type")
                }
            }
            return CodexThreadReadState(revision, first != JSONValue.Null)
        }
    }
}

data class CodexSidebarState(
    val deviceId: String? = null,
    val initialized: Boolean = false,
    val threads: Map<String, CodexThreadReadState> = emptyMap(),
    val recentThreadIds: List<String> = emptyList(),
    val pinnedThreadIds: List<String> = emptyList(),
    val pinnedSectionId: String? = null,
)
