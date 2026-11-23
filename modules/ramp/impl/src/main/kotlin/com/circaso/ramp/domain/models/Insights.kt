package com.circaso.ramp.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.shared.Network
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// A merchant's activity as staff see it. Every money figure is the merchant's own: the same `StatementTotals`, read
// by the same query, that their earnings overview (RM8) and statement (RM15) show, so the two can never disagree.

/**
 * A merchant's orders in a period that did **not** complete, by how they stand: counted on the Nairobi day they were
 * made. Completed orders are counted by [StatementTotals.orders], on the day they completed.
 */
data class UnfinishedOrders(
    val failed: Int,
    val expired: Int,
    val cancelled: Int,
    val inReview: Int,
    val open: Int,
    /** Sales whose USDC went back to the user (2026-11-23). */
    val refunded: Int = 0,
) {
    val total: Int get() = failed + expired + cancelled + inReview + open + refunded

    operator fun plus(other: UnfinishedOrders) =
        UnfinishedOrders(
            failed = failed + other.failed,
            expired = expired + other.expired,
            cancelled = cancelled + other.cancelled,
            inReview = inReview + other.inReview,
            open = open + other.open,
            refunded = refunded + other.refunded,
        )

    companion object {
        val NONE = UnfinishedOrders(0, 0, 0, 0, 0)

        fun of(byState: Map<OrderState, Int>): UnfinishedOrders =
            UnfinishedOrders(
                failed = byState[OrderState.FAILED] ?: 0,
                expired = byState[OrderState.EXPIRED] ?: 0,
                cancelled = byState[OrderState.CANCELLED] ?: 0,
                inReview = byState[OrderState.REVIEW] ?: 0,
                open = OrderState.OPEN.sumOf { byState[it] ?: 0 },
                refunded = byState[OrderState.REFUNDED] ?: 0,
            )
    }
}

/** A merchant's figures for a period: the money ([totals], as RM8 has it) and the orders that came to nothing. */
data class MerchantFigures(val totals: StatementTotals, val unfinished: UnfinishedOrders) {
    val completed: Int get() = totals.orders

    val orders: Int get() = completed + unfinished.total

    /** Completed orders out of all of them, as a percentage with at most one decimal; `0` when there were none. */
    val successRatePercent: String get() = successRate(completed, orders)

    operator fun plus(other: MerchantFigures) = MerchantFigures(totals + other.totals, unfinished + other.unfinished)

    companion object {
        val NONE = MerchantFigures(StatementTotals(0, 0, 0, 0, 0, 0, 0, 0), UnfinishedOrders.NONE)

        fun successRate(completed: Int, orders: Int): String =
            if (orders <= 0) {
                "0"
            } else {
                BigDecimal(completed * PERCENT)
                    .divide(BigDecimal(orders), 1, RoundingMode.HALF_UP)
                    .stripTrailingZeros()
                    .toPlainString()
            }

        private const val PERCENT = 100
    }
}

/** One merchant's row of the staff overview. */
data class MerchantActivity(
    val merchant: Merchant,
    val online: Boolean,
    val figures: MerchantFigures,
    /** When the merchant's latest order on the network was made, whatever the period. */
    val lastOrderAt: Instant?,
)

/** Every merchant's activity on a network in a period. The totals are the sum of the rows, and nothing else. */
data class RampOverview(val period: Period, val network: Network, val merchants: List<MerchantActivity>) {
    val totals: MerchantFigures get() = merchants.fold(MerchantFigures.NONE) { sum, row -> sum + row.figures }
}

/**
 * A merchant's float as staff see it, in USDC minor units: what open orders hold ([reservedMinor]) and what is held for
 * Circaso until the next sweep ([owedMinor]) are shown apart.
 */
data class StaffFloat(val balanceMinor: Long, val reservedMinor: Long, val owedMinor: Long) {
    val freeMinor: Long get() = (balanceMinor - reservedMinor - owedMinor).coerceAtLeast(0)
}

/** What a merchant's completed exchanges came to on one Nairobi day. */
data class DayTotals(
    val day: LocalDate,
    val completed: Int,
    val collectedMinor: Long,
    val soldMinor: Long,
    val feesMinor: Long,
    val merchantMinor: Long,
    val platformMinor: Long,
)

/** One Nairobi day of a merchant's activity: [totals] for what completed that day, [unfinished] made that day. */
data class DayActivity(val totals: DayTotals, val unfinished: Int) {
    val orders: Int get() = totals.completed + unfinished
}

/** One merchant in depth, for staff. */
data class MerchantSummary(
    val merchant: Merchant,
    val online: Boolean,
    /** The provider the stored payment account is at; null when none is stored. */
    val paymentsProvider: String?,
    val period: Period,
    val network: Network,
    val wallet: MerchantWallet?,
    /** Null without a wallet, or when the custodian can't be asked. */
    val float: StaffFloat?,
    val figures: MerchantFigures,
    /** Days with activity, oldest first. */
    val daily: List<DayActivity>,
    val sweeps: List<Settlement>,
    /** The merchant's balances at the payout provider as last read, and what their open sales hold. */
    val kesBalances: KesBalances = KesBalances.UNREAD,
    val kesHeldMinor: Long = 0,
)

/** What a completed order earned, from its commission row. */
data class CommissionFigures(
    val earnedAt: Instant,
    val merchantMinor: Long,
    val platformMinor: Long,
    val platformAssetMinor: Long,
    /** The sweep that collected Circaso's share; null while the float still holds it. */
    val settlementId: UUID?,
)

/** One of a merchant's orders as staff list them: the order, and its commission once it completed. */
data class StaffOrder(val order: Order, val commission: CommissionFigures?)

/** Which of a merchant's orders staff ask for. A null [period] is every day; a null [state] every state. */
data class StaffOrderFilter(
    val merchantId: MerchantId,
    val network: Network,
    val period: Period?,
    val state: OrderState?,
)
