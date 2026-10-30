package com.circaso.wallet.domain.models

import com.circaso.shared.UserId
import java.time.Instant
import java.time.YearMonth
import java.util.UUID

/**
 * W6's question: [userId]'s history, newest first, after [after] (the last row of the previous page), at most
 * [limit]. With [month], only that calendar month in UTC; without it, every month, for the wallet tab's recent list.
 */
data class HistoryQuery(
    val userId: UserId,
    val month: YearMonth?,
    val after: HistoryPosition?,
    val limit: Int,
)

/** A place in the history: rows are ordered by `occurred_at` then `id`, both descending (`transactions_user_idx`). */
data class HistoryPosition(val occurredAt: Instant, val id: UUID)

/** Who is on the other side of a row, as far as Circaso knows them: a person when they are one, and the address. */
data class Counterparty(
    val userId: UserId?,
    val displayName: String?,
    val handle: String?,
    val avatarUrl: String?,
    val address: String?,
)

data class HistoryEntry(val transaction: WalletTransaction, val counterparty: Counterparty)

/** One page of W6; [next] is where the following page starts, null on the last. */
data class HistoryPage(val entries: List<HistoryEntry>, val next: HistoryPosition?)

/**
 * The states the history shows. A transfer still `AWAITING_SIGNATURE`, or `EXPIRED` without ever being signed, moved
 * no money: it is an abandoned PIN sheet, not a payment. If the chain later says it was signed after all, the row
 * moves to `SUBMITTED` or further and appears.
 */
val HISTORY_STATES: Set<TransferState> = setOf(TransferState.SUBMITTED, TransferState.CONFIRMED, TransferState.FAILED)
