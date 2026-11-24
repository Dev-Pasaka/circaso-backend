package com.circaso.ramp.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.ramp.api.QuoteId
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KycStanding
import com.circaso.ramp.domain.models.OffRampStatus
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.QuoteCommand
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampStatus
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.ports.LoggedFrame
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RealtimeLog
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.QuoteRepository
import com.circaso.ramp.domain.support.Engine
import com.circaso.ramp.domain.support.Gates
import com.circaso.ramp.domain.support.MerchantTopUps
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.PayoutFees
import com.circaso.ramp.domain.support.SaleFlow
import com.circaso.ramp.domain.support.SalePricing
import com.circaso.ramp.domain.support.Verifications
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * R1: whether this user can trade on [Network] now: identity verification in full (state, whether it is required,
 * whether a check may be started, why it was declined), a block, the account, the limits, and whether anybody is
 * online. No merchant is named. Answers `ramp.unavailable` when the ramp isn't open to them there.
 */
class GetRampStatus(
    private val gates: Gates,
    private val accounts: AccountDirectory,
    private val orders: OrderRepository,
    private val engine: Engine,
    private val verifications: Verifications,
) {
    /**
     * Answers when either direction is open to the user (selling has its own switch, 2026-11-23); `ramp.unavailable`
     * only when neither is.
     */
    suspend operator fun invoke(userId: UserId, network: Network): Resource<RampStatus> {
        val settings = gates.settingsOf(network)
        val buying = gates.isOpen(userId, settings, Direction.ON_RAMP)
        val selling = gates.isOpen(userId, settings, Direction.OFF_RAMP)
        if (!buying && !selling) return Resource.error(RampError.Unavailable)
        val blocked = gates.isBlocked(userId, settings)
        val verification = verifications.view(Subject.user(userId), settings)
        return Resource.success(
            RampStatus(
                network = network,
                kyc = gates.kyc(userId, settings),
                accountActive = accounts.status(userId) == AccountStatus.ACTIVE,
                blockchains = settings.chains,
                merchantOnline = buying && settings.chains.any { engine.anyoneOnline(network, it, settings) },
                openOrderId = orders.openOf(userId)?.orderId,
                limits = settings.limits,
                feeBps = settings.feeBps,
                // Someone barred has nothing to start: a check wouldn't let them trade.
                verification = if (blocked) verification.copy(canStart = false) else verification,
                blocked = blocked,
                onRampOpen = buying,
                offRamp =
                    OffRampStatus(
                        open = selling,
                        merchantOnline =
                            selling &&
                                settings.chains.any {
                                    engine.anyoneOnline(network, it, settings, Direction.OFF_RAMP)
                                },
                        limits = settings.offRampLimits,
                        feeBands = settings.feeBandsOf(Direction.OFF_RAMP),
                    ),
                feeBands = settings.feeBandsOf(Direction.ON_RAMP),
            ),
        )
    }
}

/**
 * R2: prices an amount for the settings' quote lifetime. A quote reserves nothing; it fixes the rate, the fee and the
 * fee's split its order will use. Selling waits on a payout provider, so only buying is quoted.
 */
