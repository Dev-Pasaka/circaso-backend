package com.circaso.shared

import java.math.BigDecimal

/**
 * An amount of ordinary money: integer minor units (cents) plus its ISO 4217 currency, never floating point.
 * `FiatAmount(130_000, "KES")` is 1 300 shillings. [Money] is crypto-only (it carries an [Asset]); this is what the
 * ramp pays and collects in. The string form hides the amount, which never reaches a log line.
 */
data class FiatAmount(val amountMinor: Long, val currency: String) {
    /** Whole units for display ("1300", "12.5"). */
    fun display(): String = BigDecimal.valueOf(amountMinor, MINOR_DIGITS).stripTrailingZeros().toPlainString()

    /** True when the amount has no cents, which is all M-Pesa can carry. */
    val isWholeUnits: Boolean get() = amountMinor % MINOR_PER_UNIT == 0L

    val wholeUnits: Long get() = amountMinor / MINOR_PER_UNIT

    override fun toString(): String = "FiatAmount($currency, ***)"

    companion object {
        const val MINOR_DIGITS = 2
        const val MINOR_PER_UNIT = 100L

        fun ofWholeUnits(units: Long, currency: String): FiatAmount =
            FiatAmount(Math.multiplyExact(units, MINOR_PER_UNIT), currency)
    }
}
