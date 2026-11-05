package com.circaso.wallet.domain.models

import com.circaso.integrations.api.UsdRate
import com.circaso.shared.Asset
import java.math.BigDecimal

/**
 * What Asha asks the wallet to value (decision 2026-11-05): the [assets] named (as the model wrote them, so one we
 * don't hold can be answered as unpriced rather than refused), an optional [amount] of each in whole units, and the
 * [currency] to value them in (ISO 4217; null is US dollars).
 */
data class AssetValueQuery(val assets: List<String>, val amount: BigDecimal?, val currency: String?)

/**
 * One asset's value from the wallet's own rate. [asset] is null for a name the wallet doesn't hold ([name] says what
 * was asked); [usdPrice] is null when it has no price. [unitValue] is one unit in the currency, [value] the amount's.
 */
data class AssetValue(
    val name: String,
    val asset: Asset?,
    val usdPrice: BigDecimal?,
    val unitValue: BigDecimal?,
    val value: BigDecimal?,
)

/**
 * The answer: each asset's value, and the [rate] used (null in US dollars, or when no rate for the currency is known,
 * in which case the values are in US dollars and [currency] says so).
 */
data class AssetValues(
    val currency: String,
    val amount: BigDecimal?,
    val values: List<AssetValue>,
    val rate: UsdRate?,
)

/** W17's numbers for a payment that may never be made, with [rate] for the display currency when one was asked. */
data class FeeEstimate(val quote: TransferQuote, val rate: UsdRate?)
