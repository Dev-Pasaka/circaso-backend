package com.circaso.ramp.data.support

import com.circaso.integrations.api.ExchangeRates
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Priced
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.shared.FiatAmount
import com.circaso.shared.Resource
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * TODO(decision: the exchange rate) The day's reference rate (shillings per US dollar, USDC counted as one dollar)
 * with no spread, until where the rate comes from is decided. The fee is the settings' rate of what the user pays and
 * sits inside it (decision 2026-11-22): paying 1 300 shillings at 2 % is a fee of 26, and the user receives the USDC
 * 1 274 shillings buy. M-Pesa carries whole shillings, so the fiat side is always whole shillings; rounding always
 * favours the merchant (the fee rounds up to a shilling, the USDC rounds down), so a merchant never sells below the
 * quoted rate.
 */
class ReferenceRatePricing(private val rates: ExchangeRates) : Pricing {
    override suspend fun price(
        direction: Direction,
        fiatMinor: Long?,
        assetMinor: Long?,
        feeBps: Int,
    ): Resource<Priced> {
        val rate = rates.usdRate(RampSettings.FIAT)?.rate ?: return Resource.error(RampError.PricingUnavailable)
        if (rate.signum() <= 0) return Resource.error(RampError.PricingUnavailable)
        val paid =
            fiatMinor ?: assetMinor?.let { fiatFor(it, rate, feeBps) } ?: return Resource.error(RampError.AmountInvalid)
        if (paid <= 0 || paid % FiatAmount.MINOR_PER_UNIT != 0L) return Resource.error(RampError.AmountInvalid)

        val fee = roundUpToShilling(BigDecimal(paid).multiply(BigDecimal(feeBps)).divide(BPS, 0, RoundingMode.CEILING))
        val bought =
            BigDecimal(paid - fee)
                .movePointLeft(FiatAmount.MINOR_DIGITS)
                .divide(rate, RampSettings.ASSET_DECIMALS, RoundingMode.FLOOR)
                .movePointRight(RampSettings.ASSET_DECIMALS)
                .longValueExact()
        if (bought <= 0) return Resource.error(RampError.AmountInvalid)
        return Resource.success(Priced(fiatMinor = paid, assetMinor = bought, rate = rate, feeMinor = fee))
    }

    /** What the user pays, in whole shillings, to receive at least [assetMinor] after the fee. */
    private fun fiatFor(
        assetMinor: Long,
        rate: BigDecimal,
        feeBps: Int,
    ): Long {
        val net = BigDecimal(assetMinor).movePointLeft(RampSettings.ASSET_DECIMALS).multiply(rate)
        val afterFee = BPS.subtract(BigDecimal(feeBps))
        val gross = net.multiply(BPS).divide(afterFee, FiatAmount.MINOR_DIGITS, RoundingMode.CEILING)
        return roundUpToShilling(gross.movePointRight(FiatAmount.MINOR_DIGITS).setScale(0, RoundingMode.CEILING))
    }

    private fun roundUpToShilling(minor: BigDecimal): Long {
        val cents = minor.longValueExact()
        val remainder = cents % FiatAmount.MINOR_PER_UNIT
        return if (remainder == 0L) cents else cents + (FiatAmount.MINOR_PER_UNIT - remainder)
    }

    private companion object {
        val BPS: BigDecimal = BigDecimal(10_000)
    }
}
