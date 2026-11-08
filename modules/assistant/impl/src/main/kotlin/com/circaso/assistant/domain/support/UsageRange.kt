package com.circaso.assistant.domain.support

import com.circaso.shared.DomainException
import com.circaso.shared.SharedError
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** The staff cost report's range rule (decision 2026-11-08): `[from, until)` in UTC days, 1 to 366 of them. */
internal object UsageRange {
    const val MAX_DAYS = 366L

    /** Thrown, since the contract returns the report itself (the ops gateway's StatusPages answers it as usual). */
    fun check(from: LocalDate, until: LocalDate) {
        val days = ChronoUnit.DAYS.between(from, until)
        if (days < 1 || days > MAX_DAYS) {
            throw DomainException(SharedError.Malformed("to", "must be after from, at most $MAX_DAYS days"))
        }
    }
}
