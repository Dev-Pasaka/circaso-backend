package com.circaso.ramp.domain.support

import com.circaso.shared.FiatAmount

/**
 * The M-Pesa total of a purchase (decision 2026-11-24, Pasaka: PayHero's collection fee is passed to the buyer).
 *
 * PayHero takes its fee for each prompt from the merchant's prepaid service wallet, by the band of the amount
 * **prompted**. So the buyer is prompted for the merchant's quote (the shillings the USDC is priced on, Circaso's fee
 * inside it as before) **plus** PayHero's fee for that total: the extra shillings land in the merchant's till and cover
 * what PayHero takes from their service wallet, and the merchant nets exactly their quote.
 *
 * The band depends on the total and the total on the fee, so the total is solved to a fixed point: the **smallest**
 * whole-shilling total `T` with `T − fee(T) ≥ base`. Starting from `T = base + fee(base)`, each step sets
 * `T = base + fee(T)`; a fee that never falls as the amount grows (PayHero's table) makes this climb to the least
 * fixed point, where `T − fee(T) = base` exactly. Everything is whole shillings, and the merchant is never short:
 * a step stops only when what is left after the fee covers [baseMinor].
 */
object PurchaseTotals {
    /**
     * A purchase's total prompted and the provider's fee inside it, KES minor units, both whole shillings, and where
     * the fee came from. [baseMinor] is the merchant's quote: what the USDC is priced on.
     */
    data class Total(val totalMinor: Long, val providerFeeMinor: Long, val source: String) {
        val baseMinor: Long get() = totalMinor - providerFeeMinor
    }

    /**
     * The least total that nets the merchant [baseMinor] after the provider's fee, or null when the fee can't be
     * found for some step, or no total settles within [MAX_STEPS] (a table whose fee falls as the amount grows could
     * cycle; PayHero's doesn't).
     */
    suspend fun forBase(baseMinor: Long, feeOf: suspend (Long) -> Pair<Long, String>?): Total? {
        if (baseMinor <= 0) return null
        var total = baseMinor
        repeat(MAX_STEPS) {
            val (fee, source) = feeOf(total) ?: return null
            val next = baseMinor + roundUpToShilling(fee)
            if (next <= total) return Total(total, total - baseMinor, source)
            total = next
        }
        return null
    }

    /**
     * The buyer named the total (`fiat`: "I pay X"): X is what is prompted, and the provider's fee for X comes out
     * of the part converted. Null when the fee can't be found or leaves nothing to convert.
     */
    suspend fun forTotal(totalMinor: Long, feeOf: suspend (Long) -> Pair<Long, String>?): Total? {
        if (totalMinor <= 0) return null
        val (fee, source) = feeOf(totalMinor) ?: return null
        val rounded = roundUpToShilling(fee)
        if (rounded >= totalMinor) return null
        return Total(totalMinor, rounded, source)
    }

    fun roundUpToShilling(minor: Long): Long {
        val remainder = Math.floorMod(minor, FiatAmount.MINOR_PER_UNIT)
        return if (remainder == 0L) minor else minor + (FiatAmount.MINOR_PER_UNIT - remainder)
    }

    private const val MAX_STEPS = 32
}
