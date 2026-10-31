package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.ExchangeRates
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.HISTORY_STATES
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.support.Counterparties
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** The most rows one page answers (Conventions: `limit` at most 200). */
private const val MAX_LIMIT = 200

/**
 * W6 `GET /wallet/transactions`, and Asha's narrower read of it: the caller's own history rows, newest first, paged
 * by keyset. The other side of each row is named from users' `Profiles` ([Counterparties]). With a currency (Asha's
 * route), today's rate comes along so each row can be shown in it; no rate known is never an error.
 */
class ListTransactions(
    private val transactions: TransactionRepository,
    private val counterparties: Counterparties,
    private val rates: ExchangeRates,
) {
    suspend operator fun invoke(query: HistoryQuery): Resource<HistoryPage> {
        val page = query.limit.coerceIn(1, MAX_LIMIT)
        val from = latest(query.month?.let(::startOf), query.from)
        val until = earliest(query.month?.plusMonths(1)?.let(::startOf), query.until)
        if (from != null && until != null && !from.isBefore(until)) {
            return Resource.success(HistoryPage(emptyList(), null))
        }

        val rows =
            transactions.history(query.userId, HISTORY_STATES, from, until, query.after, page + 1, query.filter)
        val shown = rows.take(page)
        val entries = counterparties.entries(shown)
        val next = if (rows.size > page) shown.last().let { HistoryPosition(it.occurredAt, it.id) } else null
        val rate = query.currency?.let { rates.usdRate(it) }
        return Resource.success(HistoryPage(entries, next, rate))
    }

    private fun startOf(month: YearMonth): Instant = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)

    private fun latest(a: Instant?, b: Instant?): Instant? = if (a == null || (b != null && b.isAfter(a))) b else a

    private fun earliest(a: Instant?, b: Instant?): Instant? = if (a == null || (b != null && b.isBefore(a))) b else a
}