class CreateQuote(
    private val gates: Gates,
    private val accounts: AccountDirectory,
    private val pricing: Pricing,
    private val quotes: QuoteRepository,
    private val clock: Clock,
    private val payoutFees: PayoutFees,
) {
    suspend operator fun invoke(command: QuoteCommand): Resource<Quote> {
        val settings =
            when (val open = gates.open(command.userId, command.network, command.direction)) {
                is Resource.Error -> return open
                is Resource.Success -> open.data
            }
        val offered = command.blockchain in settings.chains && Network.ofChain(command.blockchain) == command.network
        if (!offered) return Resource.error(RampError.ChainUnsupported)
        if ((command.fiatMinor == null) == (command.assetMinor == null)) return Resource.error(RampError.AmountInvalid)
        if (accounts.status(command.userId) != AccountStatus.ACTIVE) return Resource.error(RampError.AccountRestricted)
        gates.refusal(command.userId, settings)?.let { return Resource.error(it) }
        if (command.direction == Direction.OFF_RAMP) return sale(command, settings)

        val asked =
            pricing.price(
                command.network,
                command.direction,
                command.fiatMinor,
                command.assetMinor,
                settings.feeBandsOf(command.direction),
            )
        val priced =
            when (asked) {
                is Resource.Error -> return asked
                is Resource.Success -> asked.data
            }
        if (priced.fiatMinor !in settings.orderMinMinor..settings.orderMaxMinor) {
            return Resource.error(RampError.LimitExceeded)
        }

        val quote =
            Quote(
                quoteId = QuoteId.new(),
                userId = command.userId,
                network = command.network,
                direction = command.direction,
                blockchain = command.blockchain,
                fiat = FiatAmount(priced.fiatMinor, RampSettings.FIAT),
                assetMinor = priced.assetMinor,
                rate = priced.rate,
                feeMinor = priced.feeMinor,
                feeBps = priced.feeBps,
                merchantShareBps = settings.merchantShareBps,
                expiresAt = clock.instant().plus(settings.quoteLifetime),
                rateBasis = priced.rateBasis,
                feeBandFromMinor = priced.feeBandFromMinor,
            )
        quotes.insert(quote)
        return Resource.success(quote)
    }

    /**
     * A sale (decision 2026-11-23): the user names the USDC to send or the shillings to receive (whole shillings).
     * Circaso's fee is the settings' rate, as on a purchase; the provider's payout fee is added to the user's cost and
     * comes from its published table, else staff's table, and is **never invented**: no fee, no quote
     * (`ramp.payout_fee_unavailable`). The limits are selling's own, on what the user receives.
     */
    private suspend fun sale(command: QuoteCommand, settings: RampSettings): Resource<Quote> {
        // Selling is quoted at the sell rate (decision 2026-11-24).
        val applied =
            when (val read = pricing.rate(command.network, Direction.OFF_RAMP)) {
                is Resource.Error -> return read
                is Resource.Success -> read.data
            }
        val rate = applied.rate
        var feeMissing = false
        var feeSource: String? = null
        val feeOf: suspend (Long) -> Long? = { amount ->
            val fee = payoutFees.of(settings, amount)
            if (fee == null) feeMissing = true else feeSource = fee.source
            fee?.feeMinor
        }
        val asset = command.assetMinor
        val fiat = command.fiatMinor
        val bands = settings.feeBandsOf(Direction.OFF_RAMP)
        val priced =
            when {
                asset != null -> SalePricing.ofAsset(asset, rate, bands, feeOf)
                fiat != null -> SalePricing.ofPayout(fiat, rate, bands, feeOf)
                else -> null
            }
        if (priced == null) {
            return Resource.error(if (feeMissing) RampError.PayoutFeeUnavailable else RampError.AmountInvalid)
        }
        if (priced.payoutMinor !in settings.offRampMinMinor..settings.offRampMaxMinor) {
            return Resource.error(RampError.LimitExceeded)
        }
        val quote =
            Quote(
                quoteId = QuoteId.new(),
                userId = command.userId,
                network = command.network,
                direction = Direction.OFF_RAMP,
                blockchain = command.blockchain,
                fiat = FiatAmount(priced.payoutMinor, RampSettings.FIAT),
                assetMinor = priced.assetMinor,
                rate = rate,
                feeMinor = priced.feeMinor,
                feeBps = priced.feeBps,
                merchantShareBps = settings.merchantShareBps,
                expiresAt = clock.instant().plus(settings.quoteLifetime),
                providerFeeMinor = priced.providerFeeMinor,
                providerFeeSource = feeSource,
                rateBasis = applied.basis,
                feeBandFromMinor = priced.feeBandFromMinor,
            )
        quotes.insert(quote)
        return Resource.success(quote)
    }
}

/** R4: one order, to the user who made it; anyone else gets `ramp.order_not_found`. */
class GetOrder(private val orders: OrderRepository, private val merchants: MerchantRepository) {
    suspend operator fun invoke(userId: UserId, orderId: RampOrderId): Resource<OrderView> {
        val order =
            orders.find(orderId)?.takeIf { it.userId == userId } ?: return Resource.error(RampError.OrderNotFound)
        return Resource.success(OrderView(order, merchants.find(order.merchantId)?.displayName.orEmpty()))
    }
}

/** R5: the user's orders on a network, newest first, a page at a time by keyset. */
class ListOrders(private val orders: OrderRepository, private val merchants: MerchantRepository) {
    suspend operator fun invoke(
        userId: UserId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): Resource<List<OrderView>> {
        val page = orders.list(userId, network, limit.coerceIn(1, MAX_LIMIT), after)
        val names = page.map { it.merchantId }.distinct().associateWith { merchants.find(it)?.displayName.orEmpty() }
        return Resource.success(page.map { OrderView(it, names.getValue(it.merchantId)) })
    }

    private companion object {
        const val MAX_LIMIT = 200
    }
}

/**
 * R6: cancels an order before it is paid. An order whose prompt is already on the phone is cancelled only once the
 * provider confirms nothing was paid: while the prompt is still open the answer is `ramp.order_not_cancellable`, and
 * a payment that did land carries on to its release.
 */
class CancelOrder(
    private val orders: OrderRepository,
    private val merchants: MerchantRepository,
    private val flow: OrderFlow,
    private val sales: SaleFlow,
) {
    suspend operator fun invoke(userId: UserId, orderId: RampOrderId): Resource<OrderView> {
        val order =
            orders.find(orderId)?.takeIf { it.userId == userId } ?: return Resource.error(RampError.OrderNotFound)
        val after =
            when (order.state) {
                OrderState.RESERVED -> {
                    flow.close(order, OrderState.CANCELLED, "cancelled")
                }

                OrderState.PAYMENT_REQUESTED -> {
                    flow.verify(order).order.takeIf { it.state == OrderState.FAILED }
                }

                // A sale before its USDC is known to be on its way: a transfer the wallet already confirmed can't be
                // taken back, so that sale carries on (and one that lands after a cancel is refunded).
                OrderState.AWAITING_TRANSFER -> {
                    order.takeIf { it.walletTransferId == null }?.let {
                        sales.close(
                            it,
                            OrderState.CANCELLED,
                            "cancelled",
                        )
                    }
                }

                else -> {
                    null
                }
            } ?: return Resource.error(RampError.OrderNotCancellable)
        return Resource.success(OrderView(after, merchants.find(after.merchantId)?.displayName.orEmpty()))
    }
}

