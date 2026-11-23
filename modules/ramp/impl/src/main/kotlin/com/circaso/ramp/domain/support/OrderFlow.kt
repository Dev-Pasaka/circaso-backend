package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.CollectionRequest
import com.circaso.integrations.api.CollectionState
import com.circaso.integrations.api.CollectionStatus
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MobileCollections
import com.circaso.ramp.api.OrderFailed
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PaidOutcome
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.PromptTransport
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

/** How sending an order's prompt ended, and with it what the caller may do next. */
sealed interface Prompted {
    /** The prompt is on the payer's phone. */
    data class Sent(val order: Order) : Prompted

    /**
     * The order is closed ([reason]) and nothing of it can be on the payer's phone: the next merchant may be asked.
     * [error] is the answer when nobody is left.
     */
    data class NotSent(val reason: String, val error: RampError) : Prompted

    /** The order is closed ([reason]) and nobody else may be asked for its quote; [error] is the answer. */
    data class Stopped(val reason: String, val error: RampError) : Prompted
}

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
    private val transport: PromptTransport,
    private val clock: Clock,
) {
    /**
     * Sends the M-Pesa prompt for a reserved order. A prompt that can't be sent closes the order and frees the float,
     * and the answer says what the caller may do next (decision 2026-11-23):
     *
     * - [Prompted.NotSent]: **nothing of this order can be on the payer's phone**, so the next merchant may be
     *   asked. The provider refused this merchant's request, the merchant's keys were gone, the request provably never
     *   left ([PromptTransport.neverSent]), or it may have left and the provider itself then said the prompt is over.
     *   The user is told nothing about the closed order: they never saw it.
     * - [Prompted.Stopped]: a prompt **may** be on the phone and the provider couldn't say, or the provider isn't
     *   offered here at all. Nobody else is asked for this quote: a buyer never gets a second prompt while the first
     *   may still be open.
     */
    suspend fun requestPayment(order: Order): Prompted {
        val keys = keysFor(order)
        val merchant = merchants.find(order.merchantId)
        val callbackKey = merchant?.callbackKey
        if (keys == null || callbackKey == null) {
            close(order, OrderState.FAILED, MERCHANT_OFFLINE, tellUser = false)
            return Prompted.NotSent(MERCHANT_OFFLINE, RampError.MerchantUnavailable)
        }
        val collections = providers.named(order.collectionsProvider)
        if (collections == null) {
            close(order, OrderState.FAILED, "provider_unavailable")
            return Prompted.Stopped("provider_unavailable", RampError.CollectionsUnavailable)
        }
        // The merchant's own callback address goes with every prompt made for them (decision 2026-11-22).
        val callbackUrl = Callbacks.url(settings.of(order.network).callbackBaseUrl, callbackKey)
        val request = CollectionRequest(order.payerPhone, order.fiat, order.orderId.toString(), callbackUrl)
        return when (val prompted = collections.requestPayment(keys, request)) {
            is Resource.Error -> {
                notPrompted(order, prompted, keys, collections)
            }

            is Resource.Success -> {
                // A prompt that went through shows whatever the provider refused before has been put right.
                if (merchant.paymentsIssue != null) merchants.setPaymentsIssue(order.merchantId, null, clock.instant())
                val patch =
                    OrderPatch(
                        providerReference = prompted.data.providerReference,
                        checkoutId = prompted.data.checkoutId,
                    )
                Prompted.Sent(waitingFor(order, patch))
            }
        }
    }

    /** `RESERVED → PAYMENT_REQUESTED`, and the user is told. */
    private suspend fun waitingFor(order: Order, patch: OrderPatch): Order {
        val from = setOf(OrderState.RESERVED)
        val waiting =
            orders.move(order.orderId, OrderState.PAYMENT_REQUESTED, from, patch, clock.instant()) ?: current(order)
        frames.orderUpdated(waiting)
        return waiting
    }

    private suspend fun notPrompted(
        order: Order,
        prompted: Resource.Error,
        keys: CollectionCredentials,
        collections: MobileCollections,
    ): Prompted =
        when (prompted.error) {
            // The merchant's prepaid balance at the provider is empty: every prompt of theirs will be refused until
            // they top it up, so the engine stops routing to them and they are told on the portal.
            is IntegrationError.ServiceBalanceLow -> {
                close(order, OrderState.FAILED, PROVIDER_BALANCE, tellUser = false)
                merchants.setPaymentsIssue(order.merchantId, PaymentsIssue.SERVICE_BALANCE, clock.instant())
                presence.goOffline(order.merchantId)
                alerts.stuck("merchant ${order.merchantId} prompts", PROVIDER_BALANCE)
                Prompted.NotSent(PROVIDER_BALANCE, RampError.MerchantUnavailable)
            }

            is IntegrationError.Rejected -> {
                close(order, OrderState.FAILED, PROMPT_REFUSED, tellUser = false)
                Prompted.NotSent(PROMPT_REFUSED, RampError.MerchantUnavailable)
            }

            else -> {
                unanswered(order, prompted, keys, collections)
            }
        }

    /**
     * The provider gave no answer. The prompt carries no idempotency key, so it is never repeated; what happens next
     * rests on whether a prompt can be on the phone.
     *
     * - The request never left (the circuit was open, no connection was made): nothing is on the phone, and the next
     *   merchant may be asked.
     * - It may have left (a timeout, a dropped answer, a server error): **the provider is asked about this order
     *   before anybody else is prompted**, by our own reference, since it never gave us one of its own. Only an
     *   answer that names this very order counts. "It failed": the prompt is over, the next merchant may be asked.
     *   "It is queued" or "it was paid": the prompt went out after all, and the order carries on as if it had been
     *   answered. No such answer (the provider is still out of reach, or can't look a prompt up by our reference):
     *   nobody knows, so the order is closed, nobody else is asked, and the people on call are told that a prompt
     *   may be open with no order behind it.
     */
    private suspend fun unanswered(
        order: Order,
        prompted: Resource.Error,
        keys: CollectionCredentials,
        collections: MobileCollections,
    ): Prompted {
        if (transport.neverSent(prompted)) {
            close(order, OrderState.FAILED, PROVIDER_UNREACHABLE, tellUser = false)
            return Prompted.NotSent(PROVIDER_UNREACHABLE, RampError.CollectionsUnavailable)
        }
        val reference = order.orderId.toString()
        return when (promptState(reference, keys, collections)) {
            CollectionState.FAILED -> {
                close(order, OrderState.FAILED, PROMPT_FAILED, tellUser = false)
                Prompted.NotSent(PROMPT_FAILED, RampError.CollectionsUnavailable)
            }

            CollectionState.QUEUED, CollectionState.SUCCESS -> {
                Prompted.Sent(waitingFor(order, OrderPatch(providerReference = reference)))
            }

            null -> {
                close(order, OrderState.FAILED, PROMPT_UNCONFIRMED)
                alerts.stuck("order ${order.orderId} prompt", PROMPT_UNCONFIRMED)
                Prompted.Stopped(PROMPT_UNCONFIRMED, RampError.CollectionsUnavailable)
            }
        }
    }

    /** What the provider says about the prompt we named [reference]; null unless its answer names that reference. */
    private suspend fun promptState(
        reference: String,
        keys: CollectionCredentials,
        collections: MobileCollections,
    ): CollectionState? {
        val status = (collections.paymentStatus(keys, reference) as? Resource.Success)?.data ?: return null
        return status.state.takeIf { status.reference == reference }
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
        /** Why an order ended when this node no longer held its merchant's keys as the prompt had to go out. */
        const val MERCHANT_OFFLINE = "merchant_offline"

        /** Why an order ended when the prompt's request provably never reached the provider. */
        const val PROVIDER_UNREACHABLE = "provider_unreachable"

        /** Why an order ended when the provider gave no answer and then said itself that the prompt was over. */
        const val PROMPT_FAILED = "prompt_failed"

        /** Why an order ended when the provider gave no answer and couldn't say whether a prompt went out. */
        const val PROMPT_UNCONFIRMED = "prompt_unconfirmed"

        /** Why an order ended when its merchant's provider refused the prompt for their service balance. */
        const val PROVIDER_BALANCE = "provider_balance"

        /** Why an order ended when its merchant's provider refused the prompt for any other reason. */
        const val PROMPT_REFUSED = "prompt_refused"

        val REVIEWABLE =
            setOf(OrderState.PAYMENT_REQUESTED, OrderState.EXPIRED, OrderState.PAID, OrderState.RELEASING)
    }
}
