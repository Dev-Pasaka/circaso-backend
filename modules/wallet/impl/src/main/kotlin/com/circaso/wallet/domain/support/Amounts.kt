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
    fun of(money: Money) = WalletAmount(money.asset.name, money.amountMinor.toString(), whole(money))

    /** "20.5" for 20 500 000 USDC minor units; "0" for nothing. */
    fun whole(money: Money): String = whole(BigInteger.valueOf(money.amountMinor), money.asset.decimals)

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

    private val DIGITS = Regex("^[0-9]{1,19}$")
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
