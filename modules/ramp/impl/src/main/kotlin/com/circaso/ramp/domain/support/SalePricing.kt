package com.circaso.ramp.domain.support

import com.circaso.integrations.api.FeeSource
import com.circaso.integrations.api.ProviderFees
import com.circaso.ramp.domain.models.PayoutFee
import com.circaso.ramp.domain.models.RampFeeBand
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.shared.FiatAmount
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The price of a sale (decision 2026-11-23). The user sends USDC; at the reference rate it is worth [gross] shillings;
 * Circaso's fee is the rate of the band that worth falls in (2026-11-24), as on a purchase; the provider's payout fee is added to the user's cost
 * (the merchant pays the provider and receives their full amount); what is left, in whole shillings (M-Pesa pays no
 * cents), is what the user receives. Rounding always favours the merchant: the fee rounds up and the payout down, so a
 * merchant never pays out more than the USDC they receive was worth less the fee.
 */
object SalePricing {
    /** One sale's figures: [assetMinor] USDC sent, [payoutMinor] shillings received, and the two fees. */
    data class Sale(
        val assetMinor: Long,
        val payoutMinor: Long,
        val feeMinor: Long,
        val providerFeeMinor: Long,
        /** The band's rate that priced it, and where that band starts (null for one flat band). */
        val feeBps: Int = 0,
        val feeBandFromMinor: Long? = null,
    )

    /** What [assetMinor] USDC is worth at [rate], in KES minor units, rounded down to the cent. */
    fun grossOf(assetMinor: Long, rate: BigDecimal): Long =
        BigDecimal(assetMinor)
            .movePointLeft(RampSettings.ASSET_DECIMALS)
            .multiply(rate)
            .movePointRight(FiatAmount.MINOR_DIGITS)
            .setScale(0, RoundingMode.FLOOR)
            .longValueExact()

    /** Circaso's fee on [grossMinor] at [feeBps], rounded up to the cent. */
    fun feeOf(grossMinor: Long, feeBps: Int): Long =
        BigDecimal(grossMinor)
            .multiply(BigDecimal(feeBps))
            .divide(BPS, 0, RoundingMode.CEILING)
            .longValueExact()

    /**
     * Selling [assetMinor] USDC. The band [grossMinor] falls in sets the fee rate (2026-11-24): selling's bands are by
     * the KES value of what is sold, the amount its fee is charged on. [providerFee] prices a payout amount; it is
     * asked for the payout before its own fee comes off, which is never less than the fee of what is finally paid
     * (the provider's charge grows with the amount), so the user is never charged less than the provider will take.
     * Null when the provider's fee can't be found, or nothing is left to pay out.
     */
    suspend fun ofAsset(
        assetMinor: Long,
        rate: BigDecimal,
        bands: List<RampFeeBand>,
        providerFee: suspend (Long) -> Long?,
    ): Sale? {
        if (assetMinor <= 0 || rate.signum() <= 0) return null
        val gross = grossOf(assetMinor, rate)
        val band = RampFeeBands.bandFor(bands, gross)
        val fee = feeOf(gross, band.feeBps)
        val before = floorToShilling(gross - fee)
        if (before <= 0) return null
        val provider = providerFee(before) ?: return null
        val payout = floorToShilling(gross - fee - provider)
        if (payout <= 0) return null
        return Sale(assetMinor, payout, fee, provider, band.feeBps, bandStart(bands, band))
    }

    /**
     * Receiving exactly [payoutMinor] shillings (whole shillings). The USDC to send is rounded up to its smallest unit,
     * and Circaso's fee is what is left of its worth after the payout and the provider's fee, which is never less than
     * the band's rate of it (the rounding goes to the fee, never to the user's payout). The band is solved so that the
     * sale's worth lands in the band whose rate priced it (2026-11-24): at each band's rate, the USDC needed, raised
     * to where the band starts when it falls below it (the extra goes to the fee); a band whose answer runs past its
     * end can't hold it; the least USDC of those wins. Null when the provider's fee can't be found.
     */
    suspend fun ofPayout(
        payoutMinor: Long,
        rate: BigDecimal,
        bands: List<RampFeeBand>,
        providerFee: suspend (Long) -> Long?,
    ): Sale? {
        if (payoutMinor <= 0 || payoutMinor % FiatAmount.MINOR_PER_UNIT != 0L || rate.signum() <= 0) return null
        val provider = providerFee(payoutMinor) ?: return null
        return bands
            .mapNotNull { band ->
                val needed =
                    BigDecimal(payoutMinor + provider)
                        .multiply(BPS)
                        .divide(BPS.subtract(BigDecimal(band.feeBps)), 0, RoundingMode.CEILING)
                        .max(BigDecimal(band.fromMinor))
                val assetMinor = assetFor(needed, rate)
                val gross = grossOf(assetMinor, rate)
                if (!band.covers(gross)) return@mapNotNull null
                Sale(
                    assetMinor,
                    payoutMinor,
                    gross - payoutMinor - provider,
                    provider,
                    band.feeBps,
                    bandStart(bands, band),
                )
            }.minByOrNull { it.assetMinor }
    }

    /** The USDC, rounded up to the smallest unit, worth at least [kesMinor] at [rate]. */
    private fun assetFor(kesMinor: BigDecimal, rate: BigDecimal): Long =
        kesMinor
            .movePointLeft(FiatAmount.MINOR_DIGITS)
            .divide(rate, RampSettings.ASSET_DECIMALS, RoundingMode.CEILING)
            .movePointRight(RampSettings.ASSET_DECIMALS)
            .longValueExact()

    private fun bandStart(bands: List<RampFeeBand>, band: RampFeeBand): Long? =
        band.fromMinor.takeIf { RampFeeBands.banded(bands) }

    private fun floorToShilling(minor: Long): Long = minor - Math.floorMod(minor, FiatAmount.MINOR_PER_UNIT)

    private val BPS: BigDecimal = BigDecimal(10_000)
}

/**
 * The provider's payout fee for one amount (decision 2026-11-23): its own published table first
 * ([ProviderFees.payoutFee], live or kept), and only when that can't say, the table staff set in the admin
 * ([RampSettings.payoutFeeBands]). **Never invented**: an amount neither covers is null, and the sale isn't quoted.
 * A fee in cents is rounded up to the cent; the provider charges what it charges.
 */
class PayoutFees(private val fees: ProviderFees) {
    suspend fun of(settings: RampSettings, payoutMinor: Long): PayoutFee? {
        val published = fees.payoutFee(settings.collectionsProvider, FiatAmount(payoutMinor, RampSettings.FIAT))
        val fee = published.fee
        if (fee != null && published.source != FeeSource.UNKNOWN && fee.currency == RampSettings.FIAT) {
            return PayoutFee(fee.amountMinor.coerceAtLeast(0), published.source.name)
        }
        return settings.payoutFeeBands
            .sortedBy { it.upToMinor }
            .firstOrNull { payoutMinor <= it.upToMinor }
            ?.let { PayoutFee(it.feeMinor, PayoutFee.ADMIN_TABLE) }
    }
}
