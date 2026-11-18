package com.remodex.mobile.ui.turn

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnTimelineFollowBottomTest {
    @Test
    fun followsWhenLastVisibleIsNearBottom() {
        assertTrue(
            shouldFollowTimelineBottom(
                totalItemsCount = 20,
                lastVisibleItemIndex = 18,
                thresholdItems = 2,
            ),
        )
    }

    @Test
    fun doesNotFollowWhenUserHasScrolledUp() {
        assertFalse(
            shouldFollowTimelineBottom(
                totalItemsCount = 40,
                lastVisibleItemIndex = 20,
                thresholdItems = 2,
            ),
        )
    }

    @Test
    fun missingLayoutDoesNotForceFollowForExistingItems() {
        assertFalse(shouldFollowTimelineBottom(totalItemsCount = 12, lastVisibleItemIndex = null))
    }

    @Test
    fun emptyTimelineCanFollow() {
        assertTrue(shouldFollowTimelineBottom(totalItemsCount = 0, lastVisibleItemIndex = null))
    }

    @Test
    fun releasingAtBottomOfTallReplyDoesNotRealignItsStart() {
        // The final row starts above the viewport. scrollToItem(last) would jump backwards.
        assertNull(target(canScrollForward = false, offset = -1100))
        assertNull(target(offset = -1100))
    }

    @Test
    fun growingVisibleReplyScrollsOnlyByItsOverflow() {
        assertEquals(TimelineBottomScroll.ByPixels(80), target(offset = -1100, size = 1580))
    }

    @Test
    fun followWaitsForManualDragAndFlingAndRespectsReadingHistory() {
        assertNull(target(dragging = true))
        assertNull(target(scrolling = true))
        assertNull(target(following = false))
    }

    @Test
    fun newRowsOutsideViewportAreBroughtIntoViewBeforeMeasuringBottom() {
        assertEquals(TimelineBottomScroll.ToItem(5), target(total = 6, last = 3))
    }

    @Test
    fun composerInsetGrowthAdvancesOnlyByAddedInset() {
        assertEquals(TimelineBottomScroll.ByPixels(60), target(offset = -1100, padding = 260))
    }

    @Test
    fun emptyUnmeasuredAndShortListsDoNotIssueScrollRequests() {
        assertNull(target(total = 0, last = null))
        assertNull(target(last = null))
        assertNull(target(size = 100))
        assertNull(target(canScrollForward = false, size = 1600))
    }

    private fun target(
        following: Boolean = true,
        dragging: Boolean = false,
        scrolling: Boolean = false,
        canScrollForward: Boolean = true,
        total: Int = 1,
        last: Int? = 0,
        offset: Int = 0,
        size: Int = 1500,
        padding: Int = 200,
    ) = timelineBottomScroll(
        following, dragging, scrolling, canScrollForward,
        total, last, offset, size, 600, padding,
    )
}
