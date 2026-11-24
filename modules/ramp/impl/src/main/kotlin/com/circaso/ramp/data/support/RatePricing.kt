package com.circaso.ramp.data.support

import com.circaso.integrations.api.ExchangeRates
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.AppliedRate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Priced
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RateReading
import com.circaso.ramp.domain.models.ReferenceRate
import com.circaso.ramp.domain.ports.Pricing
import com.circaso.ramp.domain.support.PricingBook
import com.circaso.ramp.domain.support.RatePolicy
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock

/**
 * The exchange rate (decision 2026-11-24, Pasaka): each side has its own rate, set by staff per network in
 * `ramp.pricing_settings` ([RatePolicy]): the day's reference rate (shillings per US dollar, USDC counted as one
 * dollar) with a spread on each side, or exact manual rates with an expiry. Buying is quoted at the buy rate, selling
 * at the sell rate. A side with no rate in force (no reference, a stale one, an expired manual rate) is
 * `ramp.pricing_unavailable`, never a fallback.
 *
 * The fee is the settings' rate of what the user pays and sits inside it (decision 2026-11-22): paying 1 300 shillings
 * at 2 % is a fee of 26, and the user receives the USDC 1 274 shillings buy. M-Pesa carries whole shillings, so the
 * fiat side is always whole shillings; rounding always favours the merchant (the fee rounds up to a shilling, the USDC
 * rounds down, a spread rate rounds the merchant's way), so a merchant never sells below the quoted rate.
 */
class RatePricing(private val rates: ExchangeRates, private val book: PricingBook, private val clock: Clock) :
    Pricing {
    private val log = LoggerFactory.getLogger(RatePricing::class.java)

    override suspend fun reference(): ReferenceRate? =
        rates.usdRate(RampSettings.FIAT)?.takeIf { it.rate.signum() > 0 }?.let { ReferenceRate(it.rate, it.asOf) }

    override suspend fun reading(network: Network, direction: Direction): RateReading =
        RatePolicy.read(book.of(network), reference(), direction, clock.instant())

    override suspend fun rate(network: Network, direction: Direction): Resource<AppliedRate> =
        when (val reading = reading(network, direction)) {
            is RateReading.Ready -> {
                Resource.success(reading.applied)
            }

            is RateReading.Paused -> {
                log.warn("ramp rate paused: network={} direction={} why={}", network, direction, reading.why)
                Resource.error(RampError.PricingUnavailable)
            }
        }

    override suspend fun price(
        network: Network,
        direction: Direction,
        fiatMinor: Long?,
        assetMinor: Long?,
        feeBps: Int,
    ): Resource<Priced> {
        val applied =
            when (val read = rate(network, direction)) {
                is Resource.Error -> return read
                is Resource.Success -> read.data
            }
        val rate = applied.rate
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
        return Resource.success(
            Priced(fiatMinor = paid, assetMinor = bought, rate = rate, feeMinor = fee, rateBasis = applied.basis),
        )
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
