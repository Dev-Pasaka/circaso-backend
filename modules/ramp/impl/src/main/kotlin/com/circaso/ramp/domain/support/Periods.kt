package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.models.Period
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Days as the ramp counts them. The daily limit's day is UTC. A merchant's periods (the earnings overview, the
 * statement, a sweep's day) are calendar days in Nairobi (East Africa Time, UTC+3, no daylight saving): the merchants
 * are Kenyan, and the daily sweep runs at their midnight.
 */
object Periods {
    /** The longest period one statement or overview may cover. */
    const val MAX_DAYS = 366L

    /** East Africa Time. */
    val MERCHANT_ZONE: ZoneOffset = ZoneOffset.ofHours(3)

    /** The UTC day of [at]: the daily trading limit's day. */
    fun day(at: Instant): LocalDate = at.atZone(ZoneOffset.UTC).toLocalDate()

    /** The Nairobi day of [at]. */
    fun merchantDay(at: Instant): LocalDate = at.atOffset(MERCHANT_ZONE).toLocalDate()

    /** The Nairobi calendar month [now] is in. */
    fun thisMonth(now: Instant): Period {
        val today = merchantDay(now)
        return Period(today.withDayOfMonth(1), today.withDayOfMonth(today.lengthOfMonth()))
    }

    /** The first instant of [period]. */
    fun start(period: Period): Instant = period.from.atStartOfDay().toInstant(MERCHANT_ZONE)

    /** The first instant after [period]. */
    fun end(period: Period): Instant =
        period.to
            .plusDays(1)
            .atStartOfDay()
            .toInstant(MERCHANT_ZONE)

    /** The latest time the daily sweep fell due: today at [hourUtc], or yesterday at it when today's hasn't come. */
    fun lastSweepDue(now: Instant, hourUtc: Int): Instant {
        val today =
            day(now)
                .atStartOfDay(ZoneOffset.UTC)
                .plusHours(hourUtc.toLong())
                .toInstant()
        return if (today.isAfter(now)) today.minusSeconds(SECONDS_A_DAY) else today
    }

    /** The Nairobi day a sweep that fell due at [due] closed: the day that ended as it fell due. */
    fun sweepDay(due: Instant): LocalDate = merchantDay(due.minusSeconds(1))

    private const val SECONDS_A_DAY = 86_400L
}
