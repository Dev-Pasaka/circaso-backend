package com.circaso.ramp.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RoutingOrder
import com.circaso.ramp.domain.models.RoutingOutcome
import com.circaso.ramp.domain.models.RoutingStep
import com.circaso.ramp.domain.models.StartOffRampCommand
import com.circaso.ramp.domain.ports.KesFloat
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.QuoteRepository
import com.circaso.ramp.domain.support.Engine
import com.circaso.ramp.domain.support.Gates
import com.circaso.ramp.domain.support.Periods
import com.circaso.ramp.domain.support.RoutingCommand
import com.circaso.ramp.domain.support.SaleFlow
import com.circaso.ramp.domain.support.SaleFunding
import com.circaso.ramp.domain.support.SalePayouts
import com.circaso.ramp.domain.support.TestPayouts
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.wallet.api.Wallets
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * R7: sells USDC for shillings paid to the user's own M-Pesa (decision 2026-11-23). The user never picks a merchant:
 * the engine routes the sale among merchants who are online with their keys on this node, offer selling, have a
 * payout account, and whose **live** payout balance at the provider less what their open sales hold covers the payout
 * and its fee ([KesFloat]). Under the merchant's row lock the shillings are held for this sale and the order written
 * (`AWAITING_TRANSFER`), with the merchant's float address, the USDC to send and a deadline. Nothing is sent: the user
 * signs the transfer themselves with W4, naming the order. A repeated request with the same quote finds the same
 * order.
 */
