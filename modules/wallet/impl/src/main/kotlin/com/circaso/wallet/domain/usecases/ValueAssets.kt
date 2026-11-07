package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.UsdRate
import com.circaso.shared.Asset
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.AssetValue
import com.circaso.wallet.domain.models.AssetValueQuery
import com.circaso.wallet.domain.models.AssetValues
import com.circaso.wallet.domain.support.Valuation
import java.math.BigDecimal

/**
 * `GET /agent/wallet/values` (decisions 2026-11-05 and 2026-11-06): what coins are worth, from the very rate and
 * prices W1 shows balances with and W6 and W17 value payments and fees with, so a number Asha says always matches the
 * app. A US dollar stablecoin ([Asset]) is one dollar; another coin (ETH, SOL, BTC) is at the wallet's price feed
 * ([CryptoPrices], the one behind W1's `usdPrice`); a name the feed doesn't price is answered unpriced, never looked
 * up elsewhere. Times today's exchange rate ([Valuation]). Reads a rate and prices, nothing else.
 */
class ValueAssets(private val rates: ExchangeRates, private val prices: CryptoPrices) {
    suspend operator fun invoke(query: AssetValueQuery): Resource<AssetValues> {
        val rate = query.currency?.takeIf { it != USD }?.let { rates.usdRate(it) }
        val currency = rate?.currency ?: USD
        val names = query.assets.ifEmpty { Asset.entries.map { it.name } }.distinct()
        val coins = names.filter { assetOf(it) == null }.toSet()
        val priced = if (coins.isEmpty()) emptyMap() else prices.usdPrices(coins)
        val values = names.map { valueOf(it, priced[it], query.amount, rate) }
        return Resource.success(AssetValues(currency, query.amount, values, rate))
    }

    private fun valueOf(
        name: String,
        price: BigDecimal?,
        amount: BigDecimal?,
        rate: UsdRate?,
    ): AssetValue {
        val asset = assetOf(name)
        val usdPrice = if (asset != null) BigDecimal.ONE else price ?: return AssetValue(name, null, null, null, null)
        val unit = Valuation.fiatOrDollars(usdPrice, rate)
        val value = amount?.let { Valuation.fiatOrDollars(it * usdPrice, rate) }
        return AssetValue(name, asset, usdPrice, unit, value)
    }

    private fun assetOf(name: String): Asset? = Asset.entries.firstOrNull { it.name == name }

    private companion object {
        const val USD = "USD"
    }
}
