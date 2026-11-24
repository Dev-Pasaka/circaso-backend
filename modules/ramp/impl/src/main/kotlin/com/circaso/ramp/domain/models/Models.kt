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
 *
 * Buying: `RESERVED → PAYMENT_REQUESTED → PAID → RELEASING → COMPLETED`. Selling (decision 2026-11-23):
 * `AWAITING_TRANSFER → FUNDED → PAYING_OUT → COMPLETED`, with `REFUNDING → REFUNDED` when the user can't be paid (see
 * [SaleStates] for every allowed move). `EXPIRED`, `CANCELLED` and `REVIEW` are shared.
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

    /** Selling: a merchant is picked and their shillings held; the user's USDC hasn't arrived yet. */
    AWAITING_TRANSFER,

    /** Selling: the custodian recorded the user's USDC in the merchant's float, where it is held. No payout is out. */
    FUNDED,

    /** Selling: a payout attempt is recorded and may have been sent: only the provider's lookup moves it on. */
    PAYING_OUT,

    /** Selling: the user is owed their USDC back and the ops process is sending it. */
    REFUNDING,

    /** Selling: the user's USDC is back in their wallet, confirmed on chain. */
    REFUNDED,
    ;

    val isOpen: Boolean get() = this in OPEN

    companion object {
        val OPEN = setOf(RESERVED, PAYMENT_REQUESTED, PAID, RELEASING, AWAITING_TRANSFER, FUNDED, PAYING_OUT, REFUNDING)
        val AWAITING_PAYMENT = setOf(RESERVED, PAYMENT_REQUESTED)

        /** The states in which a merchant's keys are still needed for an order: a prompt's status, or a payout. */
        val NEEDS_KEYS = setOf(RESERVED, PAYMENT_REQUESTED, AWAITING_TRANSFER, FUNDED, PAYING_OUT)
    }
}

/**
 * Every move a sale may make, and from where (decision 2026-11-23). The rule behind the table: **a refund never
 * starts while a payout may have gone out** (only `FUNDED`, where no attempt is out, a `PAYING_OUT` the provider said
 * failed, a sale that was never paid, and staff's decision lead to it), and **a sale completes only on the provider's
 * word or staff's** (`PAYING_OUT` after a lookup said paid, `REVIEW` when staff saw the M-Pesa receipt).
 */
object SaleStates {
    /** The user's USDC was confirmed in the merchant's float. Or a payout that provably went nowhere: again. */
    val TO_FUNDED = setOf(OrderState.AWAITING_TRANSFER, OrderState.PAYING_OUT)

    val TO_PAYING_OUT = setOf(OrderState.FUNDED)

    val TO_COMPLETED = setOf(OrderState.PAYING_OUT, OrderState.REVIEW)

    /** Never funded by the deadline, or cancelled by the user before they sent anything. */
    val TO_CLOSED = setOf(OrderState.AWAITING_TRANSFER)

    val TO_REFUNDING =
        setOf(
            OrderState.AWAITING_TRANSFER,
            OrderState.FUNDED,
            OrderState.PAYING_OUT,
            OrderState.EXPIRED,
            OrderState.CANCELLED,
            OrderState.REVIEW,
        )

    val TO_REFUNDED = setOf(OrderState.REFUNDING)

