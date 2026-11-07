package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.CoinMarket
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.TrendingCoin
import com.circaso.integrations.impl.PriceSymbols
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests: prices set by the test in [prices] (by symbol, `ETH`), every request recorded in [asked]. Dollar stablecoins
 * are one dollar, as with the real feed.
 */
class FakeCryptoPrices : CryptoPrices {
    val prices = ConcurrentHashMap<String, BigDecimal>()
    val asked: MutableList<Set<String>> = CopyOnWriteArrayList()

    override suspend fun usdPrices(symbols: Set<String>): Map<String, BigDecimal> {
        asked += symbols
        return PriceSymbols.answer(symbols) { prices[it] }
    }
}

/** The feed switched off (no `COINGECKO_API_KEY`): only dollar stablecoins have a price, everything else is unpriced. */
class NoCryptoPrices : CryptoPrices {
    override suspend fun usdPrices(symbols: Set<String>): Map<String, BigDecimal> =
        PriceSymbols.answer(symbols) { null }
}

/**
 * Local runs only (`INTEGRATIONS_PRICES_PROVIDER=log`): a few fixed prices, so ETH and SOL show in shillings without
 * calling the feed, and a line saying what was asked. Not real prices: the numbers are only roughly right, which is
 * why this is never the default.
 */
class LogCryptoPrices : CryptoPrices {
    private val log = LoggerFactory.getLogger(LogCryptoPrices::class.java)

    override suspend fun usdPrices(symbols: Set<String>): Map<String, BigDecimal> {
        log.info("crypto prices asked for {}", symbols.sorted())
        return PriceSymbols.answer(symbols) { FIXED[it]?.let(::BigDecimal) }
    }

    private companion object {
        val FIXED =
            mapOf(
                "ETH" to "3500",
                "WETH" to "3500",
                "POL" to "0.40",
                "MATIC" to "0.40",
                "SOL" to "180",
                "AVAX" to "30",
                "BTC" to "100000",
                "WBTC" to "100000",
                "CBBTC" to "100000",
                "EURC" to "1.08",
            )
    }
}

/** Tests: coins set by the test in [coins] (by lower-case symbol or name), trending in [trendingCoins]; asks recorded. */
class FakeCryptoMarkets : CryptoMarkets {
    val coins = ConcurrentHashMap<String, CoinMarket>()
    val trendingCoins: MutableList<TrendingCoin> = CopyOnWriteArrayList()
    val asked: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun coin(query: String): CoinMarket? {
        asked += query
        return coins[query.trim().lowercase()]
    }

    override suspend fun trending(): List<TrendingCoin> = trendingCoins.toList()
}

/** The feed switched off (no `COINGECKO_API_KEY`): no coin is found and nothing is trending. */
class NoCryptoMarkets : CryptoMarkets {
    override suspend fun coin(query: String): CoinMarket? = null

    override suspend fun trending(): List<TrendingCoin> = emptyList()
}
