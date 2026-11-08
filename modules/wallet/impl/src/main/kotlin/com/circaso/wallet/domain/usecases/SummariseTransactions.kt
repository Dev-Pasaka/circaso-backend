package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.ExchangeRates
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.AssetFlow
import com.circaso.wallet.domain.models.CounterpartyFlow
import com.circaso.wallet.domain.models.Direction
import com.circaso.wallet.domain.models.HistoryPosition
import com.circaso.wallet.domain.models.MonthFlow
import com.circaso.wallet.domain.models.Party
import com.circaso.wallet.domain.models.SUMMARY_STATES
import com.circaso.wallet.domain.models.SummaryQuery
import com.circaso.wallet.domain.models.TransactionSummary
import com.circaso.wallet.domain.models.WalletTransaction
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.support.Counterparties
import java.time.YearMonth
import java.time.ZoneOffset

/**
 * Asha's totals over the caller's own history (decision 2026-10-31): in and out per asset, the biggest counterparties
 * and month by month. It walks the matching rows by keyset, at most [MAX_ROWS] of them, and says when there were
 * more, so an answer over a very long range is never silently short. Amounts stay in each asset's minor units; a
 * currency adds today's rate, never a past one.
 */
class SummariseTransactions(
    private val transactions: TransactionRepository,
    private val counterparties: Counterparties,
    private val rates: ExchangeRates,
) {
    suspend operator fun invoke(query: SummaryQuery): Resource<TransactionSummary> {
        val (rows, truncated) = read(query)
        val top = topCounterparties(rows, query.topCounterparties.coerceIn(0, MAX_TOP))
        return Resource.success(
            TransactionSummary(
                from = query.from,
                until = query.until,
                count = rows.size,
                assets = assets(rows),
                topCounterparties = top,
                months = months(rows),
                rate = query.currency?.let { rates.usdRate(it) },
                truncated = truncated,
            ),
        )
    }

    private suspend fun read(query: SummaryQuery): Pair<List<WalletTransaction>, Boolean> {
        val rows = mutableListOf<WalletTransaction>()
        var after: HistoryPosition? = null
        while (rows.size < MAX_ROWS) {
            val page =
                transactions.history(
                    query.userId,
                    SUMMARY_STATES,
                    query.from,
                    query.until,
                    after,
                    PAGE,
                    query.filter,
                )
            rows += page
            if (page.size < PAGE) return rows to false
            after = page.last().let { HistoryPosition(it.occurredAt, it.id) }
        }
        val more =
            transactions
                .history(query.userId, SUMMARY_STATES, query.from, query.until, after, 1, query.filter)
                .isNotEmpty()
        return rows.take(MAX_ROWS) to more
    }

    private fun assets(rows: List<WalletTransaction>): List<AssetFlow> =
        rows.groupBy { it.amount.asset }.map { (asset, of) ->
            val incoming = of.filter { it.direction == Direction.IN }
            val outgoing = of.filter { it.direction == Direction.OUT }
            AssetFlow(
                asset = asset,
                inMinor = incoming.sumOf { it.amount.amountMinor },
                outMinor = outgoing.sumOf { it.amount.amountMinor },
                inCount = incoming.size,
                outCount = outgoing.size,
            )
        }

    /** Grouped by the person, or by the address when the other side isn't on Circaso; biggest total moved first. */
    private suspend fun topCounterparties(rows: List<WalletTransaction>, limit: Int): List<CounterpartyFlow> {
        if (limit == 0) return emptyList()
        val groups =
            rows
                .groupBy { it.party() }
                .entries
                .sortedByDescending { (_, of) -> of.sumOf { it.amount.amountMinor } }
                .take(limit)
        val named = counterparties.names(groups.mapNotNullTo(mutableSetOf()) { it.key.userId })
        return groups.map { (party, of) ->
            CounterpartyFlow(
                counterparty = counterparties.counterparty(party.userId, party.address, named),
                asset = party.asset,
                inMinor = of.filter { it.direction == Direction.IN }.sumOf { it.amount.amountMinor },
                outMinor = of.filter { it.direction == Direction.OUT }.sumOf { it.amount.amountMinor },
                count = of.size,
            )
        }
    }

    private fun months(rows: List<WalletTransaction>): List<MonthFlow> =
        rows
            .groupBy { YearMonth.from(it.occurredAt.atZone(ZoneOffset.UTC)) to it.amount.asset }
            .map { (key, of) ->
                MonthFlow(
                    month = key.first,
                    asset = key.second,
                    inMinor = of.filter { it.direction == Direction.IN }.sumOf { it.amount.amountMinor },
                    outMinor = of.filter { it.direction == Direction.OUT }.sumOf { it.amount.amountMinor },
                    count = of.size,
                )
            }.sortedWith(compareBy<MonthFlow> { it.month }.thenBy { it.asset.symbol })

    /** A person is one party whatever address they paid from; an outside address is a party of its own. */
    private fun WalletTransaction.party() =
        Party(counterpartyId, if (counterpartyId == null) counterpartyAddress else null, amount.asset)

    private companion object {
        /** The most rows one summary reads: years of an ordinary person's payments. */
        const val MAX_ROWS = 5_000
        const val PAGE = 500
        const val MAX_TOP = 20
    }
}
