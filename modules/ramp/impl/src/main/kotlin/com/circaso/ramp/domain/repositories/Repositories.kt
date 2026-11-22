package com.circaso.ramp.domain.repositories

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.shared.Network
import com.circaso.shared.UserId
import java.time.Instant

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

    /** Whether the merchant has an order still waiting for its payment (their keys are still needed). */
    suspend fun hasAwaitingPayment(merchantId: MerchantId): Boolean

    /** The transition guard: moves the order to [to] only from one of [from], and answers the moved row or null. */
    suspend fun move(
        orderId: RampOrderId,
        to: OrderState,
        from: Set<OrderState>,
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

    /** Gives the float back; true for the caller that closed it. */
    suspend fun release(orderId: RampOrderId, at: Instant): Boolean

    /** The float was spent on the order; true for the caller that closed it. */
    suspend fun consume(orderId: RampOrderId, at: Instant): Boolean
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
}
