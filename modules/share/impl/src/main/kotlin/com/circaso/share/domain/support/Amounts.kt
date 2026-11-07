package com.circaso.share.domain.support

import java.math.BigDecimal
import java.math.RoundingMode

/** Amounts as pages and the app show them. USDC has 6 decimals; fiat is rounded to cents. */
object Amounts {
    const val USDC_DECIMALS = 6

    private val DOLLAR_PEGGED = setOf("USDC", "USDT", "PYUSD", "DAI")

    fun isDollar(asset: String): Boolean = asset.uppercase() in DOLLAR_PEGGED

    /** 2_500_000 → "2.5": whole units, no trailing zeros. */
    fun usdcText(minor: Long): String = BigDecimal.valueOf(minor, USDC_DECIMALS).stripTrailingZeros().toPlainString()

    fun usdc(minor: Long): BigDecimal = BigDecimal.valueOf(minor, USDC_DECIMALS)

    /** [dollars] in the rate's currency, to the cent. */
    fun fiat(dollars: BigDecimal, rate: BigDecimal): BigDecimal =
        dollars.multiply(rate).setScale(2, RoundingMode.HALF_UP)

    /** An ISO 4217 code in capitals, the only shape a rate is asked for. */
    fun isCurrency(code: String): Boolean = CURRENCY.matches(code)

    private val CURRENCY = Regex("[A-Z]{3}")
}
