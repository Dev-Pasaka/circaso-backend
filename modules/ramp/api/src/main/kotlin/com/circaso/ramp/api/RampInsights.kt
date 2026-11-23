package com.circaso.ramp.api

import com.circaso.shared.Network
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// What staff see of each merchant's activity (2026-11-22). Every money figure is the merchant's own: the ramp adds it
// up from the same order and commission rows, with the same computation, as the merchant's earnings overview and
// statement, so the two always agree. A payer's number only ever leaves the ramp masked.

/** Nairobi calendar days, both inclusive. */
data class OpsRampPeriod(val from: LocalDate, val to: LocalDate)

/** Where a keyset page of orders continues: after the order made at [at] with id [orderId]. */
data class OpsRampOrderCursor(val at: Instant, val orderId: RampOrderId)

/**
 * The money of a period. [fees] is inside [collected]; [merchantShare] and [circasoShare] add up to [fees];
 * [swept] and [held] add up to [circasoShareUsdc].
 */
data class OpsRampMoney(
    val collected: RampAmount,
    val sold: RampAmount,
    val fees: RampAmount,
    val merchantShare: RampAmount,
    val circasoShare: RampAmount,
    val circasoShareUsdc: RampAmount,
    val swept: RampAmount,
    val held: RampAmount,
    /** Selling (2026-11-23): the shillings paid out to sellers, and the USDC received for them. */
    val paidOut: RampAmount = RampAmount("KES", "0", "0"),
    val bought: RampAmount = RampAmount("USDC", "0", "0"),
)

/**
 * A period's orders by how they stand. [completed] are counted on the day they completed; the others on the day they
 * were made. [total] is all of them.
 */
data class OpsRampOrderCounts(
    val total: Int,
    val completed: Int,
    val failed: Int,
    val expired: Int,
    val cancelled: Int,
    val inReview: Int,
    val open: Int,
    /** Sales whose USDC went back to the user (2026-11-23). */
    val refunded: Int = 0,
)

/** One merchant's row of the overview. [online] is the trading window: staff can't see whose keys are unlocked. */
data class OpsRampMerchantActivity(
    val merchantId: MerchantId,
    val displayName: String,
    val state: String,
    val online: Boolean,
    val orders: OpsRampOrderCounts,
    val successRatePercent: String,
    val money: OpsRampMoney,
    val lastOrderAt: Instant?,
    /** The three wallets at the provider as last read (2026-11-24), buying's first. */
    val providerWallets: List<OpsRampProviderWallet> = emptyList(),
)

/**
 * One of a merchant's three wallets at their provider as last read (2026-11-24): [wallet] `PAYHERO_SERVICE` (buying's
 * prompt fees), `FINOQ_SERVICE` (selling's payout fees) or `FINOQ_PAYMENT` (what payouts spend), [name] what to call
 * it, [direction] `ON_RAMP` | `OFF_RAMP`. A null [balance] is one never read, never zero. [low]: under the network's
 * line, or the provider refused a prompt or a payout for it.
 */
data class OpsRampProviderWallet(
    val wallet: String,
    val name: String,
    val provider: String,
    val direction: String,
    val balance: RampAmount?,
    val source: String?,
    val readAt: Instant?,
    val low: Boolean,
)

/** Every merchant's activity on [network] in [period]; [orders] and [money] are the sums of the rows. */
data class OpsRampOverview(
    val network: Network,
    val period: OpsRampPeriod,
    val orders: OpsRampOrderCounts,
    val money: OpsRampMoney,
    val merchants: List<OpsRampMerchantActivity>,
)

/** Something at the merchant's payment provider only they can fix: [code] is `SERVICE_BALANCE`. */
data class OpsRampPaymentsIssue(val code: String, val since: Instant?)

