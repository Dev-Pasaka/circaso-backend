package com.circaso.wallet.domain.support

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.wallet.api.SpaceWalletPolicy
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.Allowance
import com.circaso.wallet.domain.models.Fee
import com.circaso.wallet.domain.models.FeeQuote
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.FeeQuoteRepository
import java.math.BigInteger
import java.time.Clock
import java.util.UUID

/**
 * One payment worked out before anything is written (W4 and W17 share it): who sends from which wallet, where it
 * goes, the [amount] in the [coin] found on the chain, how it is priced, where the wallet stands against its limits
 * (in micro-dollars), and what it holds of the coin on the chain now.
 */
class PaymentPlan(
    val owner: WalletOwner,
    val sender: Sender,
    val destination: Destination,
    val pricing: Pricing,
    val allowance: Allowance,
    val balanceMinor: BigInteger,
    val amount: Money,
) {
    val coin: PaymentCoin get() = pricing.coin

    fun feeFor(amountMinor: BigInteger): Fee = pricing.feeFor(amountMinor)

    /** What [amountMinor] of the coin with [fee] counts against the limits, in micro-dollars. */
    fun usdTotal(amountMinor: BigInteger, fee: Fee): Long = coin.usdMinor(amountMinor) + fee.usdMinor

    /** The most that can be sent now with its fee: within the balance, what is left of the window and one payment's cap. */
    fun maxAmount(): BigInteger = pricing.maxAmount(balanceMinor, allowance.perPaymentMaxMinor, allowance.leftMinor)

    /**
     * This plan at the price a held quote was priced at (decision 2026-11-07): a price move after the quote changes
     * neither the fee that is signed nor what the payment counts against the limits.
     */
    fun atPrice(usdPrice: java.math.BigDecimal): PaymentPlan {
        if (usdPrice.compareTo(coin.usdPrice) == 0) return this
        val priced =
            Pricing(
                pricing.parties,
                pricing.rule,
                pricing.gasMinor,
                pricing.feeAddress,
                pricing.reason,
                coin.copy(usdPrice = usdPrice),
            )
        return PaymentPlan(owner, sender, destination, priced, allowance, balanceMinor, amount)
    }
}

/**
 * Works a payment out in W4's order (Wallet → W4): the sender's rules, an amount that can be sent, then (for W4,
 * [strict]) the limits on the amount alone, the payee, the price and the balance. A quote isn't [strict]: it reports
 * the limits rather than refusing on them.
 */
