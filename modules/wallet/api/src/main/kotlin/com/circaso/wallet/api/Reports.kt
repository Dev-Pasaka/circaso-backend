package com.circaso.wallet.api

import com.circaso.messaging.api.ChatId
import com.circaso.shared.Network
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
data class OpsBalance(
    val asset: String,
    val amountMinor: BigInteger,
    val decimals: Int,
    val usdMicros: Long?,
)

/** [state] is `ACTIVE`, `FROZEN` or `CLOSED`. */
data class OpsChainWallet(
    val blockchain: String,
    val address: String,
    val state: String,
    val balances: List<OpsBalance>,
) {
    val network: Network get() = Network.ofChain(blockchain)
}

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

/**
 * [state]: `AWAITING_SIGNATURE`, `SUBMITTED`, `CONFIRMED`, `FAILED`, `EXPIRED`. [origin]: `APP` or `ASHA`.
 *
 * Out of a group wallet [senderId] is that wallet's holder id and [fromGroupId] the group; into one, [recipientId] is
 * the wallet's holder id and [toGroupId] the group. [feeAddress] is Circaso's fee wallet the fee leg ([feeMinor]) went
 * to in the same signature, null when the payment carried none. [toFeeWallet] is true when the destination itself is
 * Circaso's fee wallet on the chain (the configured `wallet.fees.chain.<chain>.address`), so staff never read
 * Circaso's own wallet as an outside address.
 */
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
    val fromGroupId: ChatId? = null,
    val toGroupId: ChatId? = null,
    val feeAddress: String? = null,
    val toFeeWallet: Boolean = false,
) {
    val network: Network get() = Network.ofChain(blockchain)
}

/** Every field optional; [from] inclusive, [to] exclusive, on the transfer's creation time. */
data class TransferFilter(
    val state: String? = null,
    val blockchain: String? = null,
    val asset: String? = null,
    val userId: UserId? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    /** Only this network's transfers; null for both (2026-11-18). */
    val network: Network? = null,
)

data class TransferPage(val items: List<OpsTransfer>, val nextCursor: String?)

enum class ReportBucket { DAY, WEEK, MONTH }

/** One period (its first UTC day) of confirmed transfers: how many, their dollar weight, and the fees collected. */
data class PeriodTotals(
    val period: LocalDate,
    val count: Long,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
    /** The network these totals are for: a period has one row per network asked for, never the two added up. */
    val network: Network = Network.TESTNET,
)

/** One network's totals over the report's range (2026-11-18). */
data class NetworkTotals(
    val network: Network,
    val count: Long,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
)

/** Confirmed transfers of one coin on one chain over the range. */
data class ChainAssetTotals(
    val blockchain: String,
    val asset: String,
    val count: Long,
    val amountMinor: BigInteger,
    val decimals: Int,
    val volumeUsdMicros: Long,
    val feesUsdMicros: Long,
) {
    val network: Network get() = Network.ofChain(blockchain)
}

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
    /** The totals per network asked for (2026-11-18); the three figures above add them up only when both were. */
    val byNetwork: List<NetworkTotals> = emptyList(),
)

/** A group's wallet: [walletId] is also its holder id for [WalletReports.holdings]; [signerId] the one who signs. */
data class GroupWalletRef(
    val chatId: ChatId,
    val walletId: UUID,
    val signerId: UserId,
    val createdAt: Instant,
    val network: Network = Network.TESTNET,
)

data class GroupWalletPage(val items: List<GroupWalletRef>, val nextCursor: String?)

/**
 * The wallet's staff reads. Every one takes an optional `network` (2026-11-18): only that network's rows, or both
 * networks' when null. Rows carry their network, and no figure adds the two up unless both were asked for.
 */
interface WalletReports {
    suspend fun holdings(holderId: UUID, network: Network? = null): Resource<OpsHoldings>

    suspend fun transfersOf(
        userId: UserId,
        cursor: String?,
        limit: Int,
        network: Network? = null,
    ): Resource<TransferPage>

    suspend fun feesPaidBy(userId: UserId, network: Network? = null): Long

    suspend fun transfers(
        filter: TransferFilter,
        cursor: String?,
        limit: Int,
    ): Resource<TransferPage>

    suspend fun transfer(transferId: TransferId): OpsTransfer?

    suspend fun report(
        from: Instant,
        to: Instant,
        bucket: ReportBucket,
        network: Network? = null,
    ): FinanceReport

    suspend fun groupWalletCount(network: Network? = null): Long

    suspend fun groupWallets(
        cursor: String?,
        limit: Int,
        network: Network? = null,
    ): Resource<GroupWalletPage>

    /** Each group's wallet on [network]; with no network, its mainnet wallet when it has one, else its testnet one. */
    suspend fun groupWalletsOf(chatIds: Set<ChatId>, network: Network? = null): Map<ChatId, GroupWalletRef>

    /** Every wallet each group has on the networks asked for, mainnet first. */
    suspend fun groupWalletsIn(chatIds: Set<ChatId>, network: Network? = null): Map<ChatId, List<GroupWalletRef>> =
        groupWalletsOf(chatIds, network).mapValues { listOf(it.value) }
}
