package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.UsdRate
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Tests: rates set by the test in [rates] (one dollar in each currency), every request recorded in [asked]. */
class FakeExchangeRates(private val clock: Clock = Clock.systemUTC()) : ExchangeRates {
    val rates = ConcurrentHashMap<String, BigDecimal>()
    val asked: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun usdRate(currency: String): UsdRate? {
        asked += currency
        return rates[currency]?.let { UsdRate(currency, it, clock.instant()) }
    }
}

/**
 * Local runs and the sandbox: a few fixed rates, so the fiat balance shows without calling the source, and a line
 * saying which currency was asked for. Not real rates: the numbers are only roughly right.
 */
class LogExchangeRates(private val clock: Clock = Clock.systemUTC()) : ExchangeRates {
    private val log = LoggerFactory.getLogger(LogExchangeRates::class.java)

    override suspend fun usdRate(currency: String): UsdRate? {
        log.info("exchange rate asked for {}", currency)
        return FIXED[currency]?.let { UsdRate(currency, BigDecimal(it), clock.instant()) }
    }

    private companion object {
        val FIXED =
            mapOf(
                "USD" to "1",
                "KES" to "129.25",
                "UGX" to "3700",
                "TZS" to "2650",
                "NGN" to "1550",
                "GHS" to "15.5",
                "ZAR" to "18.1",
                "EUR" to "0.92",
                "GBP" to "0.78",
            )
    }
}
