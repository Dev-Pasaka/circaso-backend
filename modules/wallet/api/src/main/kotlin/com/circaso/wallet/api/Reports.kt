package com.circaso.wallet.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.math.BigInteger
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// The wallet's staff-facing contract (decision 2026-11-08), for the ops module behind the ops gateway only. Read-only:
// staff never move money. Dollar figures are integer micro-dollars (1 USD = 1_000_000), the unit the wallet already
// keeps (`usd_total_minor`, `fee_usd_minor`); coin amounts are exact integers in the coin's minor units.

/** What a wallet holds of one coin on one chain. [usdMicros] null when the coin has no price. */
data class OpsBalance(val asset: String, val amountMinor: BigInteger, val decimals: Int, val usdMicros: Long?)

/** [state] is `ACTIVE`, `FROZEN` or `CLOSED`. */
data class OpsChainWallet(
    val blockchain: String,
    val address: String,
    val state: String,
    val balances: List<OpsBalance>,
)

/**
 * A holder's wallets and balances, read from Circle (the source of truth for balances) through the wallet's short
 * balance cache. [balancesAvailable] is false when Circle couldn't be read: the wallets are listed without balances.
 * Reading never marks the holder active, so a staff look doesn't change the reconciliation pass.
 */
data class OpsHoldings(
    val hasWallet: Boolean,
    val wallets: List<OpsChainWallet>,
    val totalUsdMicros: Long?,
    val balancesAvailable: Boolean,
)

/** How a transfer moved: person to person, out of or into a group wallet, or to an address outside Circaso. */
enum class OpsTransferKind { PERSON, GROUP_OUT, GROUP_IN, EXTERNAL }

/** [state]: `AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED`, `EXPIRED`. [origin]: `APP` or `ASHA`. */
data class OpsTransfer(
    val transferId: TransferId,
    val senderId: UserId,
    val recipientId: UserId?,
    val destinationAddress: String,
    val blockchain: String,
    val asset: String,
    val amountMinor: BigInteger,
    val decimals: Int,
    val feeMinor: BigInteger,
    val usdTotalMicros: Long,
    val state: String,
    val origin: String,
    val kind: OpsTransferKind,
    val txHash: String?,
    val failureReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Every field optional; [from] inclusive, [to] exclusive, on the transfer's creation time. */
data class TransferFilter(
    val state: String? = null,
    val blockchain: String? = null,
    val asset: String? = null,
    val userId: UserId? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

data class TransferPage(val items: List<OpsTransfer>, val nextCursor: String?)

enum class ReportBucket { DAY, WEEK, MONTH }

/** One period (its first UTC day) of confirmed transfers: how many, their dollar weight, and the fees collected. */
data class PeriodTotals(val period: LocalDate, val count: Long, val volumeUsdMicros: Long, val feesUsdMicros: Long)

/** Confirmed transfers of one coin on one chain over the range. */
data class ChainAssetTotals(
    val blockchain: String,
    val asset: String,
    val count: Long,
    val amountMinor: BigInteger,
    val decimals: Int,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
)

/**
 * Platform money over a range. Only `CONFIRMED` transfers count; the volume is each transfer's dollar weight (amount
 * and fee at the price it was made at, `usd_total_minor`); fees are the fee legs actually collected (`COLLECTED`).
 * [series] has one entry per period of the range, zeros included.
 */
data class FinanceReport(
    val series: List<PeriodTotals>,
    val byChainAsset: List<ChainAssetTotals>,
    val count: Long,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
)

/** A group's wallet: [walletId] is also its holder id for [WalletReports.holdings]; [signerId] the one who signs. */
data class GroupWalletRef(val chatId: ChatId, val walletId: UUID, val signerId: UserId, val createdAt: Instant)

data class GroupWalletPage(val items: List<GroupWalletRef>, val nextCursor: String?)

interface WalletReports {
    /** A person's wallet ([holderId] = their user id) or a group's ([GroupWalletRef.walletId]). */
    suspend fun holdings(holderId: UUID): Resource<OpsHoldings>

    /** Transfers [userId] sent or received, newest first. Error: `request.malformed` for a bad cursor. [limit] 1-200. */
    suspend fun transfersOf(userId: UserId, cursor: String?, limit: Int): Resource<TransferPage>

    /** Fees [userId] paid on collected fee legs, all time, in micro-dollars. */
    suspend fun feesPaidBy(userId: UserId): Long

    /** Every transfer matching [filter], newest first. Same paging. */
    suspend fun transfers(filter: TransferFilter, cursor: String?, limit: Int): Resource<TransferPage>

    suspend fun transfer(transferId: TransferId): OpsTransfer?

    /** [from] inclusive, [to] exclusive; at most 366 days. */
    suspend fun report(from: Instant, to: Instant, bucket: ReportBucket): FinanceReport

    suspend fun groupWalletCount(): Long

    /** Group wallets, newest first. Same paging. */
    suspend fun groupWallets(cursor: String?, limit: Int): Resource<GroupWalletPage>

    /** The wallets of those of [chatIds] that have one. */
    suspend fun groupWalletsOf(chatIds: Set<ChatId>): Map<ChatId, GroupWalletRef>
}