class PaymentPlanner(
    private val rules: SenderRules,
    private val payees: Payees,
    private val fees: FeeCalculator,
    private val limits: Limits,
    /** Spaces' guard (Spaces phase 3): what a membership payment must be. */
    private val spaces: SpaceWalletPolicy? = null,
    /** Finds the coin the payment names on the chain (2026-11-07: any held coin). */
    private val coins: PaymentCoins? = null,
) {
    suspend fun plan(
        owner: WalletOwner,
        command: TransferCommand,
        strict: Boolean,
    ): Resource<PaymentPlan> {
        val holder = owner.holder
        val sender =
            when (val found = rules.sender(holder, command.blockchain, signer = owner.signerId)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val (coin, amount) =
            when (val found = coinOf(sender, command)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val membership =
            when (val checked = spacePayment(owner, command, amount)) {
                is Resource.Success -> checked.data
                is Resource.Error -> return checked
            }
        val allowance = limits.allowance(owner, amount.asset)
        val cap = allowance.perPaymentMaxMinor
        val amountUsd = coin.usdMinor(amount.amountMinor)
        if (strict && cap != null && amountUsd > cap) {
            // One payment's amount over its cap: said before the payee is looked at (W4's order).
            val most = coin.maxWithinUsd(cap)
            limits.check(allowance, amountUsd, 0, most).let { if (it is Resource.Error) return it }
        }
        val destination =
            when (
                val resolved =
                    payees.resolve(
                        holder,
                        command.payee,
                        command.blockchain,
                        command.chatId,
                        actor = command.senderId,
                        membership = membership,
                    )
            ) {
                is Resource.Success -> resolved.data
                is Resource.Error -> return resolved
            }
        val pricing =
            when (val priced = fees.pricing(owner, sender, destination, coin)) {
                is Resource.Success -> priced.data
                is Resource.Error -> return priced
            }
        val balance =
            coin.heldMinor ?: when (val read = rules.balance(sender, coin)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        return Resource.success(PaymentPlan(owner, sender, destination, pricing, allowance, balance, amount))
    }

    /**
     * The coin and the amount in it. Without [coins] (tests of the dollar paths) only an asset offered on the chain
     * is paid, at one dollar, as before 2026-11-07.
     */
    private suspend fun coinOf(sender: Sender, command: TransferCommand): Resource<Pair<PaymentCoin, Money>> {
        coins?.let { return it.find(sender, command.amount) }
        val offered =
            rules.offered(command.blockchain, command.amount.symbol) ?: return Resource.error(WalletError.AmountInvalid)
        val asset = Asset(offered.symbol, offered.decimals)
        val minor = command.amount.minorIn(asset.decimals)
        if (minor == null || minor.signum() <= 0) return Resource.error(WalletError.AmountInvalid)
        return Resource.success(PaymentCoin.dollar(asset, offered.tokenAddress) to Money(minor, asset))
    }

    /**
     * A membership payment (SP13, Spaces phase 3) is paid from the personal wallet, into exactly its space and for
     * exactly its amount, while spaces still holds it (15 minutes). True when this payment is one, so the payee check
     * lets someone who isn't yet a member pay it. Its fee is the space rate, like any payment into a space wallet.
     */
    private suspend fun spacePayment(
        owner: WalletOwner,
        command: TransferCommand,
        amount: Money,
    ): Resource<Boolean> {
        val paymentId = command.spacePaymentId ?: return Resource.success(false)
        if (owner !is WalletOwner.Person) return Resource.error(WalletError.SpacePaymentPersonalOnly)
        val payee = command.payee as? Payee.Space ?: return Resource.error(WalletError.SpacePaymentInvalid)
        val charge =
            spaces?.membershipCharge(paymentId, command.senderId)
                ?: return Resource.error(WalletError.SpacePaymentInvalid)
        val exact =
            charge.spaceId == payee.spaceId && amount.asset == Asset.USDC &&
                amount.amountMinor == BigInteger.valueOf(charge.amountMinor)
        return if (exact) Resource.success(true) else Resource.error(WalletError.SpacePaymentInvalid)
    }
}

/** The fees people were shown (W17), held for the signing window so W4 charges exactly that one. */
class FeeQuotes(private val quotes: FeeQuoteRepository, private val clock: Clock) {
    /** Keeps [fee] for [command]'s exact payment from [plan]'s wallet. */
    suspend fun hold(
        plan: PaymentPlan,
        command: TransferCommand,
        fee: Fee,
    ): FeeQuote {
        // The quote holds the fee in the coin and the price it was converted at (decision 2026-11-07).
        val now = clock.instant()
        val quote =
            FeeQuote(
                quoteId = UUID.randomUUID(),
                holder = plan.owner.holder,
                signerId = command.senderId,
                blockchain = command.blockchain,
                payeeKey = keyOf(command.payee),
                amount = plan.amount,
                fee = fee,
                createdAt = now,
                expiresAt = now.plus(Transfer.SIGNING_WINDOW),
            )
        quotes.insert(quote)
        return quote
    }

    /** The quote [command] names, if it is for exactly this payment from [plan]'s wallet (expired or not). */
    suspend fun named(plan: PaymentPlan, command: TransferCommand): FeeQuote? {
        val quote = command.quoteId?.let { quotes.find(it) } ?: return null
        val same =
            quote.holder == plan.owner.holder && quote.signerId == command.senderId &&
                quote.blockchain == command.blockchain && quote.payeeKey == keyOf(command.payee) &&
                quote.amount == plan.amount
        return quote.takeIf { same }
    }

    fun holds(quote: FeeQuote): Boolean = clock.instant().isBefore(quote.expiresAt)

    private fun keyOf(payee: Payee): String =
        when (payee) {
            is Payee.Person -> "user:${payee.userId.value}"
            is Payee.Group -> "group:${payee.chatId.value}"
            is Payee.Space -> "space:${payee.spaceId}"
            is Payee.Address -> "address:${Addresses.normalise(payee.address.trim())}"
        }
}
