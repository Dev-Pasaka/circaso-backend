package com.circaso.wallet.domain.usecases

import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferQuote
import com.circaso.wallet.domain.support.TransferQuoter

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
class QuoteTransfer(private val quoter: TransferQuoter) {
    suspend operator fun invoke(command: TransferCommand): Resource<TransferQuote> = quoter.quote(command, hold = true)
}
