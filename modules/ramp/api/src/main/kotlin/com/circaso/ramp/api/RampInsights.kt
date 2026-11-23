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