class StartOffRamp(
    private val gates: Gates,
    private val accounts: AccountDirectory,
    private val wallets: Wallets,
    private val quotes: QuoteRepository,
    private val orders: OrderRepository,
    private val merchants: MerchantRepository,
    private val counters: RampCounters,
    private val engine: Engine,
    private val kesFloat: KesFloat,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: StartOffRampCommand): Resource<OrderView> {
        val now = clock.instant()
        val settings =
            when (val open = gates.open(command.userId, command.network, Direction.OFF_RAMP)) {
                is Resource.Error -> return open
                is Resource.Success -> open.data
            }
        if (accounts.status(command.userId) != AccountStatus.ACTIVE) return Resource.error(RampError.AccountRestricted)
        gates.refusal(command.userId, settings)?.let { return Resource.error(it) }

        orders.byQuote(command.quoteId)?.let { existing ->
            return if (existing.userId == command.userId) view(existing) else Resource.error(RampError.QuoteNotFound)
        }
        val quote = quotes.find(command.quoteId, now) ?: return Resource.error(RampError.QuoteExpired)
        if (quote.userId != command.userId || quote.network != command.network) {
            return Resource.error(RampError.QuoteNotFound)
        }
        if (quote.direction != Direction.OFF_RAMP) return Resource.error(RampError.QuoteNotFound)
        if (orders.openOf(command.userId) != null) return Resource.error(RampError.OrderInProgress)
        // Only the account's own verified number is paid: a sale never pays out to anyone else.
        if (accounts.resolvePhones(setOf(command.phone))[command.phone] != command.userId) {
            return Resource.error(RampError.PhoneNotYours)
        }
        // The user's own wallet on the chain: where the USDC must come from, and where a refund goes.
        val address =
            wallets.depositAddress(command.userId, quote.blockchain) ?: return Resource.error(RampError.WalletRequired)
        val day = Periods.day(now)
        val paid = quote.fiat.amountMinor
        if (!counters.reserveDailySale(command.userId, quote.network, day, paid, settings.offRampDailyLimitMinor)) {
            return Resource.error(RampError.LimitExceeded)
        }
        return when (val placed = route(command, quote, settings, address, now)) {
            is Resource.Error -> {
                counters.giveBackDailySale(command.userId, quote.network, day, paid)
                placed
            }

            is Resource.Success -> {
                view(placed.data)
            }
        }
    }

    /**
     * Routes the sale and holds the first merchant who can still take it under their row lock. Nothing is sent to
     * anyone, so a merchant who can't is simply passed over (step `reserve_failed`), up to every eligible one.
     */
    private suspend fun route(
        command: StartOffRampCommand,
        quote: Quote,
        settings: RampSettings,
        address: String,
        now: Instant,
    ): Resource<Order> {
        val need =
            RoutingOrder(
                Direction.OFF_RAMP,
                quote.fiat.amountMinor,
                quote.fiat.amountMinor + quote.providerFeeMinor,
            )
        val routing =
            engine.route(
                RoutingCommand(quote.quoteId, quote.network, quote.blockchain, need, kesFloat),
                settings,
            )
        val steps = mutableListOf<RoutingStep>()
        for (candidate in routing.ranked) {
            val held =
                engine.reserve(
                    routing,
                    candidate,
                    settings,
                ) { picked -> order(command, quote, settings, picked, address, now) }
            when (held) {
                is Resource.Success -> {
                    steps += RoutingStep(candidate.merchant.merchantId, held.data.orderId, HELD, clock.instant())
                    engine.conclude(routing, RoutingOutcome.ROUTED, steps, held.data)
                    return held
                }

                is Resource.Error -> {
                    steps += RoutingStep(candidate.merchant.merchantId, null, RESERVE_FAILED, clock.instant())
                    if (held.error != RampError.NoMerchantAvailable) {
                        engine.conclude(routing, RoutingOutcome.STOPPED, steps)
                        return held
                    }
                }
            }
        }
        val outcome = if (routing.ranked.isEmpty()) RoutingOutcome.NO_CANDIDATE else RoutingOutcome.EXHAUSTED
        engine.conclude(routing, outcome, steps)
        return Resource.error(RampError.NoMerchantAvailable)
    }

    private fun order(
        command: StartOffRampCommand,
        quote: Quote,
        settings: RampSettings,
        candidate: Candidate,
        address: String,
        now: Instant,
    ) = Order(
        orderId = RampOrderId.new(),
        userId = command.userId,
        merchantId = candidate.merchant.merchantId,
        quoteId = quote.quoteId,
        network = quote.network,
        direction = Direction.OFF_RAMP,
        state = OrderState.AWAITING_TRANSFER,
        blockchain = quote.blockchain,
        fiat = quote.fiat,
        assetMinor = quote.assetMinor,
        rate = quote.rate,
        rateBasis = quote.rateBasis,
        feeMinor = quote.feeMinor,
        feeBps = quote.feeBps,
        merchantShareBps = quote.merchantShareBps,
        feeBandFromMinor = quote.feeBandFromMinor,
        collectionsProvider = settings.collectionsProvider,
        payerPhone = command.phone,
        // The merchant's float address on the sale's chain: where the user sends the USDC.
        destinationAddress = candidate.wallet.address,
        providerReference = null,
        checkoutId = null,
        mpesaReceipt = null,
        custodyTxId = null,
        txHash = null,
        failureReason = null,
        paymentDeadline = now.plus(settings.transferWindow),
        createdAt = now,
        updatedAt = now,
        providerFeeMinor = quote.providerFeeMinor,
        providerFeeSource = quote.providerFeeSource,
        refundAddress = address,
    )

    private suspend fun view(order: Order): Resource<OrderView> =
        Resource.success(OrderView(order, merchants.find(order.merchantId)?.displayName.orEmpty()))

    private companion object {
        const val HELD = "held"
        const val RESERVE_FAILED = "reserve_failed"
    }
}

/**
 * The wallet's `wallet.TransferConfirmed` for a transfer that names a ramp order (W4 `rampOrderId`): the sale's
 * funding is recorded and checked with the custodian ([SaleFunding]), then paid out at once when this node can. A
 * repeated event changes nothing: the transfer is recorded once and the guards hold. A transfer that names an order
 * the ramp doesn't have moved real USDC somewhere: the people on call are told.
 */
