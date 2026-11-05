package com.circaso.spaces.domain.support

import com.circaso.spaces.domain.models.Period
import java.time.Instant
import java.time.ZoneOffset

/** Period arithmetic in UTC: a month or a year from a moment, nothing for a one-time membership. */
object Periods {
    fun end(start: Instant, period: Period): Instant? =
        when (period) {
            Period.ONE_TIME -> null
            Period.MONTHLY -> start.atOffset(ZoneOffset.UTC).plusMonths(1).toInstant()
            Period.YEARLY -> start.atOffset(ZoneOffset.UTC).plusYears(1).toInstant()
        }
}
