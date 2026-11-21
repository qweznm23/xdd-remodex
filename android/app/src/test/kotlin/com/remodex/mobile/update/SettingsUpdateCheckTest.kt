package com.remodex.mobile.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsUpdateCheckTest {
    private val feed = "https://example.com/update.json"

    @Test fun automaticChecksShareManualCooldownOnlyForTheSameSource() {
        val latest = AppUpdateState(UpdatePhase.LATEST)
        assertFalse(latest.shouldCheckOnSettingsEntry(feed, feed, 299_999))
        assertTrue(latest.shouldCheckOnSettingsEntry(feed, feed, 300_000))
        assertTrue(latest.shouldCheckOnSettingsEntry(feed, null, 0))
        assertTrue(latest.shouldCheckOnSettingsEntry(feed, "https://other.example/feed", 1))
        assertFalse(latest.shouldCheckOnSettingsEntry("", feed, 600_000))
    }

    @Test fun navigationPreservesTransferAndInstallationStates() {
        for (phase in listOf(UpdatePhase.CHECKING, UpdatePhase.CONNECTING, UpdatePhase.DOWNLOADING,
            UpdatePhase.CANCELLING, UpdatePhase.VERIFYING, UpdatePhase.READY)) {
            assertFalse(phase.name, AppUpdateState(phase).shouldCheckOnSettingsEntry(feed, null, 600_000))
        }
        for (retry in UpdateRetry.entries) {
            val state = AppUpdateState(UpdatePhase.ERROR, retry = retry)
            assertFalse(state.shouldCheckOnSettingsEntry(feed, feed, 1))
            org.junit.Assert.assertEquals(retry == UpdateRetry.CHECK, state.shouldCheckOnSettingsEntry(feed, feed, 300_000))
        }
    }
}
