package com.circaso.ramp.domain.repositories

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.TestPayout
import com.circaso.ramp.domain.models.TestPayoutState
import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpState
import com.circaso.ramp.domain.models.TopUpTarget
import com.circaso.shared.Network
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

interface SettingsRepository {
    /** Every network's row there is. */
    suspend fun load(): Map<Network, RampSettings>

    /** Writes a network's first row; does nothing when it already has one. */
    suspend fun seed(settings: RampSettings)

    /**
     * Replaces a network's editable settings. With [expectedVersion], only when the row is still at it; the row
     * afterwards, or null when someone changed it meanwhile.
     */
    suspend fun update(
        settings: RampSettings,
        expectedVersion: Long?,
        by: String,
        at: Instant,
    ): RampSettings?

    /** Records the merchants' wallet set once; a set already recorded stays. */
    suspend fun setWalletSet(network: Network, walletSetId: String)
}

interface PreviewAccessRepository {
    suspend fun has(userId: UserId, network: Network): Boolean

    /** True when the row was added. */
    suspend fun grant(access: PreviewAccess): Boolean

    /** True when a row was removed. */
    suspend fun revoke(userId: UserId, network: Network): Boolean

    suspend fun list(network: Network, limit: Int): List<PreviewAccess>
}

interface QuoteRepository {
    suspend fun insert(quote: Quote)

    /** The quote while it lasts; an expired one is gone. */
    suspend fun find(quoteId: QuoteId, now: Instant): Quote?
}

/**
 * `ramp.orders`. **Every insert and every move also writes the order's timeline** (`ramp.order_events`: the state
 * entered, when, and the reason when there is one) in the same transaction, so a step can't happen without its time
 * being kept (decision 2026-11-23). The timeline is read through [OrderEventRepository].
 */
interface OrderRepository {
    /**
     * True when the order was added. False when its quote already made an order for this attempt, or the user already
     * has an open one: the caller tells them apart with [byQuote].
     */
    suspend fun insert(order: Order): Boolean

    suspend fun find(orderId: RampOrderId): Order?

    /** The quote's order: its latest attempt, when the provider refused earlier ones. */
    suspend fun byQuote(quoteId: QuoteId): Order?

    suspend fun openOf(userId: UserId): Order?

    /** The user's orders on [network], newest first, after [after]. */
    suspend fun list(
        userId: UserId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): List<Order>

    /** The merchant's orders on [network], newest first, after [after]. */
    suspend fun ofMerchant(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): List<Order>

    /**
     * Whether the merchant has an order their keys are still needed for: a purchase waiting for its payment, or a sale
     * that may still have to be paid out ([OrderState.NEEDS_KEYS]).
     */
    suspend fun needsKeys(merchantId: MerchantId): Boolean

    /**
     * Records the W4 transfer [transferId] funding a sale, once: an order that already has one keeps it (the caller
     * compares), and a transfer that funds another order is not taken. The order as it stands afterwards.
     */
    suspend fun attachTransfer(
        orderId: RampOrderId,
        transferId: UUID,
        at: Instant,
    ): Order?

    /** Sales on [network] whose transfer is known but not yet confirmed with the custodian, untouched since [before]. */
    suspend fun awaitingFunding(
        network: Network,
        before: Instant,
        limit: Int,
    ): List<Order>

    /** `FUNDED` sales on [network] whose next payout attempt is due at [now], oldest first. */
    suspend fun dueForPayout(
        network: Network,
        now: Instant,
        limit: Int,
    ): List<Order>

    /**
     * Staff asked for an order in `REVIEW` to be checked with its provider again (RO4): the app's process, which holds
     * the merchant's keys, does it on its next sweep. False when the order isn't in review.
     */
    suspend fun requestRecheck(orderId: RampOrderId, at: Instant): Boolean

    /** Orders staff asked to be checked again, oldest request first; and the request done. */
    suspend fun rechecks(limit: Int): List<Order>

    suspend fun clearRecheck(orderId: RampOrderId)

    /** Whether another order than [except] already carries the M-Pesa [receipt] (`orders_receipt_key`). */
    suspend fun receiptTaken(receipt: String, except: RampOrderId): Boolean

    /** The transition guard: moves the order to [to] only from one of [from], and answers the moved row or null. */
    suspend fun move(
        orderId: RampOrderId,
        to: OrderState,
        from: Set<OrderState>,
        patch: OrderPatch,
        at: Instant,
    ): Order?

    /**
     * Writes [patch] on an order that is still in [state], without moving it (no timeline row): what a step learned
     * while the order stays where it is (a payout's references, a refund's transaction). Null when it has moved on.
     */
    suspend fun annotate(
        orderId: RampOrderId,
        state: OrderState,
        patch: OrderPatch,
        at: Instant,
    ): Order?

    /** Moves an order that waits for its payment to `PAID` with the provider's [receipt]. */
    suspend fun markPaid(
        orderId: RampOrderId,
        receipt: String?,
        at: Instant,
    ): PaidOutcome

    /** Orders on [network] in one of [states] not touched since [before], oldest first. */
    suspend fun stale(
        network: Network,
        states: Set<OrderState>,
        before: Instant,
        limit: Int,
    ): List<Order>

    /** The latest [limit] purchases on [network] in [direction] (each quote's first order), newest first. */
    suspend fun recent(
        network: Network,
        direction: Direction,
        limit: Int,
    ): List<Order>

