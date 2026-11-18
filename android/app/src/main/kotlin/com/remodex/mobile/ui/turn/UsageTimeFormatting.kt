package com.remodex.mobile.ui.turn

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Round up so a future reset never reads as already due. */
internal fun remainingQuotaResetMinutes(resetsAt: Instant, now: Instant): Long {
    val remaining = Duration.between(now, resetsAt)
    if (remaining.isNegative || remaining.isZero) return 0
    return remaining.seconds / 60 + if (remaining.seconds % 60 != 0L || remaining.nano != 0) 1 else 0
}

internal fun formatUsageCalendarTime(
    time: Instant,
    now: Instant,
    zone: ZoneId = ZoneId.systemDefault(),
    locale: Locale = Locale.getDefault(),
): String {
    val local = time.atZone(zone)
    val today = now.atZone(zone).toLocalDate()
    val chinese = locale.language == "zh"
    val clock = DateTimeFormatter.ofPattern("HH:mm", locale).format(local)
    return when (local.toLocalDate()) {
        today -> (if (chinese) "今天 " else "Today ") + clock
        today.plusDays(1) -> (if (chinese) "明天 " else "Tomorrow ") + clock
        today.minusDays(1) -> (if (chinese) "昨天 " else "Yesterday ") + clock
        else -> DateTimeFormatter.ofPattern(
            if (chinese) {
                if (local.year == today.year) "M月d日 E HH:mm" else "yyyy年M月d日 E HH:mm"
            } else {
                if (local.year == today.year) "EEE, MMM d · HH:mm" else "EEE, MMM d, yyyy · HH:mm"
            }, locale).format(local)
    }
}
