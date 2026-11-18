package com.remodex.mobile.ui.turn

import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class UsageTimeFormattingTest {
    private val now = Instant.parse("2026-11-13T10:00:00Z")
    private val zone = ZoneId.of("Asia/Shanghai")

    @Test fun countdownRoundsUpAndDoesNotClaimExpiredDataWasReset() {
        assertEquals(1L, remainingQuotaResetMinutes(now.plusNanos(1), now))
        assertEquals(1L, remainingQuotaResetMinutes(now.plusSeconds(60), now))
        assertEquals(2L, remainingQuotaResetMinutes(now.plusSeconds(61), now))
        assertEquals(0L, remainingQuotaResetMinutes(now, now))
        assertEquals(0L, remainingQuotaResetMinutes(now.minusSeconds(60), now))
        assertEquals(3060L, remainingQuotaResetMinutes(now.plusSeconds(51 * 3600), now))
    }

    @Test fun chineseCalendarUsesPhoneLocalDaysAndReadableDates() {
        assertEquals("今天 19:00", formatUsageCalendarTime(now.plusSeconds(3600), now, zone, Locale.SIMPLIFIED_CHINESE))
        assertEquals("明天 00:30", formatUsageCalendarTime(Instant.parse("2026-11-13T16:30:00Z"), now, zone, Locale.SIMPLIFIED_CHINESE))
        assertEquals("11月18日 周三 08:00", formatUsageCalendarTime(Instant.parse("2026-11-18T00:00:00Z"), now, zone, Locale.SIMPLIFIED_CHINESE))
        assertEquals("2027年1月2日 周六 08:00", formatUsageCalendarTime(Instant.parse("2027-01-02T00:00:00Z"), now, zone, Locale.SIMPLIFIED_CHINESE))
    }

    @Test fun calendarAndCountdownHandleDstIndependently() {
        val before = Instant.parse("2026-03-08T06:30:00Z")
        val after = Instant.parse("2026-03-08T07:30:00Z")
        assertEquals(60L, remainingQuotaResetMinutes(after, before))
        assertEquals("Today 03:30", formatUsageCalendarTime(after, before, ZoneId.of("America/New_York"), Locale.US))
    }
}