    val TO_REVIEW =
        setOf(
            OrderState.AWAITING_TRANSFER,
            OrderState.FUNDED,
            OrderState.PAYING_OUT,
            OrderState.REFUNDING,
            OrderState.EXPIRED,
            OrderState.CANCELLED,
        )
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
    /** Selling: the provider's payout fee the user pays on top of [feeMinor] (KES minor units), and its source. */
    val providerFeeMinor: Long = 0,
    val providerFeeSource: String? = null,
    /** How [rate] was made (decision 2026-11-24); null on quotes made before it was recorded. */
    val rateBasis: RateBasis? = null,
    /** Where the fee band that set [feeBps] starts, KES minor units (2026-11-24); null for one flat band. */
    val feeBandFromMinor: Long? = null,
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
    /** Selling: the provider's payout fee quoted (the user pays it), where it came from, and the tariff when sent. */
    val providerFeeMinor: Long = 0,
    val providerFeeSource: String? = null,
    val providerFeeActualMinor: Long? = null,
    /** Selling: the user's W4 transfer that funds it, once the wallet confirmed it. */
    val walletTransferId: UUID? = null,
    /** Selling: the USDC the merchant's float received, as the custodian recorded it, and that record's hash. */
    val fundedMinor: Long? = null,
    val fundingTxHash: String? = null,
    val fundedAt: Instant? = null,
    /** Selling: the user's own wallet the USDC came from: where a refund goes. */
    val refundAddress: String? = null,
    /** Selling: payout attempts made, and the latest one's references and outcome. */
    val payoutAttempts: Int = 0,
    /** Selling: how many attempts the provider itself said failed (a second ends the sale in a refund). */
    val payoutFailures: Int = 0,
    val payoutReference: String? = null,
    val payoutProviderReference: String? = null,
    val payoutSecondaryReference: String? = null,
    val payoutOutcome: String? = null,
    val payoutSentAt: Instant? = null,
    /** Selling, `FUNDED`: the next payout attempt not before then. */
    val payoutRetryAt: Instant? = null,
    /** How [rate] was made, copied from the quote (decision 2026-11-24); null on orders from before. */
    val rateBasis: RateBasis? = null,
    /** The fee band that set [feeBps], copied from the quote; null for one flat band. */
    val feeBandFromMinor: Long? = null,
) {
    /** Selling: the shillings held at the merchant's provider for it, the payout and its fee. */
    val payoutHoldMinor: Long get() = fiat.amountMinor + providerFeeMinor

    override fun toString(): String = "Order($orderId, $state)"
}

/** What a transition may write beside the new state; null leaves a column as it is. */
data class OrderPatch(
    val providerReference: String? = null,
    val checkoutId: String? = null,
    val custodyTxId: String? = null,
    val txHash: String? = null,
    val failureReason: String? = null,
    val providerFeeActualMinor: Long? = null,
    val fundedMinor: Long? = null,
    val fundingTxHash: String? = null,
    val fundedAt: Instant? = null,
    val refundAddress: String? = null,
    val payoutAttempts: Int? = null,
    val payoutFailures: Int? = null,
    val payoutReference: String? = null,
    val payoutProviderReference: String? = null,
    val payoutSecondaryReference: String? = null,
    val payoutOutcome: String? = null,
    val payoutSentAt: Instant? = null,
    val payoutRetryAt: Instant? = null,
    val mpesaReceipt: String? = null,
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

/** R7's input: the sale's quote and the M-Pesa number to pay, which must be the account's own verified number. */
data class StartOffRampCommand(
    val userId: UserId,
    val network: Network,
    val quoteId: QuoteId,
    val phone: PhoneNumber,
)

data class StartOnRampCommand(
    val userId: UserId,
    val network: Network,
    val quoteId: QuoteId,
    val phone: PhoneNumber,
)

/**
 * An order as its user sees it. [depositAddress] is where a sale's USDC goes (the merchant's float), shown while the
 * user still has to send it.
 */
data class OrderView(val order: Order, val merchantName: String) {
    val depositAddress: String? get() = order.destinationAddress.takeIf { order.direction == Direction.OFF_RAMP }
}

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
    /** The user's identity verification in full, so the app never guesses what to show. */
    val verification: KycView,
    /** Barred from the ramp, as a user or as the identity they verified with. */
    val blocked: Boolean,
    /** Whether buying is open to the user on this network (selling has its own switch). */
    val onRampOpen: Boolean = true,
    /** Selling: whether it is open to the user, whether anyone could take a sale now, and its limits. */
    val offRamp: OffRampStatus = OffRampStatus(open = false, merchantOnline = false, limits = limits),
    /** Buying's fee bands (2026-11-24); one band when the fee is flat. */
    val feeBands: List<RampFeeBand> = emptyList(),
)

/** R1's `offRamp`. */
data class OffRampStatus(
    val open: Boolean,
    val merchantOnline: Boolean,
    val limits: RampLimits,
    /** Selling's fee bands (2026-11-24); one band when the fee is flat. */
    val feeBands: List<RampFeeBand> = emptyList(),
)

/**
 * The gate's short answer, kept for apps built before [RampStatus.verification]: verified, not verified (whatever
 * the reason), or not asked for on this network.
 */
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
    val rateBasis: RateBasis? = null,
    /** The band's rate that priced it, and where that band starts (null for one flat band). */
    val feeBps: Int = 0,
    val feeBandFromMinor: Long? = null,
)

/** How an order's fee is shared: the merchant's part and Circaso's in the fiat currency, and Circaso's in USDC. */
data class FeeSplit(val merchantMinor: Long, val platformMinor: Long, val platformAssetMinor: Long)
