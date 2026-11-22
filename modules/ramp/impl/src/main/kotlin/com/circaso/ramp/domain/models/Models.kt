package com.circaso.ramp.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.UserId
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

enum class Direction { ON_RAMP, OFF_RAMP }

/**
 * Where an order stands. It moves only by a transition guard (`WHERE state IN <allowed predecessors>`), so a repeated
 * callback, the sweep job and the release job can never apply the same step twice.
 */
enum class OrderState {
    /** A merchant is picked and its float reserved; the prompt hasn't been sent yet. */
    RESERVED,

    /** The M-Pesa prompt is on the payer's phone. */
    PAYMENT_REQUESTED,

    /** The provider itself confirmed the payment and it matches the order. The ops process releases it. */
    PAID,

    /** The USDC is on its way from the merchant's wallet. */
    RELEASING,
    COMPLETED,

    /** The prompt was never sent or never paid. */
    EXPIRED,
    CANCELLED,

    /** The provider says the payment failed or was cancelled. */
    FAILED,

    /** A person must look: the payment doesn't match, or the release couldn't be made. Nothing more moves. */
    REVIEW,
    ;

    val isOpen: Boolean get() = this in OPEN

    companion object {
        val OPEN = setOf(RESERVED, PAYMENT_REQUESTED, PAID, RELEASING)
        val AWAITING_PAYMENT = setOf(RESERVED, PAYMENT_REQUESTED)
    }
}

/** A price for one amount, good until [expiresAt]. [rate] is shillings per USDC; [feeMinor] is in the fiat currency. */
data class Quote(
    val quoteId: QuoteId,
    val userId: UserId,
    val network: Network,
    val direction: Direction,
    val blockchain: String,
    val fiat: FiatAmount,
    val assetMinor: Long,
    val rate: BigDecimal,
    val feeMinor: Long,
    /** The fee rate and the merchant's share of it in force when the quote was priced; its order keeps them. */
    val feeBps: Int,
    val merchantShareBps: Int,
    val expiresAt: Instant,
)

data class Order(
    val orderId: RampOrderId,
    val userId: UserId,
    val merchantId: MerchantId,
    val quoteId: QuoteId,
    val network: Network,
    val direction: Direction,
    val state: OrderState,
    val blockchain: String,
    val fiat: FiatAmount,
    val assetMinor: Long,
    val rate: BigDecimal,
    val feeMinor: Long,
    val feeBps: Int,
    val merchantShareBps: Int,
    /** The collections provider that sent its prompt: its payment status is asked there, whatever is set later. */
    val collectionsProvider: String,
    val payerPhone: PhoneNumber,
    val destinationAddress: String,
    val providerReference: String?,
    val checkoutId: String?,
    val mpesaReceipt: String?,
    val custodyTxId: String?,
    val txHash: String?,
    val failureReason: String?,
    val paymentDeadline: Instant,
    val createdAt: Instant,
    val updatedAt: Instant,
    /**
     * Which try of its quote this order is: 1, and one more each time the provider refused the merchant before it and
     * the next merchant was asked. A quote has one order per attempt.
     */
    val attempt: Int = 1,
) {
    override fun toString(): String = "Order($orderId, $state)"
}

/** What a transition may write beside the new state; null leaves a column as it is. */
data class OrderPatch(
    val providerReference: String? = null,
    val checkoutId: String? = null,
    val custodyTxId: String? = null,
    val txHash: String? = null,
    val failureReason: String? = null,
)

/** How marking an order paid ended. */
sealed interface PaidOutcome {
    data class Moved(val order: Order) : PaidOutcome

    /** The order was already past waiting for its payment: another caller got there first. */
    data object AlreadyMoved : PaidOutcome

    /** The M-Pesa receipt already settled another order: one payment never releases twice. */
    data object ReceiptUsed : PaidOutcome
}

data class QuoteCommand(
    val userId: UserId,
    val network: Network,
    val direction: Direction,
    val blockchain: String,
    /** Exactly one of the two is given. */
    val fiatMinor: Long?,
    val assetMinor: Long?,
)

data class StartOnRampCommand(
    val userId: UserId,
    val network: Network,
    val quoteId: QuoteId,
    val phone: PhoneNumber,
)

/** An order as its user sees it. */
data class OrderView(val order: Order, val merchantName: String)

/** Where a keyset page continues: the row after ([at], [id]) in the list's order. */
data class PageCursor(val at: Instant, val id: UUID)

/** R1: whether this user can trade now. Never names a merchant. */
data class RampStatus(
    val network: Network,
    val kyc: KycStanding,
    val accountActive: Boolean,
    val blockchains: List<String>,
    val merchantOnline: Boolean,
    val openOrderId: RampOrderId?,
    val limits: RampLimits,
    val feeBps: Int,
)

enum class KycStanding { VERIFIED, NONE, NOT_REQUIRED }

data class RampLimits(
    val currency: String,
    val minOrderMinor: Long,
    val maxOrderMinor: Long,
    val dailyMinor: Long,
)

/** A priced amount: both sides, the rate and the fee. */
data class Priced(
    val fiatMinor: Long,
    val assetMinor: Long,
    val rate: BigDecimal,
    val feeMinor: Long,
)

/** How an order's fee is shared: the merchant's part and Circaso's in the fiat currency, and Circaso's in USDC. */
data class FeeSplit(val merchantMinor: Long, val platformMinor: Long, val platformAssetMinor: Long)