/** The merchant's account, as the summary shows it. Their payment credentials stay sealed. */
data class OpsRampMerchantProfile(
    val merchantId: MerchantId,
    val displayName: String,
    val email: String,
    val contactPhone: String,
    val state: String,
    val createdAt: Instant,
    val approvedAt: Instant?,
    val online: Boolean,
    val onlineUntil: Instant?,
    val paymentsConnected: Boolean,
    val collectionsProvider: String?,
    val paymentsIssue: OpsRampPaymentsIssue?,
    /** Their identity verification in the environment the summary's network trusts; null when they never started. */
    val kyc: OpsRampVerification? = null,
    /** Selling (2026-11-23): whether they offer it, their payout account id, and a payout refusal they must fix. */
    val offersOffRamp: Boolean = false,
    val payoutAccountId: String? = null,
    val payoutIssue: OpsRampPaymentsIssue? = null,
    /** Online until the merchant stops it: [onlineUntil] is then null (decision 2026-11-23). */
    val onlineOpenEnded: Boolean = false,
    /** Why the merchant's last session ended (`STOPPED`, `RESTART`, ...); null while online or when not known. */
    val offlineReason: String? = null,
) {
    override fun toString(): String = "OpsRampMerchantProfile($merchantId, $state)"
}

/** A float wallet's USDC: what open orders hold, what is held for Circaso until the next sweep, and what is free. */
data class OpsRampFloat(
    val balance: RampAmount,
    val reserved: RampAmount,
    val owedToCircaso: RampAmount,
    val free: RampAmount,
)

/** One Nairobi day with activity. */
data class OpsRampDay(
    val day: LocalDate,
    val orders: Int,
    val completed: Int,
    val collected: RampAmount,
    val sold: RampAmount,
    val fees: RampAmount,
    val merchantShare: RampAmount,
    val circasoShare: RampAmount,
)

/** One daily sweep of Circaso's share. [state] is `PENDING`, `CONFIRMED` or `FAILED`. */
data class OpsRampSweep(
    val settlementId: UUID,
    val day: LocalDate,
    val amount: RampAmount,
    val state: String,
    val orders: Int,
    val txHash: String?,
)

/** One merchant on [network] in [period]. [wallet] is null until the ops server has made it; [float] when unreadable. */
data class OpsRampMerchantSummary(
    val merchant: OpsRampMerchantProfile,
    val network: Network,
    val period: OpsRampPeriod,
    val wallet: OpsMerchantWallet?,
    val float: OpsRampFloat?,
    val orders: OpsRampOrderCounts,
    val successRatePercent: String,
    val money: OpsRampMoney,
    /** Oldest day first. */
    val daily: List<OpsRampDay>,
    val sweeps: List<OpsRampSweep>,
    /**
     * The merchant's KES at the payout provider as last read (2026-11-23): the ops process holds nobody's keys, so this
     * is the app's latest reading, with its time. A null amount is one that couldn't be read.
     */
    val kesBalances: OpsRampKesBalances? = null,
)

/**
 * A merchant's balances at the payout provider as last read: [payout] is FinoQ's payment wallet (what payouts spend),
 * [fees] FinoQ's service wallet (a payout's fee). [wallets] has all three, PayHero's service wallet (buying's) too.
 */
data class OpsRampKesBalances(
    val payout: RampAmount?,
    val payoutSource: String?,
    val fees: RampAmount?,
    val feesSource: String?,
    val readAt: Instant?,
    /** What their open sales hold of [payout]. */
    val held: RampAmount,
    val wallets: List<OpsRampProviderWallet> = emptyList(),
)

/**
 * One of a merchant's orders for staff. [released] is the USDC the order is for: it reached the buyer only when
 * [state] is `COMPLETED`. The shares, [completedAt] and [settlementId] come from the order's commission, so they are
 * null until it completes ([settlementId] until the sweep that took Circaso's share is confirmed).
 */
data class OpsRampMerchantOrder(
    val orderId: RampOrderId,
    val createdAt: Instant,
    val completedAt: Instant?,
    val state: String,
    val direction: String,
    val blockchain: String,
    val maskedPhone: String,
    val paid: RampAmount,
    val released: RampAmount,
    val rate: String,
    val fee: RampAmount,
    val merchantShare: RampAmount?,
    val circasoShare: RampAmount?,
    val circasoShareUsdc: RampAmount?,
    val failureReason: String?,
    val settlementId: UUID?,
    val txHash: String?,
    val collectionsProvider: String,
) {
    override fun toString(): String = "OpsRampMerchantOrder($orderId, $state)"
}
