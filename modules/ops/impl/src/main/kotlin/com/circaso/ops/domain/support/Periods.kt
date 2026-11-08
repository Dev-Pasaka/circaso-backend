package com.circaso.ops.domain.support

import com.circaso.ops.domain.models.UsageBucket
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** UTC periods of a day range, as PostgreSQL's `date_trunc` cuts them (ISO weeks start on Monday). */
object Periods {
    fun periodOf(day: LocalDate, bucket: UsageBucket): LocalDate =
        when (bucket) {
            UsageBucket.DAY -> day
            UsageBucket.WEEK -> day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            UsageBucket.MONTH -> day.withDayOfMonth(1)
        }

    /** Every period that overlaps `[from, until)`, first to last, so a chart has every period, empty ones too. */
    fun of(
        from: LocalDate,
        until: LocalDate,
        bucket: UsageBucket,
    ): List<LocalDate> =
        generateSequence(periodOf(from, bucket)) { next(it, bucket) }.takeWhile { it.isBefore(until) }.toList()

    private fun next(period: LocalDate, bucket: UsageBucket): LocalDate =
        when (bucket) {
            UsageBucket.DAY -> period.plusDays(1)
            UsageBucket.WEEK -> period.plusWeeks(1)
            UsageBucket.MONTH -> period.plusMonths(1)
        }
}
