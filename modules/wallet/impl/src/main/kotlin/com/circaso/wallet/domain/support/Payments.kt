package com.circaso.wallet.domain.support

import com.circaso.shared.Asset
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.util.UUID

/**
 * One payment worked out before anything is written (W4 and W17 share it): who sends from which wallet, where it
 * goes, how it is priced, where the wallet stands against its limits, and what it holds on the chain now.
 */
class PaymentPlan(
    val owner: WalletOwner,
    val sender: Sender,
    val destination: Destination,
    val pricing: Pricing,
    val allowance: Allowance,
    val balanceMinor: Long,
) {
    fun feeFor(amountMinor: Long): Fee = pricing.feeFor(amountMinor)

    /** The most that can be sent now with its fee: within the balance, what is left of the window and one payment's cap. */
    fun maxAmount(): Long {
        val budget = allowance.leftMinor?.let { minOf(it, balanceMinor) } ?: balanceMinor
        return pricing.maxAmount(budget, allowance.perPaymentMaxMinor)
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
        rules.checkAmount(command.blockchain, command.amount).let { if (it is Resource.Error) return it }
        val membership =
            when (val checked = spacePayment(owner, command)) {
                is Resource.Success -> checked.data
                is Resource.Error -> return checked
            }
        val allowance = limits.allowance(owner, command.amount.asset)
        val cap = allowance.perPaymentMaxMinor
        if (strict && cap != null && command.amount.amountMinor > cap) {
            // One payment's amount over its cap: said before the payee is looked at (W4's order).
            limits.check(allowance, command.amount.amountMinor, 0, cap).let { if (it is Resource.Error) return it }
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
        return priceAndBalance(owner, command, sender, destination, allowance)
    }

    /**
     * The price (Circle's gas estimate, the slow one) and the balance (Circle's live read), asked at once: neither
     * needs the other. The answer is what asking them in turn gave: a pricing error wins over a balance error, and a
     * balance read whose answer isn't needed is cancelled.
     */
    private suspend fun priceAndBalance(
        owner: WalletOwner,
        command: TransferCommand,
        sender: Sender,
        destination: Destination,
        allowance: Allowance,
    ): Resource<PaymentPlan> =
        coroutineScope {
            val priced = async { fees.pricing(owner, sender, destination, command.amount) }
            val read = async { rules.balance(sender, command.amount) }
            val pricing =
                when (val answer = priced.await()) {
                    is Resource.Success -> {
                        answer.data
                    }

                    is Resource.Error -> {
                        read.cancel()
                        return@coroutineScope answer
                    }
                }
            when (val balance = read.await()) {
                is Resource.Success -> {
                    Resource.success(PaymentPlan(owner, sender, destination, pricing, allowance, balance.data))
                }

                is Resource.Error -> {
                    balance
                }
            }
        }

    /**
     * A membership payment (SP13, Spaces phase 3) is paid from the personal wallet, into exactly its space and for
     * exactly its amount, while spaces still holds it (15 minutes). True when this payment is one, so the payee check
     * lets someone who isn't yet a member pay it. Its fee is the space rate, like any payment into a space wallet.
     */
    private suspend fun spacePayment(owner: WalletOwner, command: TransferCommand): Resource<Boolean> {
        val paymentId = command.spacePaymentId ?: return Resource.success(false)
        if (owner !is WalletOwner.Person) return Resource.error(WalletError.SpacePaymentPersonalOnly)
        val payee = command.payee as? Payee.Space ?: return Resource.error(WalletError.SpacePaymentInvalid)
        val charge =
            spaces?.membershipCharge(paymentId, command.senderId)
                ?: return Resource.error(WalletError.SpacePaymentInvalid)
        val exact =
            charge.spaceId == payee.spaceId && command.amount.asset == Asset.USDC &&
                command.amount.amountMinor == charge.amountMinor
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
        val now = clock.instant()
        val quote =
            FeeQuote(
                quoteId = UUID.randomUUID(),
                holder = plan.owner.holder,
                signerId = command.senderId,
                blockchain = command.blockchain,
                payeeKey = keyOf(command.payee),
                amount = command.amount,
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
                quote.amount == command.amount
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
