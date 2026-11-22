package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.models.FeeSplit
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.shared.FiatAmount
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * How an order's fee is shared (decision 2026-11-22). The shillings all land in the merchant's till, so the merchant
 * already holds the whole fee; Circaso's part is taken from the merchant's USDC float, converted at the order's own
 * rate. Each order uses the split of its quote, so a change of the settings never touches an order already made.
 */
object Fees {
    /**
     * The merchant's part rounds down to the cent and Circaso gets the rest of the fee; Circaso's part in USDC rounds
     * down to the smallest unit, so the float is never asked for more than the fee was worth.
     */
    fun split(order: Order): FeeSplit {
        val merchantMinor = Math.multiplyExact(order.feeMinor, order.merchantShareBps.toLong()) / RampSettings.ALL_BPS
        val platformMinor = order.feeMinor - merchantMinor
        val platformAssetMinor =
            if (order.rate.signum() <= 0) {
                0
            } else {
                BigDecimal
                    .valueOf(platformMinor, FiatAmount.MINOR_DIGITS)
                    .divide(order.rate, RampSettings.ASSET_DECIMALS, RoundingMode.FLOOR)
                    .movePointRight(RampSettings.ASSET_DECIMALS)
                    .longValueExact()
            }
        return FeeSplit(merchantMinor, platformMinor, platformAssetMinor)
    }
}
