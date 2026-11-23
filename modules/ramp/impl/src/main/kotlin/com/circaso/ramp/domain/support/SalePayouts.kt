package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.MobileCollections
import com.circaso.integrations.api.PayoutLookup
import com.circaso.integrations.api.PayoutReference
import com.circaso.integrations.api.PayoutRejection
import com.circaso.integrations.api.PayoutRequest
import com.circaso.integrations.api.PayoutResult
import com.circaso.integrations.api.PayoutStatus
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderPatch
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PayoutIssue
import com.circaso.ramp.domain.ports.FrameSender
import com.circaso.ramp.domain.ports.PayoutJournal
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * A sale's payout (decision 2026-11-23), in the app's process that holds the merchant's unlocked keys. The rule that
 * makes it safe: **a payout is sent once, and only the provider's lookup decides what became of it.**
 *
 * - **Sent once.** The attempt (its number, our reference, `SENDING`) is written with the move to `PAYING_OUT`
 *   **before** the provider is called, so a crash, a timeout or a cancelled call leaves a sale that says a payout may
 *   be out. The client never retries ([MobileCollections.sendPayout]), and nothing here sends again unless the provider
 *   said the first one was **not** sent (refused, no balance, never reached) or **failed** (its lookup said so).
 * - **What the answer allows** ([PayoutResult]): `Accepted` waits for the lookup; `Unknown` is looked up, never
 *   resent; `Rejected`, `InsufficientBalance` and `Unavailable` sent nothing, so the sale goes back to `FUNDED` and the
 *   **same merchant** is asked again later (the user's USDC is in that merchant's float: no other merchant can pay
 *   it) until the payout window runs out, and then the USDC is refunded.
 * - **Only the lookup decides** ([PayoutStatus]): `Paid`, with the amount and our reference checked where it gives
 *   them, completes the sale; `Failed` allows one more attempt within the window, then a refund; `NotFound` sends the
 *   sale to a person (the provider denying its own reference, or a payout that may have gone out and can't be found
 *   by ours); `Pending` and `Unknown` wait. The provider's callback only rings for a lookup.
 */