class HandleSaleTransfer(
    private val orders: OrderRepository,
    private val funding: SaleFunding,
    private val payouts: SalePayouts,
    private val alerts: RampAlerts,
) {
    suspend operator fun invoke(rampOrderId: UUID, transferId: UUID): Resource<Unit> {
        val order = orders.find(RampOrderId(rampOrderId))
        if (order == null) {
            alerts.stuck("transfer $transferId for order $rampOrderId", "unknown_ramp_order")
            return Resource.success(Unit)
        }
        val funded = funding.transferConfirmed(order, transferId)
        if (funded.state == OrderState.FUNDED) payouts.pay(funded)
        return Resource.success(Unit)
    }
}

/**
 * The provider called back about a payout, on the address that ends in [callbackKey], naming [reference]. Only a
 * doorbell (decision 2026-11-22): a key the ramp never gave out is ignored, and so is a reference that isn't a sale or
 * a test payout **of the merchant that key belongs to**. For one that is, the provider itself is asked
 * ([SalePayouts.lookUp], [TestPayouts.lookUp]); a callback that rings twice asks twice and changes things once.
 */
class HandlePayoutCallback(
    private val merchants: MerchantRepository,
    private val orders: OrderRepository,
    private val payouts: SalePayouts,
    private val tests: TestPayouts,
) {
    suspend operator fun invoke(callbackKey: String, reference: String): Resource<Unit> {
        val merchant = merchants.byCallbackKey(callbackKey) ?: return Resource.success(Unit)
        val order = SalePayouts.orderOf(reference)?.let { orders.find(it) }
        if (order != null) {
            val ours = order.merchantId == merchant.merchantId && order.direction == Direction.OFF_RAMP
            if (ours && order.payoutReference == reference.trim()) payouts.lookUp(order)
            return Resource.success(Unit)
        }
        tests.rung(merchant.merchantId, reference)
        return Resource.success(Unit)
    }
}

/**
 * `ramp.payouts` on one network, in the app's process (every 10 seconds): finishes what an event or a callback didn't.
 * Safe on two nodes and safe to run twice: every step is a guard, and a payout is only sent from `FUNDED`.
 *
 * - Sales whose transfer the wallet confirmed but the custodian hadn't recorded: checked again; still nothing after a
 *   long wait, a person looks.
 * - `FUNDED` sales due a payout: sent ([SalePayouts.pay]); refunded when the payout window ran out with nothing sent.
 * - `PAYING_OUT` sales: looked up ([SalePayouts.lookUp]). One still `SENDING` is looked up only once it is old
 *   enough that its call can't still be running.
 * - `AWAITING_TRANSFER` past the deadline with no transfer: `EXPIRED` (`never_funded`), the shillings freed.
 */
class SweepSales(
    private val orders: OrderRepository,
    private val funding: SaleFunding,
    private val payouts: SalePayouts,
    private val flow: SaleFlow,
    private val clock: Clock,
) {
    suspend operator fun invoke(network: Network): Int {
        val now = clock.instant()
        var handled = 0
        orders.awaitingFunding(network, now.minus(RECHECK), LIMIT).forEach { order ->
            val after = funding.confirmOrReview(order)
            if (after.state == OrderState.FUNDED) payouts.pay(after)
            handled++
        }
        orders.dueForPayout(network, now, LIMIT).forEach { order ->
            payouts.pay(order)
            handled++
        }
        orders.stale(network, setOf(OrderState.PAYING_OUT), now.minus(RECHECK), LIMIT).forEach { order ->
            val stillSending = order.payoutOutcome == SalePayouts.SENDING
            val sentAt = order.payoutSentAt ?: order.updatedAt
            if (!stillSending || sentAt.plus(SalePayouts.SENDING_STALE).isBefore(now)) {
                payouts.lookUp(order)
                handled++
            }
        }
        orders.stale(network, setOf(OrderState.AWAITING_TRANSFER), now, LIMIT).forEach { order ->
            if (order.walletTransferId == null && order.paymentDeadline.isBefore(now)) {
                flow.close(order, OrderState.EXPIRED, SaleFlow.NEVER_FUNDED)
                handled++
            }
        }
        return handled
    }

    private companion object {
        const val LIMIT = 100
        val RECHECK: Duration = Duration.ofSeconds(10)
    }
}
