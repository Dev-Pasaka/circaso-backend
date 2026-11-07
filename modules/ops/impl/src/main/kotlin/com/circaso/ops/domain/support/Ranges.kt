package com.circaso.ops.domain.support

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** UTC days as instants: a range of whole days is `[from 00:00, to + 1 day 00:00)`. */
object Ranges {
    fun startOf(day: LocalDate): Instant = day.atStartOfDay().toInstant(ZoneOffset.UTC)

    fun endOf(day: LocalDate): Instant = startOf(day.plusDays(1))

    fun today(now: Instant): LocalDate = LocalDate.ofInstant(now, ZoneOffset.UTC)
}
