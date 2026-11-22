package com.circaso.ramp.domain.support

import com.circaso.ramp.api.RampAmount
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.shared.FiatAmount
import java.math.BigDecimal

/** Amounts as the ramp hands them out: strings, exact (`amountMinor`) and for display (`amount`). */
object Amounts {
    fun usdc(minor: Long): RampAmount =
        RampAmount(
            RampSettings.ASSET,
            minor.toString(),
            BigDecimal.valueOf(minor, RampSettings.ASSET_DECIMALS).stripTrailingZeros().toPlainString(),
        )

    fun fiat(amount: FiatAmount): RampAmount =
        RampAmount(amount.currency, amount.amountMinor.toString(), amount.display())

    fun fiat(minor: Long): RampAmount = fiat(FiatAmount(minor, RampSettings.FIAT))

    /** Basis points as a percentage for display: 150 is "1.5", 7000 is "70". */
    fun percent(bps: Int): String = BigDecimal.valueOf(bps.toLong(), 2).stripTrailingZeros().toPlainString()

    /** `amountMinor` (digits) or `amount` (whole units, at most [decimals] decimals); null when neither is above zero. */
    fun parse(
        amountMinor: String?,
        amount: String?,
        decimals: Int,
    ): Long? {
        val fromMinor = amountMinor?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
        val fromWhole =
            amount?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                runCatching { BigDecimal(text) }
                    .getOrNull()
                    ?.takeIf { it.scale() <= decimals }
                    ?.let { runCatching { it.movePointRight(decimals).longValueExact() }.getOrNull() }
            }
        val minor = fromMinor ?: fromWhole ?: return null
        if (fromMinor != null && fromWhole != null && fromMinor != fromWhole) return null
        return minor.takeIf { it > 0 }
    }
}
