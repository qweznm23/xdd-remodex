package com.remodex.mobile.core.model

data class ThreadHistoryPaginationState(
    val olderCursor: JSONValue? = null,
    val exhaustedOlderCursor: JSONValue? = null,
    val hasAuthoritativeLocalHistoryStart: Boolean = false,
    val initialTurnsLoaded: Boolean = false,
    val initialLoadRevision: Long = 0,
    val loadedActivityRevision: String? = null,

    val olderPageAnchorTurnId: String? = null,
    val usesItemPagination: Boolean = false,
    val olderPageAnchorMessageId: String? = null,
    val lastPageItemKeys: Set<Pair<String, String>> = emptySet(),
) {
    val canLoadOlder: Boolean
        get() = olderCursor != null && olderCursor != JSONValue.Null && !hasAuthoritativeLocalHistoryStart
}
