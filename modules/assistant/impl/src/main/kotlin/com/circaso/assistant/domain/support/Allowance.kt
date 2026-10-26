package com.circaso.assistant.domain.support

import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/** Seconds until the daily allowance resets at midnight UTC: the `retryAfterSeconds` of `assistant.quota_exceeded`. */
fun Clock.secondsUntilReset(): Long {
    val now = instant().atZone(ZoneOffset.UTC)
    val midnight =
        now
            .toLocalDate()
            .plusDays(1)
            .atStartOfDay(ZoneOffset.UTC)
    return Duration.between(now, midnight).seconds.coerceAtLeast(1)
}
