package com.remodex.mobile.ui.turn

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TurnSmartScrollNavigationTest {
    @Test
    fun readingHistoryShowsTheButtonEvenWithoutUserMessageAnchors() {
        assertTrue(showJumpToLatest(ready = true, following = false, canScrollForward = true))
    }

    @Test
    fun hideAtTheEndOrWhenAllContentFits() {
        assertFalse(showJumpToLatest(ready = true, following = false, canScrollForward = false))
    }

    @Test
    fun hideDuringInitialPositioningAndWhileFollowingStreamingContent() {
        assertFalse(showJumpToLatest(ready = false, following = false, canScrollForward = true))
        assertFalse(showJumpToLatest(ready = true, following = true, canScrollForward = true))
    }
}