class SalePayouts(
    private val orders: OrderRepository,
    private val merchants: MerchantRepository,
    private val flow: SaleFlow,
    private val providers: CollectionProviders,
    private val sessions: TradingSessions,
    private val settings: SettingsBook,
    private val fees: PayoutFees,
    private val frames: FrameSender,
    private val alerts: RampAlerts,
    private val clock: Clock,
    private val journal: PayoutJournal,
) {
    /** Sends the payout of a `FUNDED` sale when it is due and this node can; otherwise the sale is left as it is. */
    suspend fun pay(order: Order): Order {
        val now = clock.instant()
        if (order.state != OrderState.FUNDED || order.payoutRetryAt?.isAfter(now) == true) return order
        val merchant = merchants.find(order.merchantId)
        val keys = keysFor(order)
        val collections = providers.named(order.collectionsProvider)
        val accountId = merchant?.payoutAccountId
        val callbackKey = merchant?.callbackKey
        if (merchant == null || keys == null || collections == null || accountId == null || callbackKey == null) {
            return unableToSend(order, merchant, keys, collections, now)
        }
        val attempt = order.payoutAttempts + 1
        val reference = referenceOf(order.orderId, attempt)
        val tariff = fees.of(settings.of(order.network), order.fiat.amountMinor)
        val patch =
            OrderPatch(
                payoutAttempts = attempt,
                payoutReference = reference,
                payoutOutcome = SENDING,
                payoutSentAt = now,
                providerFeeActualMinor = tariff?.feeMinor,
            )
        // Written before the call: from here on the sale says a payout may be out.
        val sending = orders.move(order.orderId, OrderState.PAYING_OUT, setOf(OrderState.FUNDED), patch, now)
        if (sending == null) return flow.current(order)
        frames.orderUpdated(sending)
        val callbackUrl = Callbacks.url(settings.of(order.network).callbackBaseUrl, callbackKey)
        val request = PayoutRequest(order.payerPhone, order.fiat, reference, callbackUrl, accountId)
        val result = send(collections, keys, request)
        journal.step("payout sent", "${order.orderId}#$attempt", result::class.simpleName.orEmpty())
        return sent(sending, merchant, result)
    }

    /**
     * Asks the provider what became of the latest payout of a `PAYING_OUT` sale (or of a sale in `REVIEW` that has
     * one, when its callback rings or staff re-check), and applies the answer. Without the merchant's keys on this
     * node, or the provider out of reach, nothing changes.
     */
    suspend fun lookUp(order: Order): Looked {
        val reference = order.payoutReference ?: return Looked(order, null)
        if (order.state != OrderState.PAYING_OUT && order.state != OrderState.REVIEW) return Looked(order, null)
        val keys = keysFor(order) ?: return Looked(order, null)
        val collections = providers.named(order.collectionsProvider) ?: return Looked(order, null)
        val status = lookUp(collections, keys, PayoutLookup(reference, order.payoutProviderReference))
        journal.step("payout looked up", order.orderId.toString(), said(status))
        val after =
            if (order.state == OrderState.REVIEW) {
                inReview(order, status)
            } else {
                applied(order, status)
            }
        return Looked(after, said(status))
    }

    /** A sale after [lookUp], and what the provider said (null when it couldn't be asked). */
    data class Looked(val order: Order, val said: String?)

    /** The provider was asked, and nothing may rest on a guess: everything it can answer is handled here. */
    private suspend fun applied(order: Order, status: PayoutStatus): Order {
        val now = clock.instant()
        return when (status) {
            is PayoutStatus.Paid -> {
                if (matches(order, status)) {
                    flow.complete(order, status.providerReceipt, setOf(OrderState.PAYING_OUT))
                } else {
                    flow.review(order, PAYOUT_MISMATCH)
                }
            }

            is PayoutStatus.Failed -> {
                failed(order, now)
            }

            is PayoutStatus.NotFound -> {
                // The provider denies its own reference, or a payout that may have gone out can't be found by ours:
                // nobody can say it wasn't paid, so a person looks before anything is sent or refunded.
                val reason = if (status.by == PayoutReference.PROVIDER) PAYOUT_NOT_FOUND else PAYOUT_UNCONFIRMED
                flow.review(order, reason)
            }

            PayoutStatus.Pending, PayoutStatus.Unknown -> {
                stillOpen(order, now)
            }
        }
    }

    /** A sale with staff: only a matching `Paid` moves it (completed); anything else stays for the person. */
    private suspend fun inReview(order: Order, status: PayoutStatus): Order =
        if (status is PayoutStatus.Paid && matches(order, status)) {
            flow.complete(order, status.providerReceipt, setOf(OrderState.REVIEW))
        } else {
            order
        }

    /**
     * The provider said this attempt failed: nothing was paid. One more attempt is allowed within the payout window
     * (a second failure, or the window's end, refunds the USDC).
     */
    private suspend fun failed(order: Order, now: Instant): Order {
        val failures = order.payoutFailures + 1
        val outcome = "$FAILED_PREFIX:${order.payoutAttempts}"
        if (failures <= MAX_FAILURES && !windowOver(order, now)) {
            val patch = OrderPatch(payoutOutcome = outcome, payoutFailures = failures, payoutRetryAt = now)
            return again(order, patch)
        }
        val patch = OrderPatch(payoutOutcome = outcome, payoutFailures = failures)
        return flow.refund(order, SaleFlow.PAYOUT_FAILED, setOf(OrderState.PAYING_OUT), patch) ?: flow.current(order)
    }

    /** Still pending or unreadable: wait, and once the payout window is over, hand it to a person. */
    private suspend fun stillOpen(order: Order, now: Instant): Order {
        val sentAt = order.payoutSentAt ?: order.updatedAt
        val window = settings.of(order.network).payoutWindow
        return if (sentAt.plus(window).isBefore(now)) flow.review(order, PAYOUT_UNRESOLVED) else order
    }

    /** What sending answered. Every case is one the provider spoke to: nothing here is a guess. */
    private suspend fun sent(
        order: Order,
        merchant: Merchant,
        result: PayoutResult,
    ): Order {
        val now = clock.instant()
        return when (result) {
            is PayoutResult.Accepted -> {
                if (merchant.payoutIssue != null) merchants.setPayoutIssue(merchant.merchantId, null, now)
                val patch =
                    OrderPatch(
                        payoutOutcome = ACCEPTED,
                        payoutProviderReference = result.receipt.providerReference,
                        payoutSecondaryReference = result.receipt.secondaryReference,
                    )
                orders.annotate(order.orderId, OrderState.PAYING_OUT, patch, now) ?: flow.current(order)
            }

            PayoutResult.Unknown -> {
                // It may have gone out: never sent again, never refunded on this. The lookup decides.
                val unknown =
                    orders.annotate(order.orderId, OrderState.PAYING_OUT, OrderPatch(payoutOutcome = UNKNOWN), now)
                lookUp(unknown ?: flow.current(order)).order
            }

            is PayoutResult.Rejected -> {
                rejected(order, merchant, result.reason, now)
            }

            PayoutResult.InsufficientBalance -> {
                merchants.setPayoutIssue(merchant.merchantId, PayoutIssue.PAYOUT_BALANCE, now)
                alerts.stuck("merchant ${merchant.merchantId} payouts", PAYOUT_BALANCE)
                notSent(order, PAYOUT_BALANCE, SaleFlow.PAYOUT_FAILED, REFUSED_RETRY, now)
            }

            PayoutResult.Unavailable -> {
                notSent(order, UNAVAILABLE, SaleFlow.PAYOUT_UNAVAILABLE, UNAVAILABLE_RETRY, now)
            }
        }
    }

    /**
     * The provider refused the payout for good: nothing was sent. A refusal of the request itself (an amount it won't
     * carry, a provider that pays nothing out) is refunded at once: asking again can't change it. A refusal of the
     * merchant (their credentials, their account, a reason not told apart) marks them, so no sale is routed to them,
     * and is retried within the window in case they fix it.
     */
    private suspend fun rejected(
        order: Order,
        merchant: Merchant,
        reason: PayoutRejection,
        now: Instant,
    ): Order {
        val outcome = "$NOT_SENT_PREFIX:rejected:${reason.name}"
        if (reason == PayoutRejection.AMOUNT_BELOW_COST || reason == PayoutRejection.NOT_SUPPORTED) {
            val patch = OrderPatch(payoutOutcome = outcome)
            return flow.refund(order, SaleFlow.PAYOUT_REJECTED, setOf(OrderState.PAYING_OUT), patch)
                ?: flow.current(order)
        }
        merchants.setPayoutIssue(merchant.merchantId, PayoutIssue.PAYOUT_REFUSED, now)
        alerts.stuck("merchant ${merchant.merchantId} payouts", "payout_refused:${reason.name}")
        return notSent(order, "rejected:${reason.name}", SaleFlow.PAYOUT_FAILED, REFUSED_RETRY, now)
    }

    /**
     * Nothing was sent: back to `FUNDED`, to be tried again with the same merchant after [retryIn], or, once the
     * payout window is over, refunded for [refundReason].
     */
    private suspend fun notSent(
        order: Order,
        why: String,
        refundReason: String,
        retryIn: Duration,
        now: Instant,
    ): Order {
        val outcome = "$NOT_SENT_PREFIX:$why"
        if (windowOver(order, now)) {
            val patch = OrderPatch(payoutOutcome = outcome)
            return flow.refund(order, refundReason, setOf(OrderState.PAYING_OUT), patch) ?: flow.current(order)
        }
        return again(order, OrderPatch(payoutOutcome = outcome, payoutRetryAt = now.plus(retryIn)))
    }

    /** `PAYING_OUT → FUNDED`: provably nothing is out, and the payout will be tried again. */
    private suspend fun again(order: Order, patch: OrderPatch): Order {
        val back = orders.move(order.orderId, OrderState.FUNDED, setOf(OrderState.PAYING_OUT), patch, clock.instant())
        back?.let { frames.orderUpdated(it) }
        return back ?: flow.current(order)
    }

    /**
     * A `FUNDED` sale this node can't pay now (the merchant's keys aren't here, their payout account isn't set, the
     * provider isn't offered): it waits, and once the payout window is over its USDC is refunded.
     */
    private suspend fun unableToSend(
        order: Order,
        merchant: Merchant?,
        keys: CollectionCredentials?,
        collections: MobileCollections?,
        now: Instant,
    ): Order {
        if (!windowOver(order, now)) return order
        val reason =
            when {
                collections == null -> SaleFlow.PAYOUT_UNAVAILABLE
                keys == null -> SaleFlow.PAYOUT_MISSED
                merchant?.payoutAccountId == null -> SaleFlow.PAYOUT_FAILED
                else -> SaleFlow.PAYOUT_MISSED
            }
        return flow.refund(order, reason, setOf(OrderState.FUNDED)) ?: flow.current(order)
    }

    private suspend fun windowOver(order: Order, now: Instant): Boolean {
        val since = order.fundedAt ?: order.updatedAt
        return since.plus(settings.of(order.network).payoutWindow).isBefore(now)
    }

    /** Where the provider gives the amount or our reference, they must be this sale's. */
    private fun matches(order: Order, paid: PayoutStatus.Paid): Boolean =
        (paid.amount == null || paid.amount == order.fiat) &&
            (paid.reference == null || paid.reference == order.payoutReference)

    /** The merchant's keys, when this node holds them and they are an account at the sale's provider. */
    private fun keysFor(order: Order): CollectionCredentials? =
        sessions.of(order.merchantId)?.takeIf { it.provider == order.collectionsProvider }?.credentials

    /** A call that was cancelled or threw is `Unknown`: the request may have left. */
    private suspend fun send(
        collections: MobileCollections,
        keys: CollectionCredentials,
        request: PayoutRequest,
    ): PayoutResult =
        try {
            collections.sendPayout(keys, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            journal.step("payout call failed", request.reference, e::class.simpleName.orEmpty())
            PayoutResult.Unknown
        }

    private suspend fun lookUp(
        collections: MobileCollections,
        keys: CollectionCredentials,
        lookup: PayoutLookup,
    ): PayoutStatus =
        try {
            collections.lookUpPayout(keys, lookup)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            journal.step("payout lookup failed", lookup.reference, e::class.simpleName.orEmpty())
            PayoutStatus.Unknown
        }

    companion object {
        /** A sale's payout attempts: our reference is the order id, then `<id>-2`, `<id>-3`… */
        fun referenceOf(orderId: RampOrderId, attempt: Int): String =
            if (attempt <= 1) orderId.toString() else "$orderId-$attempt"

        /** The sale a payout reference belongs to, whichever attempt it was. */
        fun orderOf(reference: String): RampOrderId? = RampOrderId.parse(reference.trim().take(UUID_LENGTH))

        /** What a lookup said, in our words, for staff and the log. */
        fun said(status: PayoutStatus): String =
            when (status) {
                is PayoutStatus.Paid -> "PAID"
                is PayoutStatus.Failed -> "FAILED"
                is PayoutStatus.NotFound -> "NOT_FOUND_BY_${status.by.name}"
                PayoutStatus.Pending -> "PENDING"
                PayoutStatus.Unknown -> "UNKNOWN"
            }

        const val SENDING = "SENDING"
        const val ACCEPTED = "ACCEPTED"
        const val UNKNOWN = "UNKNOWN"
        const val NOT_SENT_PREFIX = "NOT_SENT"
        const val FAILED_PREFIX = "FAILED"
        const val PAYOUT_BALANCE = "payout_balance"
        const val UNAVAILABLE = "unavailable"

        /** The provider's lookup said paid, but not this amount or reference. */
        const val PAYOUT_MISMATCH = "payout_mismatch"

        /** The provider denies the reference it gave us. */
        const val PAYOUT_NOT_FOUND = "payout_not_found"

        /** A payout that may have gone out can't be found by our reference. */
        const val PAYOUT_UNCONFIRMED = "payout_unconfirmed"

        /** Still pending (or unreadable) when the payout window ran out. */
        const val PAYOUT_UNRESOLVED = "payout_unresolved"

        /** After a failure the provider reported, one more attempt; a second failure refunds. */
        const val MAX_FAILURES = 1

        /** A send still `SENDING` after this never answered: it is looked up as one that may have gone out. */
        val SENDING_STALE: Duration = Duration.ofMinutes(2)

        private val UNAVAILABLE_RETRY: Duration = Duration.ofSeconds(30)
        private val REFUSED_RETRY: Duration = Duration.ofMinutes(2)
        private const val UUID_LENGTH = 36
    }
}
