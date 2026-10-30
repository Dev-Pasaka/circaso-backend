package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.users.api.Profiles
import com.circaso.wallet.domain.models.Counterparty
import com.circaso.wallet.domain.models.HISTORY_STATES
import com.circaso.wallet.domain.models.HistoryEntry
import com.circaso.wallet.domain.models.HistoryPage
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.HistoryQuery
import com.circaso.wallet.domain.repositories.TransactionRepository
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneOffset

/** The most rows one page answers (Conventions: `limit` at most 200). */
private const val MAX_LIMIT = 200

/**
 * W6 `GET /wallet/transactions`: the caller's own history rows, newest first, paged by keyset. The other side of each
 * row is named from users' `Profiles`, asked once per page for every person on it; someone whose profile is gone
 * (a deleted account) keeps their id and address but no name.
 */
class ListTransactions(private val transactions: TransactionRepository, private val profiles: Profiles) {
    suspend operator fun invoke(query: HistoryQuery): Resource<HistoryPage> {
        val page = query.limit.coerceIn(1, MAX_LIMIT)
        val from = query.month?.let(::startOf)
        val until = query.month?.plusMonths(1)?.let(::startOf)
        val rows = transactions.history(query.userId, HISTORY_STATES, from, until, query.after, page + 1)
        val shown = rows.take(page)
        val people = shown.mapNotNullTo(mutableSetOf()) { it.counterpartyId }
        val named = if (people.isEmpty()) emptyMap() else profiles.get(people)
        val entries =
            shown.map { row ->
                val profile = row.counterpartyId?.let { named[it] }
                val counterparty =
                    Counterparty(
                        userId = row.counterpartyId,
                        displayName = profile?.displayName,
                        handle = profile?.handle,
                        avatarUrl = profile?.avatarUrl,
                        address = row.counterpartyAddress,
                    )
                HistoryEntry(row, counterparty)
            }
        val next = if (rows.size > page) shown.last().let { HistoryPosition(it.occurredAt, it.id) } else null
        return Resource.success(HistoryPage(entries, next))
    }

    private fun startOf(month: YearMonth): Instant = month.atDay(1).atStartOfDay().toInstant(ZoneOffset.UTC)
}
