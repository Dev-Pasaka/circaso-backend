package com.circaso.wallet.domain.support

import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.wallet.api.WalletAmount
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Amounts as the wallet hands them out and takes them in (decision 2026-10-28: strings across the whole wallet,
 * `amountMinor` exact and `amount` in whole units for display).
 */
object Amounts {
    fun of(money: Money) = WalletAmount(money.asset.symbol, money.amountMinor.toString(), whole(money))

    /** "20.5" for 20 500 000 USDC minor units; "0" for nothing. */
    fun whole(money: Money): String = whole(money.amountMinor, money.asset.decimals)

    /** [usdMinor] micro-dollars as an amount of `USD` (6 decimals), for limits on a payment in another coin. */
    fun usd(usdMinor: Long) = WalletAmount(USD, usdMinor.toString(), whole(BigInteger.valueOf(usdMinor), USD_DECIMALS))

    /** US dollars as an amount's asset, where an amount is a dollar value rather than a coin (2026-11-07). */
    const val USD = "USD"
    const val USD_DECIMALS = 6

    fun whole(amountMinor: BigInteger, decimals: Int): String =
        BigDecimal(amountMinor, decimals).stripTrailingZeros().toPlainString()

    /**
     * The amount in [asset]'s minor units from a request: `amountMinor` (digits) or `amount` (whole units, at most the
     * asset's decimals), or both when they agree. Null when neither reads, they disagree, or it passes a `Long`.
     */
    fun parse(
        asset: Asset,
        amountMinor: String?,
        amount: String?,
    ): Long? {
        val exact = amountMinor?.let { minorOf(it) }
        val whole = amount?.let { minorOf(it, asset.decimals) }
        if (amountMinor != null && exact == null) return null
        if (amount != null && whole == null) return null
        if (exact != null && whole != null && exact != whole) return null
        return exact ?: whole
    }

    /**
     * A request's amount before its coin is known (W4, W17; 2026-11-07): `amountMinor` as digits (up to 78, a
     * uint256) and `amount` as a whole-unit decimal, each null when absent; false when one is given and doesn't read.
     */
    fun read(amountMinor: String?, amount: String?): Triple<Boolean, BigInteger?, BigDecimal?> {
        val exact = amountMinor?.takeIf { BIG_DIGITS.matches(it) }?.let { BigInteger(it) }
        val whole = amount?.takeIf { DECIMAL.matches(it) }?.let { BigDecimal(it) }
        val ok = (amountMinor == null || exact != null) && (amount == null || whole != null)
        return Triple(ok, exact, whole)
    }

    private val DIGITS = Regex("^[0-9]{1,19}$")
    private val BIG_DIGITS = Regex("^[0-9]{1,78}$")
    private val DECIMAL = Regex("^[0-9]{1,19}(\\.[0-9]{1,36})?$")

    private fun minorOf(digits: String): Long? = digits.takeIf { DIGITS.matches(it) }?.toLongOrNull()

    private fun minorOf(decimal: String, decimals: Int): Long? {
        if (!DECIMAL.matches(decimal)) return null
        return runCatching {
            BigDecimal(decimal)
                .movePointRight(decimals)
                .stripTrailingZeros()
                .toBigIntegerExact()
                .longValueExact()
        }.getOrNull()
    }
}
