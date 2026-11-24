package com.circaso.ramp.domain.support

import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Instant

/** A purchase about to be routed: what every attempt of it shares. The day's amount is already counted for it. */
class Purchase(
    val command: StartOnRampCommand,
    val quote: Quote,
    val settings: RampSettings,
    /** The buyer's own wallet on the quote's chain: where the USDC goes. */
    val address: String,
    val now: Instant,
)

/**
 * Routes a purchase and tries the merchants in the engine's order, up to the engine settings' attempts (decision
 * 2026-11-23). A merchant who can't take it after all (under their row lock) is passed over without counting as an
 * attempt. When a prompt can't be sent and nothing of it can be on the phone, the order is closed and the next
 * merchant is asked with a new order for the same quote; when a prompt may be on the phone, nobody else is asked
 * ([Prompted]). The day's limit is counted once: a closed attempt gives its amount back, so each new attempt takes
 * it again. Every step goes into the routing's log row.
 */
class PurchaseRouting(
    private val engine: Engine,
    private val flow: OrderFlow,
    private val counters: RampCounters,
    private val clock: Clock,
) {
    /**
     * What the attempts so far left behind: the steps for the routing log, how many merchants were really tried,
     * whether the day's amount is still counted for this purchase, and the answer if nobody takes it ([answer]: the
     * last merchant's reason, so a refusal by everyone is `ramp.merchant_unavailable` and nobody at all is
     * `ramp.no_merchant_available`).
     */
    private class Trail(var answer: RampError) {
        val steps = mutableListOf<RoutingStep>()
        var attempts = 0
        var holdsDay = true
    }

    /** The order whose prompt is on the buyer's phone, or why nobody took the purchase. */
    suspend fun place(purchase: Purchase): Resource<Order> {
        val quote = purchase.quote
        val need = RoutingOrder(Direction.ON_RAMP, quote.fiat.amountMinor, quote.assetMinor)
        val command = RoutingCommand(quote.quoteId, quote.network, quote.blockchain, need)
        val routing = engine.route(command, purchase.settings)
        val trail = Trail(if (routing.custodyDown) RampError.CustodyUnavailable else RampError.NoMerchantAvailable)
        for (candidate in routing.ranked) {
            if (trail.attempts >= routing.engine.settings.maxAttempts) break
            if (!trail.holdsDay && !takeDay(purchase)) {
                engine.conclude(routing, RoutingOutcome.STOPPED, trail.steps)
                return Resource.error(RampError.LimitExceeded)
            }
            trail.holdsDay = true
            val ended = attempt(routing, candidate, purchase, trail)
            if (ended != null) return ended
        }
        if (trail.holdsDay) giveDayBack(purchase)
        val outcome = if (routing.ranked.isEmpty()) RoutingOutcome.NO_CANDIDATE else RoutingOutcome.EXHAUSTED
        engine.conclude(routing, outcome, trail.steps)
        return Resource.error(trail.answer)
    }

    /** One merchant: reserve, then prompt. The purchase's answer when it ends here, or null to try the next. */
    private suspend fun attempt(
        routing: Routing,
        candidate: Candidate,
        purchase: Purchase,
        trail: Trail,
    ): Resource<Order>? {
        val merchantId = candidate.merchant.merchantId
        val reserved =
            engine.reserve(routing, candidate, purchase.settings) { picked ->
                order(purchase, picked, trail.attempts + 1)
            }
        val order =
            when (reserved) {
                is Resource.Success -> {
                    reserved.data
                }

                is Resource.Error -> {
                    trail.steps += RoutingStep(merchantId, null, RESERVE_FAILED, clock.instant())
                    if (reserved.error == RampError.NoMerchantAvailable) return null
                    giveDayBack(purchase)
                    engine.conclude(routing, RoutingOutcome.STOPPED, trail.steps)
                    return reserved
                }
            }
        trail.attempts += 1
        return when (val prompted = flow.requestPayment(order)) {
            is Prompted.Sent -> {
                counters.countPrompt(purchase.command.userId)
                trail.steps += RoutingStep(merchantId, order.orderId, PROMPTED, clock.instant())
                engine.conclude(routing, RoutingOutcome.ROUTED, trail.steps, prompted.order)
                Resource.success(prompted.order)
            }

            is Prompted.NotSent -> {
                // Closing the order gave the day's amount back; the next attempt takes it again.
                trail.steps += RoutingStep(merchantId, order.orderId, prompted.reason, clock.instant())
                trail.answer = prompted.error
                trail.holdsDay = false
                null
            }

            is Prompted.Stopped -> {
                trail.steps += RoutingStep(merchantId, order.orderId, prompted.reason, clock.instant())
                engine.conclude(routing, RoutingOutcome.STOPPED, trail.steps)
                Resource.error(prompted.error)
            }
        }
    }

    private suspend fun takeDay(purchase: Purchase): Boolean =
        counters.reserveDaily(
            purchase.command.userId,
            purchase.quote.network,
            Periods.day(purchase.now),
            purchase.quote.fiat.amountMinor,
            purchase.settings.dailyLimitMinor,
        )

    private suspend fun giveDayBack(purchase: Purchase) {
        counters.giveBackDaily(
            purchase.command.userId,
            purchase.quote.network,
            Periods.day(purchase.now),
            purchase.quote.fiat.amountMinor,
        )
    }

    private fun order(
        purchase: Purchase,
        candidate: Candidate,
        attempt: Int,
    ): Order {
        val quote = purchase.quote
        val now = purchase.now
        return Order(
            orderId = RampOrderId.new(),
            userId = purchase.command.userId,
            merchantId = candidate.merchant.merchantId,
            quoteId = quote.quoteId,
            network = quote.network,
            direction = Direction.ON_RAMP,
            state = OrderState.RESERVED,
            blockchain = quote.blockchain,
            fiat = quote.fiat,
            assetMinor = quote.assetMinor,
            rate = quote.rate,
            rateBasis = quote.rateBasis,
            feeMinor = quote.feeMinor,
            feeBps = quote.feeBps,
            merchantShareBps = quote.merchantShareBps,
            feeBandFromMinor = quote.feeBandFromMinor,
            // The total prompted carries PayHero's fee (2026-11-24): the order keeps it and where it came from.
            providerFeeMinor = quote.providerFeeMinor,
            providerFeeSource = quote.providerFeeSource,
            collectionsProvider = purchase.settings.collectionsProvider,
            payerPhone = purchase.command.phone,
            destinationAddress = purchase.address,
            providerReference = null,
            checkoutId = null,
            mpesaReceipt = null,
            custodyTxId = null,
            txHash = null,
            failureReason = null,
            paymentDeadline = now.plus(purchase.settings.paymentWindow),
            createdAt = now,
            updatedAt = now,
            attempt = attempt,
        )
    }

    private companion object {
        const val RESERVE_FAILED = "reserve_failed"
        const val PROMPTED = "prompted"
    }
}
