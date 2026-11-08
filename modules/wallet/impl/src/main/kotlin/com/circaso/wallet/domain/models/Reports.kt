package com.circaso.wallet.domain.models

import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// What staff read about money on the ops gateway (decision 2026-11-08). Read-only: staff never move money.

/** One coin on one chain as staff see it: the holding and its dollar worth in micro-dollars, null when unpriced. */
data class StaffBalance(val holding: Holding, val usdMicros: Long?)

/** One chain of a holder's wallet; [balances] is empty when Circle couldn't be read. */
data class StaffChainWallet(val wallet: ChainWallet, val balances: List<StaffBalance>)

/**
 * A holder's wallets for staff. [balancesAvailable] is false when Circle couldn't be read: the wallets are listed
 * without balances and [totalUsdMicros] is null, never an error.
 */
data class StaffHoldings(
    val hasWallet: Boolean,
    val wallets: List<StaffChainWallet>,
    val totalUsdMicros: Long?,
    val balancesAvailable: Boolean,
)

/** Where a newest-first page of transfers stopped: the last one's `(created_at, transfer_id)`. */
data class TransferKey(val createdAt: Instant, val transferId: TransferId)

/** Which side of a transfer a person is on, for the per-person reads (each served by its own index). */
sealed interface TransferParty {
    val userId: UserId

    data class Sender(override val userId: UserId) : TransferParty

    data class Recipient(override val userId: UserId) : TransferParty
}

/** A staff transfer search; every field optional. [from] inclusive, [until] exclusive, on the creation time. */
data class TransferQuery(
    val state: TransferState? = null,
    val blockchain: String? = null,
    val asset: String? = null,
    val party: TransferParty? = null,
    val from: Instant? = null,
    val until: Instant? = null,
)

data class TransferResults(val transfers: List<Transfer>, val nextCursor: String?)

/** Confirmed transfers in one period: how many and their dollar weight in micro-dollars. */
data class PeriodSums(val count: Long, val volumeUsdMicros: Long)

/** Confirmed transfers of one coin on one chain: how many, how much of the coin, and their dollar weight. */
data class ChainAssetSums(
    val blockchain: String,
    val asset: String,
    val decimals: Int,
    val count: Long,
    val amountMinor: BigInteger,
    val volumeUsdMicros: Long,
)

/** One period of the finance report, its first UTC day. */
data class ReportPeriod(
    val period: LocalDate,
    val count: Long,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
)

/** One coin on one chain of the finance report. */
data class ReportChainAsset(val sums: ChainAssetSums, val feesUsdMicros: Long)

data class FinanceTotals(
    val series: List<ReportPeriod>,
    val byChainAsset: List<ReportChainAsset>,
    val count: Long,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
)

/** Where a newest-first page of group wallets stopped. */
data class GroupWalletKey(val createdAt: Instant, val walletId: UUID)

data class GroupWalletResults(val owners: List<WalletOwner.Group>, val nextCursor: String?)
