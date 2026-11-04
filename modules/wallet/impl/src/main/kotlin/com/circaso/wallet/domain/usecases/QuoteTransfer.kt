package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.QuoteProblem
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferQuote
import com.circaso.wallet.domain.support.FeeQuotes
import com.circaso.wallet.domain.support.Holders
import com.circaso.wallet.domain.support.PaymentPlanner
import java.time.Clock

/**
 * W17 `POST /wallet/transfers/quote` (phase 2F, GW13): the one Circaso fee for this exact payment, network cost
 * included, before anything is signed, with the total, where the wallet stands against its limits, and the most that
 * fits. It takes W4's body and runs W4's checks on the sender and the payee, but reports a short balance or a limit
 * ([TransferQuote.problem]) instead of refusing. Nothing moves.
 *
 * For the person who signs for the wallet the fee is held for 15 minutes ([TransferQuote.quoteId]): W4 with that id
 * charges exactly it. A group's members may quote its wallet too (a payout request shows the fee and the daily limit
 * as they stand, GW9c and GW14), without a hold: the signer gets their own quote when they pay.
 */
class QuoteTransfer(
    private val holders: Holders,
    private val planner: PaymentPlanner,
    private val quotes: FeeQuotes,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: TransferCommand): Resource<TransferQuote> {
        val access =
            when (val seen = holders.view(command.senderId, command.walletId)) {
                is Resource.Success -> seen.data
                is Resource.Error -> return seen
            }
        val plan =
            when (val planned = planner.plan(access.owner, command, strict = false)) {
                is Resource.Success -> planned.data
                is Resource.Error -> return planned
            }
        val amount = command.amount.amountMinor
        val fee = plan.feeFor(amount)
        val total = amount + fee.amountMinor
        val allowance = plan.allowance
        val problem =
            when {
                allowance.perPaymentMaxMinor?.let { amount > it } == true -> QuoteProblem.PER_PAYMENT_LIMIT
                total > plan.balanceMinor -> QuoteProblem.INSUFFICIENT_FUNDS
                !allowance.fits(total) -> QuoteProblem.DAILY_LIMIT
                else -> null
            }
        val held = if (access.canSign) quotes.hold(plan, command, fee) else null
        return Resource.success(
            TransferQuote(
                blockchain = command.blockchain,
                amount = command.amount,
                fee = fee,
                balanceMinor = plan.balanceMinor,
                maxAmountMinor = plan.maxAmount(),
                allowance = allowance,
                problem = problem,
                quoteId = held?.quoteId,
                expiresAt = held?.expiresAt ?: clock.instant().plus(Transfer.SIGNING_WINDOW),
            ),
        )
    }
}
