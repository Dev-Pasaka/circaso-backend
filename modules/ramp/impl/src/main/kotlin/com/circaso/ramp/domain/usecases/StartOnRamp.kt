package com.circaso.ramp.domain.usecases

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountStatus
import com.circaso.ramp.api.RampOrderId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KycStanding
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.OrderView
import com.circaso.ramp.domain.models.Quote
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.StartOnRampCommand
import com.circaso.ramp.domain.ports.RampCounters
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.repositories.QuoteRepository
import com.circaso.ramp.domain.support.Engine
import com.circaso.ramp.domain.support.Gates
import com.circaso.ramp.domain.support.OrderFlow
import com.circaso.ramp.domain.support.Periods
import com.circaso.shared.Resource
import com.circaso.wallet.api.Wallets
import java.time.Clock
import java.time.Instant

/**
 * R3: buys USDC with M-Pesa. Checks the gates, picks a merchant and reserves its float in one transaction, then
 * sends the M-Pesa prompt to the user's own verified number. The order and its reservation are written before the
 * provider is asked, so a repeated request (the same quote) finds the same order and sends no second prompt.
 */
class StartOnRamp(
    private val gates: Gates,
    private val accounts: AccountDirectory,
    private val wallets: Wallets,
    private val quotes: QuoteRepository,
    private val orders: OrderRepository,
    private val merchants: MerchantRepository,
    private val counters: RampCounters,
    private val engine: Engine,
    private val flow: OrderFlow,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: StartOnRampCommand): Resource<OrderView> {
        val now = clock.instant()
        val settings =
            when (val open = gates.open(command.userId, command.network)) {
                is Resource.Error -> return open
                is Resource.Success -> open.data
            }
        if (accounts.status(command.userId) != AccountStatus.ACTIVE) return Resource.error(RampError.AccountRestricted)
        if (gates.kyc(command.userId, settings) == KycStanding.NONE) return Resource.error(RampError.KycRequired)

        orders.byQuote(command.quoteId)?.let { existing ->
            return if (existing.userId == command.userId) view(existing) else Resource.error(RampError.QuoteNotFound)
        }
        val quote = quotes.find(command.quoteId, now) ?: return Resource.error(RampError.QuoteExpired)
        if (quote.userId != command.userId || quote.network != command.network) {
            return Resource.error(RampError.QuoteNotFound)
        }
        if (quote.direction != Direction.ON_RAMP) return Resource.error(RampError.OffRampUnavailable)
        if (orders.openOf(command.userId) != null) return Resource.error(RampError.OrderInProgress)

        // The payer the provider reports is matched to the user, so only their own verified number may pay.
        if (accounts.resolvePhones(setOf(command.phone))[command.phone] != command.userId) {
            return Resource.error(RampError.PhoneNotYours)
        }
        val address =
            wallets.depositAddress(command.userId, quote.blockchain) ?: return Resource.error(RampError.WalletRequired)
        return place(command, quote, settings, address, now)
    }

    private suspend fun place(
        command: StartOnRampCommand,
        quote: Quote,
        settings: RampSettings,
        address: String,
        now: Instant,
    ): Resource<OrderView> {
        val day = Periods.day(now)
        val paid = quote.fiat.amountMinor
        if (!counters.reserveDaily(command.userId, quote.network, day, paid, settings.dailyLimitMinor)) {
            return Resource.error(RampError.LimitExceeded)
        }
        if (counters.countPrompt(command.userId) > settings.promptsPerHour) {
            counters.giveBackDaily(command.userId, quote.network, day, paid)
            return Resource.error(RampError.TooManyPrompts(PROMPT_WINDOW_SECONDS))
        }

        val reserved =
            engine.reserve(quote.network, quote.blockchain, quote.assetMinor, settings) { candidate ->
                order(quote, command, candidate, address, now.plus(settings.paymentWindow), now)
            }
        val order =
            when (reserved) {
                is Resource.Success -> {
                    reserved.data
                }

                is Resource.Error -> {
                    counters.giveBackDaily(command.userId, quote.network, day, paid)
                    return reserved
                }
            }
        return when (val prompted = flow.requestPayment(order)) {
            is Resource.Error -> prompted
            is Resource.Success -> view(prompted.data)
        }
    }

    private fun order(
        quote: Quote,
        command: StartOnRampCommand,
        candidate: Candidate,
        address: String,
        deadline: Instant,
        now: Instant,
    ) = Order(
        orderId = RampOrderId.new(),
        userId = command.userId,
        merchantId = candidate.merchant.merchantId,
        quoteId = quote.quoteId,
        network = quote.network,
        direction = Direction.ON_RAMP,
        state = OrderState.RESERVED,
        blockchain = quote.blockchain,
        fiat = quote.fiat,
        assetMinor = quote.assetMinor,
        rate = quote.rate,
        feeMinor = quote.feeMinor,
        feeBps = quote.feeBps,
        merchantShareBps = quote.merchantShareBps,
        payerPhone = command.phone,
        destinationAddress = address,
        providerReference = null,
        checkoutId = null,
        mpesaReceipt = null,
        custodyTxId = null,
        txHash = null,
        failureReason = null,
        paymentDeadline = deadline,
        createdAt = now,
        updatedAt = now,
    )

    private suspend fun view(order: Order): Resource<OrderView> =
        Resource.success(OrderView(order, merchants.find(order.merchantId)?.displayName.orEmpty()))

    private companion object {
        const val PROMPT_WINDOW_SECONDS = 3_600L
    }
}
