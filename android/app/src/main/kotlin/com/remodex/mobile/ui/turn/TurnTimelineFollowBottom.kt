package com.remodex.mobile.ui.turn

internal fun shouldFollowTimelineBottom(
    totalItemsCount: Int,
    lastVisibleItemIndex: Int?,
    thresholdItems: Int = 2,
): Boolean {
    if (totalItemsCount <= 0) return true
    val lastVisible = lastVisibleItemIndex ?: return false
    val threshold = thresholdItems.coerceAtLeast(0)
    return lastVisible >= totalItemsCount - 1 - threshold
}

/** Pixel distance, including the composer inset; message indices cannot measure a tall row. */
internal fun timelineBottomOverflow(
    totalItemsCount: Int,
    lastVisibleIndex: Int?,
    lastOffset: Int,
    lastSize: Int,
    viewportEnd: Int,
    afterPadding: Int,
): Int? {
    if (totalItemsCount == 0) return 0
    if (lastVisibleIndex != totalItemsCount - 1) return null
    return lastOffset + lastSize + afterPadding - viewportEnd
}

internal sealed interface TimelineBottomScroll {
    data class ByPixels(val pixels: Int) : TimelineBottomScroll
    data class ToItem(val index: Int) : TimelineBottomScroll
}

/** Only advance toward the bottom; resuming follow after a gesture must not move a settled list. */
internal fun timelineBottomScroll(
    following: Boolean,
    dragging: Boolean,
    scrolling: Boolean,
    canScrollForward: Boolean,
    totalItemsCount: Int,
    lastVisibleIndex: Int?,
    lastOffset: Int,
    lastSize: Int,
    viewportEnd: Int,
    afterPadding: Int,
): TimelineBottomScroll? {
    if (!following || dragging || scrolling || !canScrollForward || totalItemsCount <= 0 || lastVisibleIndex == null) return null
    val overflow = timelineBottomOverflow(
        totalItemsCount, lastVisibleIndex, lastOffset, lastSize, viewportEnd, afterPadding,
    ) ?: return TimelineBottomScroll.ToItem(totalItemsCount - 1)
    return if (overflow > 0) TimelineBottomScroll.ByPixels(overflow) else null
}
