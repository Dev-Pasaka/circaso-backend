package com.circaso.identity.domain.usecases

import com.circaso.identity.api.DayCount
import com.circaso.identity.api.SignUpStats
import com.circaso.identity.domain.repositories.AccountAdminRepository
import com.circaso.shared.DomainException
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * Staff: people who signed up, all time and per UTC day between [from] and [to] (both included), with every day of the
 * range in the series, zeros too, so a chart never has to guess a missing day (decision 2026-11-08). Counted are
 * person accounts that finished sign-up (not `PENDING_PHONE`) and aren't deleted, by when they started it.
 *
 * At most [MAX_DAYS] days: the grouping reads every sign-up in the range, so the range is what bounds it. A wider or
 * reversed range is `request.malformed`, thrown because the contract returns the stats themselves; the ops gateway's
 * StatusPages answers it in the usual envelope.
 */
class CountSignUps(private val admin: AccountAdminRepository) {
    suspend operator fun invoke(from: LocalDate, to: LocalDate): Resource<SignUpStats> {
        if (to < from || ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw DomainException(SharedError.Malformed("to", "must be on or after from, at most $MAX_DAYS days"))
        }
        val byDay =
            admin.signUpsByDay(
                from.atStartOfDay().toInstant(ZoneOffset.UTC),
                to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC),
            )
        val series =
            generateSequence(from) { it.plusDays(1) }
                .takeWhile { it <= to }
                .map { DayCount(it, byDay[it] ?: 0) }
                .toList()
        return Resource.success(SignUpStats(admin.signUpTotal(), series.sumOf { it.count }, series))
    }

    private companion object {
        const val MAX_DAYS = 366L
    }
}
