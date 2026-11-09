package com.circaso.chama.domain.support

import com.circaso.wallet.api.WalletAmount
import java.math.BigDecimal

/** USDC minor units ↔ the wire's string amounts (decision 2026-10-28: amounts are strings across the wallet). */
object Amounts {
    private const val ASSET = "USDC"
    private const val DECIMALS = 6

    fun usdc(minor: Long): WalletAmount = WalletAmount(ASSET, minor.toString(), whole(minor))

    /** The coin a member paid, in its own units ("0.01" ETH from 10^16 at 18 decimals). */
    fun coin(paid: com.circaso.chama.domain.models.PaidCoin): WalletAmount =
        WalletAmount(
            paid.symbol,
            paid.amountMinor.toString(),
            BigDecimal(paid.amountMinor).movePointLeft(paid.decimals).stripTrailingZeros().toPlainString(),
        )

    fun whole(minor: Long): String =
        BigDecimal
            .valueOf(minor)
            .movePointLeft(DECIMALS)
            .stripTrailingZeros()
            .toPlainString()

    /** `amountMinor` (digits) or `amount` (whole units, at most 6 decimals); null when neither is an amount above zero. */
    fun parse(amountMinor: String?, amount: String?): Long? {
        val fromMinor = amountMinor?.trim()?.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLongOrNull()
        val fromWhole =
            amount?.trim()?.takeIf { it.isNotEmpty() }?.let { text ->
                runCatching {
                    BigDecimal(text)
                }.getOrNull()?.takeIf { it.scale() <= DECIMALS }?.movePointRight(DECIMALS)?.longValueExact()
            }
        val minor = fromMinor ?: fromWhole ?: return null
        if (fromMinor != null && fromWhole != null && fromMinor != fromWhole) return null
        return minor.takeIf { it > 0 }
    }
}