    /** Orders in `REVIEW` (on [network], or both when null), oldest first, after the id [after]. */
    suspend fun inReview(
        network: Network?,
        after: RampOrderId?,
        limit: Int,
    ): List<Order>
}

interface ReservationRepository {
    /** The sum of the merchant's open reservations on [network], in the asset's minor units. */
    suspend fun openTotal(merchantId: MerchantId, network: Network): Long

    suspend fun insert(order: Order, at: Instant)

    /**
     * Holds a sale's USDC in the merchant's float (`HOLD`, decision 2026-11-23): [amountMinor] the merchant's wallet
     * received for it, counted as reserved so it can be neither sold nor swept until the user is paid or refunded.
     */
    suspend fun hold(
        order: Order,
        amountMinor: Long,
        at: Instant,
    )

    /**
     * Holds the float again for an order staff resolved as paid after it was given back (a purchase paid after its
     * expiry); true when it was given back and is held again now.
     */
    suspend fun reopen(orderId: RampOrderId, at: Instant): Boolean

    /** Gives the float back; true for the caller that closed it. */
    suspend fun release(orderId: RampOrderId, at: Instant): Boolean

    /** The float was spent on the order; true for the caller that closed it. */
    suspend fun consume(orderId: RampOrderId, at: Instant): Boolean

    /**
     * Holds a withdrawal's amount in the merchant's float (`WITHDRAWAL`, keyed by the withdrawal id, 2026-11-24), in the
     * caller's transaction under the merchant's row lock: counted as reserved, so routing sees less free float at once.
     */
    suspend fun reserveWithdrawal(
        withdrawalId: UUID,
        merchantId: MerchantId,
        network: Network,
        amountMinor: Long,
        at: Instant,
    )

    /** Closes a withdrawal's hold: consumed when the chain confirmed it, released when it failed. */
    suspend fun closeWithdrawal(
        withdrawalId: UUID,
        consumed: Boolean,
        at: Instant,
    ): Boolean

    /** The part of [openTotal] that open withdrawals hold. */
    suspend fun withdrawingTotal(merchantId: MerchantId, network: Network): Long
}

/**
 * `ramp.payout_holds`: the shillings at a merchant's payout provider promised to their open sales, one per order (the
 * payout and its fee). Free shillings are the provider's payout balance less [openTotal]. Each closes once.
 */
interface PayoutHoldRepository {
    suspend fun insert(order: Order, at: Instant)

    /** What the merchant's open sales hold, across networks (one provider account pays for both). */
    suspend fun openTotal(merchantId: MerchantId): Long

    /** The sale ended without a payout: the shillings are free again. True for the caller that closed it. */
    suspend fun release(orderId: RampOrderId, at: Instant): Boolean

    /** The payout was made. True for the caller that closed it. */
    suspend fun consume(orderId: RampOrderId, at: Instant): Boolean
}

/** `ramp.test_payouts`. One test in flight per merchant (`test_payouts_open_key`). */
interface TestPayoutRepository {
    /** False when the merchant already has a test in flight. */
    suspend fun insert(test: TestPayout): Boolean

    suspend fun find(testId: UUID): TestPayout?

    suspend fun latest(merchantId: MerchantId): TestPayout?

    /** Replaces the test's row with [test] only while it is in one of [from]; true when it moved. */
    suspend fun move(test: TestPayout, from: Set<TestPayoutState>): Boolean

    /** Tests in flight untouched since [before], oldest first. */
    suspend fun open(before: Instant, limit: Int): List<TestPayout>
}

/** `ramp.topups`. One open top-up per merchant and wallet (`topups_open_key`). */
interface TopUpRepository {
    /** False when the merchant already has an open top-up of that wallet. */
    suspend fun insert(topUp: TopUp): Boolean

    suspend fun find(topUpId: UUID): TopUp?

    /** The merchant's open top-up of [target], if any. */
    suspend fun open(merchantId: MerchantId, target: TopUpTarget): TopUp?

    /** The merchant's latest top-ups, newest first. */
    suspend fun recent(merchantId: MerchantId, limit: Int): List<TopUp>

    /** Replaces the row with [topUp] only while it is in one of [from]; true when it moved. */
    suspend fun move(topUp: TopUp, from: Set<TopUpState>): Boolean

    /** Open top-ups untouched since [before], oldest first. */
    suspend fun stale(before: Instant, limit: Int): List<TopUp>

    /** Whether the merchant has an open top-up: its lookups need their unlocked keys. */
    suspend fun hasOpen(merchantId: MerchantId): Boolean
}

interface CommissionRepository {
    /**
     * True when the order's commission was recorded now. A commission that owes Circaso nothing in USDC is settled
     * as it is written: no sweep will ever name it.
     */
    suspend fun insert(
        order: Order,
        split: FeeSplit,
        at: Instant,
    ): Boolean

    /**
     * What the merchant's float on [network] still holds for Circaso: every commission no confirmed sweep has
     * collected, in USDC minor units. Subtracted from free float.
     */
    suspend fun owedTotal(merchantId: MerchantId, network: Network): Long

    /** How many trades the merchant has completed on [network], ever: one commission is one completed trade. */
    suspend fun completedCount(merchantId: MerchantId, network: Network): Int
}
