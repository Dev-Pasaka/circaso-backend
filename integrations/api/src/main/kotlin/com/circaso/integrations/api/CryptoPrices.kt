package com.circaso.integrations.api

import java.math.BigDecimal
import java.time.Instant

/**
 * What one whole coin is worth in US dollars (decision 2026-11-06: CoinGecko), for showing a coin other than a dollar
 * stablecoin in the person's own money and for typing an amount in that money. Like [ExchangeRates], a price is a
 * convenience on screen and for converting what someone types, never part of a payment or a fee, so there is no
 * error to handle: a coin with no price (an unknown symbol, the feed off or down for too long) is left out of the
 * answer and its fiat value is left out on screen.
 */
interface CryptoPrices {
    /**
     * The price of each of [symbols] that has one, keyed by the symbol exactly as asked. Symbols are matched without
     * case and without a testnet suffix (`ETH-SEPOLIA` is priced as `ETH`). US dollar stablecoins (USDC, USDT) are
     * one dollar without asking the feed.
     */
    suspend fun usdPrices(symbols: Set<String>): Map<String, BigDecimal>

    /** [symbol]'s price, or null when it has none. */
    suspend fun usdPrice(symbol: String): BigDecimal? = usdPrices(setOf(symbol))[symbol]
}

/**
 * What the crypto market says about any coin (decision 2026-11-06: CoinGecko, the same feed as [CryptoPrices]), for
 * Asha: she answers every question about coins and markets from here, never from what the model remembers. Like the
 * prices, nothing here throws: a coin that can't be found or a feed that can't answer is null (or an empty list).
 */
interface CryptoMarkets {
    /**
     * The coin [query] names, by symbol (`eth`), name (`Ethereum`) or id (`ethereum`), with its market data. When
     * several coins share a symbol, the one with the largest market cap wins (a copycat token never does).
     */
    suspend fun coin(query: String): CoinMarket?

    /** The coins most searched in the last 24 hours, most first. */
    suspend fun trending(): List<TrendingCoin>
}

/**
 * One coin's market data in US dollars, each value null when the feed doesn't give it. [description] is the feed's
 * own words, plain text and short: other people's text, so the caller treats it as untrusted.
 */
data class CoinMarket(
    val id: String,
    val symbol: String,
    val name: String,
    val marketCapRank: Int?,
    val usdPrice: BigDecimal?,
    val change24hPercent: BigDecimal?,
    val change7dPercent: BigDecimal?,
    val marketCapUsd: BigDecimal?,
    val volume24hUsd: BigDecimal?,
    val high24hUsd: BigDecimal?,
    val low24hUsd: BigDecimal?,
    val allTimeHighUsd: BigDecimal?,
    val allTimeHighAt: Instant?,
    val fromAllTimeHighPercent: BigDecimal?,
    val circulatingSupply: BigDecimal?,
    val maxSupply: BigDecimal?,
    val categories: List<String>,
    val homepage: String?,
    val description: String?,
    val updatedAt: Instant?,
)

/** A trending coin; [usdPrice] and [change24hPercent] when the feed gives them. */
data class TrendingCoin(
    val id: String,
    val symbol: String,
    val name: String,
    val marketCapRank: Int?,
    val usdPrice: BigDecimal?,
    val change24hPercent: BigDecimal?,
)
