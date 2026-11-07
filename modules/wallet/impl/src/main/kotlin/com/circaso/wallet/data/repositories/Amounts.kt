package com.circaso.wallet.data.repositories

import com.circaso.shared.Asset
import com.circaso.shared.Money
import java.math.BigDecimal

/** A stored amount (numeric(78,0) minor units, the coin's symbol and decimals; V8) as the domain's [Money]. */
internal fun moneyOf(
    amountMinor: BigDecimal,
    symbol: String,
    decimals: Short,
): Money = Money(amountMinor.toBigIntegerExact(), Asset(symbol, decimals.toInt()))
