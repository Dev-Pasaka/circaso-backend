package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.ExchangeRates
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.FeeEstimate
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.support.TransferQuoter

/**
 * `GET /agent/wallet/fee` (decision 2026-11-05): W17's fee, total, balance, most that fits and limits for a payment
 * Asha is asked about, priced by the same rules and the same network estimate the payment would be. Unlike W17 it
 * never holds a quote: nothing is written, and the person still sees and confirms the real fee when they pay. The
 * network estimate is the fee calculator's, kept a minute per sending wallet and payee, so asking again costs Circle
 * nothing; the agent token's own rate limit bounds the rest.
 */
class EstimateFee(private val quoter: TransferQuoter, private val rates: ExchangeRates) {
    suspend operator fun invoke(command: TransferCommand, currency: String?): Resource<FeeEstimate> {
        val quoted =
            when (val read = quoter.quote(command, hold = false)) {
                is Resource.Success -> read.data
                is Resource.Error -> return read
            }
        val rate = currency?.takeIf { it != "USD" }?.let { rates.usdRate(it) }
        return Resource.success(FeeEstimate(quoted, rate))
    }
}
