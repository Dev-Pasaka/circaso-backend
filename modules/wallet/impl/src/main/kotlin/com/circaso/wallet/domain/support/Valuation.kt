package com.circaso.wallet.domain.support

import com.circaso.integrations.api.UsdRate
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.wallet.domain.models.AssetTotal
import com.circaso.wallet.domain.models.ChainHoldings
import com.circaso.wallet.domain.models.FiatValue
import com.circaso.wallet.domain.models.Holding
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.util.Currency

/**
 * Adding up a wallet over its chains and putting it in the person's own currency (decisions 2026-10-28 and
 * 2026-11-06). Each holding is priced by its [Holding.usdPrice] (one dollar for a US dollar stablecoin, the price
 * feed's for another coin we trust, see [HoldingPrices]) times the exchange rate; an asset with no price is left out
 * of the fiat sum and the sum says it is partial. Prices are for showing and typing only, never for a payment or fee.
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
        val dollars = holdings.fold(BigDecimal.ZERO) { sum, it -> sum + (it.dollars() ?: BigDecimal.ZERO) }
        val partial = holdings.any { it.price() == null && it.amountMinor.signum() > 0 }
        return FiatValue(
            currency = rate.currency,
            amount = inCurrency(dollars, rate),
            rate = rate.rate,
            asOf = rate.asOf,
            partial = partial,
        )
    }

    /** What one holding is worth in the rate's currency, or null when it has no price. */
    fun fiatOf(holding: Holding, rate: UsdRate): BigDecimal? = holding.dollars()?.let { inCurrency(it, rate) }

    /**
     * What an amount of a history asset is worth in the rate's currency. Every [Asset] the history records is a US
     * dollar stablecoin, so each is priced at one dollar.
     */
    fun fiatOf(amount: Money, rate: UsdRate): BigDecimal =
        inCurrency(BigDecimal(BigInteger.valueOf(amount.amountMinor), amount.asset.decimals), rate)

    /** Rounded half up, as a person rounds: the value is shown, never paid, so there is nothing to balance. */
    private fun inCurrency(dollars: BigDecimal, rate: UsdRate): BigDecimal =
        (dollars * rate.rate).setScale(fractionDigits(rate.currency), RoundingMode.HALF_UP)

    /** [dollars] in the rate's currency, or in US dollars to the cent when there is no rate (Asha's values). */
    fun fiatOrDollars(dollars: BigDecimal, rate: UsdRate?): BigDecimal =
        if (rate == null) dollars.setScale(2, RoundingMode.HALF_UP) else inCurrency(dollars, rate)

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
        val price = holdings.map { it.price() }.distinct().singleOrNull()
        return AssetTotal(symbol, decimals, sum, price)
    }

    /** One unit in US dollars, as [HoldingPrices] set it; null is unpriced (it never falls back to [Holding.usdPegged]). */
    private fun Holding.price(): BigDecimal? = usdPrice

    /** The holding in US dollars, unrounded, or null when it has no price. */
    private fun Holding.dollars(): BigDecimal? = price()?.let { BigDecimal(amountMinor, decimals) * it }
}