/**
 * The provider called back on the address that ends in [callbackKey], naming [reference]. The callback is unsigned,
 * so it only rings the bell (decision 2026-11-22): a key the ramp never gave out is ignored, and so is a reference
 * that isn't an order **of the merchant that key belongs to**. For one that is, the provider itself is asked what
 * happened ([OrderFlow.verify], which ignores an order that isn't waiting for a payment). A forged callback can at
 * most cause one status query for a real open order of that same merchant.
 *
 * A reference that is no order may be one of the merchant's top-ups of their own wallets ([MerchantTopUps.rung]).
 *
 * One more case is told to the people on call: a callback for an order that was closed as `prompt_unconfirmed` (the
 * provider gave no answer when the prompt was sent, and couldn't say afterwards whether it went out).
 */
class HandlePaymentCallback(
    private val merchants: MerchantRepository,
    private val orders: OrderRepository,
    private val flow: OrderFlow,
    private val alerts: RampAlerts,
    private val topUps: MerchantTopUps,
) {
    suspend operator fun invoke(callbackKey: String, reference: String): Resource<Unit> {
        val merchant = merchants.byCallbackKey(callbackKey) ?: return Resource.success(Unit)
        val order = RampOrderId.parse(reference)?.let { orders.find(it) }
        // Not an order: it may be one of this merchant's own top-ups (2026-11-23), which asks the provider in turn.
        if (order == null) {
            topUps.rung(merchant.merchantId, reference)
            return Resource.success(Unit)
        }
        if (order.merchantId != merchant.merchantId) return Resource.success(Unit)
        // The order was closed because nobody could say whether its prompt went out, and now the provider rings
        // about it: a prompt did go out. The callback proves nothing, so nothing moves; a person must look.
        val wasUnconfirmed = order.state == OrderState.FAILED && order.failureReason == OrderFlow.PROMPT_UNCONFIRMED
        if (wasUnconfirmed) alerts.stuck("order ${order.orderId} callback", "callback_after_unconfirmed_prompt")
        flow.verify(order)
        return Resource.success(Unit)
    }
}

/**
 * `ramp.expire-orders` on one network: finishes what a callback didn't. Safe on two nodes and safe to run twice: every
 * step is a transition guard.
 *
 * - `RESERVED` past its payment window (the prompt never went out): `EXPIRED`, the float given back.
 * - `PAYMENT_REQUESTED`: the provider is asked (a lost callback). A failed payment frees the float at once; a payment
 *   the provider still can't settle after [RampSettings.REVIEW_AFTER] goes to a person. **An unpaid order is never
 *   expired on a guess**: while the provider can't be asked (or this node doesn't hold the merchant's keys), nothing
 *   changes.
 *
 * `PAID` and `RELEASING` orders are the ops process's (`ramp.release`).
 */
class SweepOrders(private val orders: OrderRepository, private val flow: OrderFlow, private val clock: Clock) {
    suspend operator fun invoke(network: Network): Int {
        val now = clock.instant()
        var handled = 0

        orders.stale(network, setOf(OrderState.RESERVED), now, LIMIT).forEach { order ->
            if (order.paymentDeadline.isBefore(now)) {
                flow.close(order, OrderState.EXPIRED, "never_prompted")
                handled += 1
            }
        }
        val recheck = now.minus(RampSettings.RECHECK_AFTER)
        orders.stale(network, setOf(OrderState.PAYMENT_REQUESTED), recheck, LIMIT).forEach { order ->
            val checked = flow.verify(order)
            val unsettled = checked.asked && checked.order.state == OrderState.PAYMENT_REQUESTED
            if (unsettled && order.createdAt.isBefore(now.minus(RampSettings.REVIEW_AFTER))) {
                flow.review(checked.order, "payment_unresolved")
            }
            handled += 1
        }
        return handled
    }

    private companion object {
        const val LIMIT = 100
    }
}

/** The gateway's replay after a reconnect: the frames since [seq], or null when the gap is older than what is kept. */
class ReplayFrames(private val log: RealtimeLog) {
    suspend operator fun invoke(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> {
        val frames = log.since(userId, seq, LIMIT)
        if (frames.isEmpty()) {
            val latest = log.latest(userId) ?: return Resource.success(emptyList())
            if (latest > seq) return Resource.success(null) // frames existed after seq but are gone: resync
        }
        return Resource.success(frames)
    }

    private companion object {
        const val LIMIT = 500
    }
}

/** `Ramp.hasOpenOrder`: whether the user has an order that hasn't finished. */
class HasOpenOrder(private val orders: OrderRepository) {
    suspend operator fun invoke(userId: UserId): Boolean = orders.openOf(userId) != null
}
