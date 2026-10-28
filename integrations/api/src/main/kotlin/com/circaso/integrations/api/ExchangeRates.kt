package com.circaso.integrations.api

import java.math.BigDecimal
import java.time.Instant

/**
 * What one US dollar is worth in another currency, for showing a balance in the person's own money next to USDC
 * (decision 2026-10-28). A rate is a convenience on screen, never part of a payment, so there is no error to handle:
 * when no rate is known (an unknown currency, or the source down for too long) the answer is null and the fiat value
 * is left out.
 */
interface ExchangeRates {
    /** The rate for [currency], an ISO 4217 code in capitals (`KES`), or null when there is none. */
    suspend fun usdRate(currency: String): UsdRate?
}

/** One US dollar is [rate] units of [currency], as the source published it at [asOf]. */
data class UsdRate(val currency: String, val rate: BigDecimal, val asOf: Instant)
