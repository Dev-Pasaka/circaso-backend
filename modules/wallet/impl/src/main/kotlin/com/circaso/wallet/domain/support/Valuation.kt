package com.circaso.wallet.domain.support

import com.circaso.integrations.api.UsdRate
import com.circaso.wallet.domain.models.AssetTotal
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.FiatValue
import com.circaso.wallet.domain.models.Holding
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Currency

/**
 * Adding up a wallet over its chains and putting it in the person's own currency (decision 2026-10-28). Only US
 * dollar stablecoins have a price, one dollar each, times the exchange rate; any other asset is left out of the fiat
 * sum and the sum says it is partial.
 */
object Valuation {
    /** Each asset over every chain, in the order first seen. Amounts in different decimals meet at the larger. */
    fun totals(chains: List<ChainHoldings>): List<AssetTotal> =
        chains
            .flatMap { it.holdings }
            .groupBy { it.symbol }
            .map { (symbol, holdings) -> total(symbol, holdings) }

    /** The fiat value of everything priced; [rate] is what one US dollar is in the currency. */
    fun fiat(chains: List<ChainHoldings>, rate: UsdRate): FiatValue {
        val holdings = chains.flatMap { it.holdings }
        val dollars = holdings.filter { it.usdPegged }.fold(BigDecimal.ZERO) { sum, it -> sum + it.whole() }
        // TODO(decision: crypto price source) assets other than US dollar stablecoins have no price yet.
        val partial = holdings.any { !it.usdPegged && it.amountMinor.signum() > 0 }
        return FiatValue(
            currency = rate.currency,
            amount = inCurrency(dollars, rate),
            rate = rate.rate,
            asOf = rate.asOf,
            partial = partial,
        )
    }

    /** What one holding is worth in the rate's currency, or null when it has no price. */
    fun fiatOf(holding: Holding, rate: UsdRate): BigDecimal? =
        if (holding.usdPegged) inCurrency(holding.whole(), rate) else null

    /** Rounded half up, as a person rounds: the value is shown, never paid, so there is nothing to balance. */
    private fun inCurrency(dollars: BigDecimal, rate: UsdRate): BigDecimal =
        (dollars * rate.rate).setScale(fractionDigits(rate.currency), RoundingMode.HALF_UP)

    /** The currency's minor unit (2 for KES, 0 for UGX); 2 for the few codes with none defined. */
    private fun fractionDigits(currency: String): Int =
        runCatching { Currency.getInstance(currency).defaultFractionDigits }
            .getOrNull()
            ?.takeIf { it >= 0 }
            ?: 2

    private fun total(symbol: String, holdings: List<Holding>): AssetTotal {
        val decimals = holdings.maxOf { it.decimals }
        val sum =
            holdings.fold(BigInteger.ZERO) { acc, it ->
                acc +
                    it.amountMinor * BigInteger.TEN.pow(decimals - it.decimals)
            }
        return AssetTotal(symbol, decimals, sum)
    }

    private fun Holding.whole(): BigDecimal = BigDecimal(amountMinor, decimals)
}
