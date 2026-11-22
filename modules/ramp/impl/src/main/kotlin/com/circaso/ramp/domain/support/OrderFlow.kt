package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.CollectionRequest
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.OrderFailed
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.ramp.domain.ports.RampOutbox
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.envelope
import java.time.Clock

/**
 * The steps an on-ramp order takes in the app's processes after it is reserved, shared by every way in (the request,
 * the provider's callback, the sweep job), so each behaves the same whoever runs it. Every step is a transition
 * guard: running one twice, or two at once, applies it once.
 *
 * The rule that makes this module safe to move money (decision 2026-11-22): **an order becomes `PAID` only after the
 * provider itself says the payment succeeded and it matches the order**. A callback is a doorbell; a guess is never
 * acted on. When the provider can't be asked (it is down, or this node doesn't hold the merchant's keys), nothing
 * changes. The USDC itself is sent by the ops process, which alone holds the signing secret ([Releases]).
 */
class OrderFlow(
    private val orders: OrderRepository,
    private val reservations: ReservationRepository,
    private val merchants: MerchantRepository,
    private val transactor: Transactor,
    private val outbox: RampOutbox,
    private val bus: EventBus,
    private val frames: FrameSender,
    private val counters: RampCounters,
    private val alerts: RampAlerts,
    private val providers: CollectionProviders,
    private val sessions: TradingSessions,
    private val settings: SettingsBook,
    private val presence: Presence,
    private val clock: Clock,
) {
    /**
     * Sends the M-Pesa prompt for a reserved order. A prompt that can't be sent closes the order and frees the float.
     *
     * The two ways it can't be sent are told apart. The provider **out of reach** (`ramp.collections_unavailable`):
     * whether a prompt went out is unknown, so nothing more is tried. The provider **refusing this merchant's
     * request** (`ramp.merchant_unavailable`): nothing was sent, so the caller may ask the next merchant; the user is
     * told nothing about the order that was closed, because they never saw it.
     */
    suspend fun requestPayment(order: Order): Resource<Order> {
        val keys = keysFor(order)
        val merchant = merchants.find(order.merchantId)
        val callbackKey = merchant?.callbackKey
        if (keys == null || callbackKey == null) {
            close(order, OrderState.FAILED, "merchant_offline")
            return Resource.error(RampError.NoMerchantAvailable)
        }
        val collections = providers.named(order.collectionsProvider)
        if (collections == null) {
            close(order, OrderState.FAILED, "provider_unavailable")
            return Resource.error(RampError.CollectionsUnavailable)
        }
        // The merchant's own callback address goes with every prompt made for them (decision 2026-11-22).
        val callbackUrl = Callbacks.url(settings.of(order.network).callbackBaseUrl, callbackKey)
        val request = CollectionRequest(order.payerPhone, order.fiat, order.orderId.toString(), callbackUrl)
        return when (val prompted = collections.requestPayment(keys, request)) {
            is Resource.Error -> {
                notPrompted(order, prompted)
            }

            is Resource.Success -> {
                // A prompt that went through shows whatever the provider refused before has been put right.
                if (merchant?.paymentsIssue != null) merchants.setPaymentsIssue(order.merchantId, null, clock.instant())
                val patch =
                    OrderPatch(
                        providerReference = prompted.data.providerReference,
                        checkoutId = prompted.data.checkoutId,
                    )
                val from = setOf(OrderState.RESERVED)
                val waiting =
                    orders.move(order.orderId, OrderState.PAYMENT_REQUESTED, from, patch, clock.instant())
                        ?: current(order)
                frames.orderUpdated(waiting)
                Resource.success(waiting)
            }
        }
    }

    private suspend fun notPrompted(order: Order, prompted: Resource.Error): Resource<Order> =
        when (prompted.error) {
            // The merchant's prepaid balance at the provider is empty: every prompt of theirs will be refused until
            // they top it up, so the engine stops routing to them and they are told on the portal.
            is IntegrationError.ServiceBalanceLow -> {
                close(order, OrderState.FAILED, PROVIDER_BALANCE, tellUser = false)
                merchants.setPaymentsIssue(order.merchantId, PaymentsIssue.SERVICE_BALANCE, clock.instant())
                presence.goOffline(order.merchantId)
                alerts.stuck("merchant ${order.merchantId} prompts", PROVIDER_BALANCE)
                Resource.error(RampError.MerchantUnavailable, prompted.cause)
            }

            is IntegrationError.Rejected -> {
                close(order, OrderState.FAILED, PROMPT_REFUSED, tellUser = false)
                Resource.error(RampError.MerchantUnavailable, prompted.cause)
            }

            // The prompt carries no idempotency key, so it is never repeated here; the user starts again.
            else -> {
                close(order, OrderState.FAILED, "prompt_failed")
                Resource.error(RampError.CollectionsUnavailable, prompted.cause)
            }
        }

    /**
     * Asks the provider what happened to the order's payment and applies the answer: nothing while it is still
     * queued, `FAILED` when it failed, `PAID` when it succeeded and matches, `REVIEW` when it succeeded but doesn't
     * match. Without the merchant's keys, or when the provider can't be reached, nothing changes and [Checked.asked]
     * is false: the caller must not conclude anything from the order still waiting.
     */
    suspend fun verify(order: Order): Checked {
        if (order.state != OrderState.PAYMENT_REQUESTED && order.state != OrderState.EXPIRED) {
            return Checked(order, false)
        }
        val reference = order.providerReference ?: return Checked(order, false)
        val keys = keysFor(order) ?: return Checked(order, false)
        // The provider that prompted it, whatever the settings name now: a switch mid-order never asks the wrong one.
        val collections = providers.named(order.collectionsProvider) ?: return Checked(order, false)
        val status =
            when (val asked = collections.paymentStatus(keys, reference)) {
                is Resource.Error -> return Checked(order, false)
                is Resource.Success -> asked.data
            }
        val after =
            when (status.state) {
                CollectionState.QUEUED -> order
                CollectionState.FAILED -> close(order, OrderState.FAILED, "payment_failed") ?: current(order)
                CollectionState.SUCCESS -> settle(order, status)
            }
        return Checked(after, asked = true)
    }

    /** The merchant's keys when this node holds them **and** they are an account at the order's provider. */
    private fun keysFor(order: Order): CollectionCredentials? =
        sessions.of(order.merchantId)?.takeIf { it.provider == order.collectionsProvider }?.credentials

    /** An order after [verify], and whether the provider actually answered. */
    data class Checked(val order: Order, val asked: Boolean)

    private suspend fun settle(order: Order, status: CollectionStatus): Order =
        when {
            !matches(order, status) -> {
                review(order, "payment_mismatch")
            }

            // The float went back when the order expired; a person decides between a release and a refund.
            order.state == OrderState.EXPIRED -> {
                review(order, "paid_after_expiry")
            }

            else -> {
                when (val outcome = orders.markPaid(order.orderId, status.receipt, clock.instant())) {
                    is PaidOutcome.Moved -> outcome.order.also { frames.orderUpdated(it) }
                    PaidOutcome.AlreadyMoved -> current(order)
                    PaidOutcome.ReceiptUsed -> review(order, "receipt_reused")
                }
            }
        }

    /**
     * The provider's answer was asked for by the reference it gave **this order's** prompt, so it is about this
     * prompt; whatever else it reports (the amount, the payer, our own reference) must agree with the order too.
     */
    private fun matches(order: Order, status: CollectionStatus): Boolean =
        (status.amount == null || status.amount == order.fiat) &&
            (status.payer == null || status.payer == order.payerPhone) &&
            (status.reference == null || status.reference == order.orderId.toString())

    /**
     * Ends an order that was never paid ([to] is `FAILED`, `EXPIRED` or `CANCELLED`) and gives its float back, in one
     * transaction. Null when the order is already past waiting for its payment. [tellUser] is false for an order the
     * user never saw (its merchant's provider refused the prompt and the next merchant is asked).
     */
    suspend fun close(
        order: Order,
        to: OrderState,
        reason: String,
        tellUser: Boolean = true,
    ): Order? {
        val now = clock.instant()
        val failed = OrderFailed(order.orderId, order.userId, order.direction.name, reason, order.network.name)
        val event = bus.envelope(failed)
        val closed =
            transactor.run("orders.close") {
                val patch = OrderPatch(failureReason = reason)
                val moved = orders.move(order.orderId, to, OrderState.AWAITING_PAYMENT, patch, now)
                if (moved != null) {
                    reservations.release(order.orderId, now)
                    outbox.write(event)
                }
                moved
            } ?: return null
        bus.publish(event)
        counters.giveBackDaily(order.userId, order.network, Periods.day(order.createdAt), order.fiat.amountMinor)
        if (tellUser) frames.orderUpdated(closed)
        return closed
    }

    /** Stops the order for a person to look at. Its float stays reserved: nothing more moves by itself. */
    suspend fun review(order: Order, reason: String): Order {
        val patch = OrderPatch(failureReason = reason)
        val moved =
            orders.move(order.orderId, OrderState.REVIEW, REVIEWABLE, patch, clock.instant())
                ?: return current(order)
        alerts.needsReview(moved, reason)
        frames.orderUpdated(moved)
        return moved
    }

    private suspend fun current(order: Order): Order = orders.find(order.orderId) ?: order

    companion object {
        /** Why an order ended when its merchant's provider refused the prompt for their service balance. */
        const val PROVIDER_BALANCE = "provider_balance"

        /** Why an order ended when its merchant's provider refused the prompt for any other reason. */
        const val PROMPT_REFUSED = "prompt_refused"

        val REVIEWABLE =
            setOf(OrderState.PAYMENT_REQUESTED, OrderState.EXPIRED, OrderState.PAID, OrderState.RELEASING)
    }
}
