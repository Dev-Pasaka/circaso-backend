package com.circaso.wallet.domain.usecases

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
 * `GET /agent/wallet/values` (decision 2026-11-05): what the assets the wallet holds are worth, from the very rate W1
 * shows balances with and W6 and W17 value payments and fees with, so a number Asha says always matches the app.
 * Every [Asset] is a US dollar stablecoin, one dollar each, times today's exchange rate ([Valuation]); a name the
 * wallet doesn't hold is answered unpriced, never looked up elsewhere. Reads a rate and nothing else.
 */
class ValueAssets(private val rates: ExchangeRates) {
    suspend operator fun invoke(query: AssetValueQuery): Resource<AssetValues> {
        val rate = query.currency?.takeIf { it != USD }?.let { rates.usdRate(it) }
        val currency = rate?.currency ?: USD
        val names = query.assets.ifEmpty { Asset.entries.map { it.name } }
        val values = names.distinct().map { valueOf(it, query.amount, rate) }
        return Resource.success(AssetValues(currency, query.amount, values, rate))
    }

    private fun valueOf(
        name: String,
        amount: BigDecimal?,
        rate: UsdRate?,
    ): AssetValue {
        val asset = Asset.entries.firstOrNull { it.name == name } ?: return AssetValue(name, null, null, null, null)
        val unit = Valuation.fiatOrDollars(BigDecimal.ONE, rate)
        val value = amount?.let { Valuation.fiatOrDollars(it, rate) }
        return AssetValue(name, asset, BigDecimal.ONE, unit, value)
    }

    private companion object {
        const val USD = "USD"
    }
}
