package com.circaso.wallet.domain.models

import com.circaso.integrations.api.UsdRate
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.UserId
import com.circaso.wallet.api.WalletId
import java.math.BigInteger
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/**
 * W6's question: [userId]'s history, newest first, after [after] (the last row of the previous page), at most
 * [limit]. With [month], only that calendar month in UTC; without it, every month, for the wallet tab's recent list.
 *
 * Asha's agent route asks more narrowly (decision 2026-10-31): [from] and [until] bound the time (with [month] too,
 * the overlap), [filter] the rows, and [currency] adds each row's worth at today's rate. W6 sets none of them.
 */
data class HistoryQuery(
    /** The caller; [walletId] the wallet whose history it is (null: their own; a group wallet's for its members). */
    val userId: UserId,
    val month: YearMonth?,
    val after: HistoryPosition?,
    val limit: Int,
    val from: Instant? = null,
    val until: Instant? = null,
    val filter: HistoryFilter = HistoryFilter.ANY,
    val currency: String? = null,
    val walletId: WalletId? = null,
)

/**
 * Narrows the history (decision 2026-10-31, Asha's questions): every part optional, every part given must match.
 * [counterpartyAddress] is compared as stored ([com.circaso.wallet.domain.support.Addresses.normalise]).
 */
data class HistoryFilter(
    val direction: Direction? = null,
    val counterpartyId: UserId? = null,
    val counterpartyAddress: String? = null,
    /** A coin's symbol (`USDC`, `ETH`). */
    val asset: String? = null,
    val blockchain: String? = null,
) {
    companion object {
        val ANY = HistoryFilter()
    }
}

/** A place in the history: rows are ordered by `occurred_at` then `id`, both descending (`transactions_user_idx`). */
data class HistoryPosition(val occurredAt: Instant, val id: UUID)

/** Who is on the other side of a row, as far as Circaso knows them: a person when they are one, and the address. */
data class Counterparty(
    val userId: UserId?,
    val displayName: String?,
    val handle: String?,
    val avatarUrl: String?,
    val address: String?,
    /** Set when the other side is a group's wallet ([userId] is then that wallet's id; added 2026-11-03). */
    val groupId: ChatId? = null,
)

data class HistoryEntry(val transaction: WalletTransaction, val counterparty: Counterparty)

/**
 * One page of W6; [next] is where the following page starts, null on the last. [rate] is today's rate for the
 * currency the query named, or null when it named none or none is known.
 */
data class HistoryPage(val entries: List<HistoryEntry>, val next: HistoryPosition?, val rate: UsdRate? = null)

/**
 * One payment by its history row id (Asha's agent route), with today's [rate] when a currency was named. With
 * [walletId], a row of that group wallet's history, for its members (2026-11-10).
 */
data class TransactionQuery(
    val userId: UserId,
    val transactionId: UUID,
    val currency: String?,
    val walletId: WalletId? = null,
)

data class TransactionDetail(val entry: HistoryEntry, val rate: UsdRate?)

/**
 * Asha's totals over a stretch of the history (decision 2026-10-31): rows matching [filter] with `occurred_at` in
 * [from, until), either end open. Only payments that moved or are moving money count (`SUBMITTED`, `CONFIRMED`).
 */
data class SummaryQuery(
    val userId: UserId,
    val from: Instant?,
    val until: Instant?,
    val filter: HistoryFilter,
    val currency: String?,
    /** How many of the people paid or paid by to name, biggest first. */
    val topCounterparties: Int,
    /** The wallet whose history it adds up (null: the caller's own; a group wallet's for its members, 2026-11-10). */
    val walletId: WalletId? = null,
)

/** In and out of one asset; amounts in its minor units. */
data class AssetFlow(
    val asset: Asset,
    val inMinor: BigInteger,
    val outMinor: BigInteger,
    val inCount: Int,
    val outCount: Int,
)

/** What went between the user and one other party, in one asset. */
data class CounterpartyFlow(
    val counterparty: Counterparty,
    val asset: Asset,
    val inMinor: BigInteger,
    val outMinor: BigInteger,
    val count: Int,
)

/** Who a summary groups by: a person (by id), or an outside address, in one asset. */
data class Party(val userId: UserId?, val address: String?, val asset: Asset)

/** One calendar month (UTC) of one asset. */
data class MonthFlow(
    val month: YearMonth,
    val asset: Asset,
    val inMinor: BigInteger,
    val outMinor: BigInteger,
    val count: Int,
)

/**
 * The totals. [truncated] is true when more rows matched than the summary reads ([count] is then what was read):
 * a narrower range answers exactly.
 */
data class TransactionSummary(
    val from: Instant?,
    val until: Instant?,
    val count: Int,
    val assets: List<AssetFlow>,
    val topCounterparties: List<CounterpartyFlow>,
    val months: List<MonthFlow>,
    val rate: UsdRate?,
    val truncated: Boolean,
)

/** The states a summary adds up: money that moved or is on its way; a failed payment moved nothing. */
val SUMMARY_STATES: Set<TransferState> = setOf(TransferState.SUBMITTED, TransferState.CONFIRMED)

/**
 * The states the history shows. A transfer still `AWAITING_SIGNATURE`, or `EXPIRED` without ever being signed, moved
 * no money: it is an abandoned PIN sheet, not a payment. If the chain later says it was signed after all, the row
 * moves to `SUBMITTED` or further and appears.
 */
val HISTORY_STATES: Set<TransferState> = setOf(TransferState.SUBMITTED, TransferState.CONFIRMED, TransferState.FAILED)
