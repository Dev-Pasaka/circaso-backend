package com.circaso.wallet.domain.support

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.QuoteProblem
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferQuote
import java.time.Clock

/**
 * W17's pricing of one payment, shared by [com.circaso.wallet.domain.usecases.QuoteTransfer] (which holds the fee for
 * the signer) and Asha's estimate (decision 2026-11-05, which never holds). It runs W4's checks on the sender and the
 * payee and reports a short balance or a limit ([TransferQuote.problem]) instead of refusing. Nothing moves.
 */
class TransferQuoter(
    private val holders: Holders,
    private val planner: PaymentPlanner,
    private val quotes: FeeQuotes,
    private val clock: Clock,
) {
    /** [hold] false (Asha's estimate, decision 2026-11-05) reads only: no quote is held, whoever asks. */
    suspend fun quote(command: TransferCommand, hold: Boolean): Resource<TransferQuote> {
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
        val held = if (hold && access.canSign) quotes.hold(plan, command, fee) else null
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
