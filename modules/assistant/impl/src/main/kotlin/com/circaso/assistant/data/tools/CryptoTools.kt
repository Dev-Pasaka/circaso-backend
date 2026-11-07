package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.integrations.api.CoinMarket
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.UsdRate
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Currency

/**
 * Asha's crypto market tools (decision 2026-11-06): every question about a coin or the market (a price, how it moved,
 * market cap, volume, all-time high, what a coin is, what is trending) is answered from the price feed through
 * [CryptoMarkets], never from what the model remembers. Values are in US dollars and, at the wallet's own exchange
 * rate ([ExchangeRates]), in the user's display currency. The user's own holdings stay on the wallet's tools, whose
 * values use the same feed, so the two always agree.
 *
 * They need no agent token: market data is public and nothing of the user's goes to the feed but the coin's name.
 * A coin the feed can't answer for comes back as a message telling Asha to say so, never to guess.
 */
class CryptoTools(private val markets: CryptoMarkets, private val rates: ExchangeRates) : AgentTools {
    override fun specs(hasAgentToken: Boolean) = listOf(AshaPrompt.getCryptoMarket, AshaPrompt.getTrendingCrypto)

    override fun handles(toolName: String) = toolName == GET_MARKET || toolName == GET_TRENDING

    override fun label(toolName: String, argumentsJson: String): String =
        if (toolName == GET_TRENDING) {
            "Checking what's trending in crypto…"
        } else {
            "Checking ${quoted(argsOf(argumentsJson).coin.ifBlank { "the coin" })} on the market…"
        }

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String> {
        val args = argsOf(argumentsJson)
        val currency =
            args.currency
                ?.trim()
                ?.uppercase()
                ?.takeIf { it.isNotEmpty() } ?: context.currency
        return if (toolName == GET_TRENDING) trending(currency) else market(args.coin, currency, context)
    }

    private suspend fun market(
        coin: String,
        currency: String?,
        context: ToolContext,
    ): Resource<String> {
        if (coin.isBlank()) return ToolOutput.message("Say which coin, by its symbol or name (ETH, Solana).")
        if (context.calledBefore.count { it == GET_MARKET } >= MAX_LOOKUPS_PER_TURN) {
            return ToolOutput.message(TURN_LIMIT)
        }
        val found = markets.coin(coin.trim().take(MAX_COIN_CHARS)) ?: return ToolOutput.message(NO_DATA)
        val rate = rateFor(currency)
        val answer = found.toAnswer(rate)
        return ToolOutput.encode(answer, MarketAnswer.serializer()) {
            it.description?.let { _ ->
                it.copy(description = null)
            }
        }
    }

    private suspend fun trending(currency: String?): Resource<String> {
        val coins = markets.trending()
        if (coins.isEmpty()) return ToolOutput.message(NO_TRENDING)
        val rate = rateFor(currency)
        val answer =
            TrendingAnswer(
                source = SOURCE,
                currency = rate?.currency,
                coins =
                    coins.map {
                        TrendingDto(
                            symbol = it.symbol,
                            name = it.name,
                            marketCapRank = it.marketCapRank,
                            priceUsd = it.usdPrice?.let(::usd),
                            priceInCurrency = rate?.let { r -> it.usdPrice?.let { p -> fiat(p, r) } },
                            change24hPercent = it.change24hPercent?.let(::percent),
                        )
                    },
            )
        return ToolOutput.encode(
            answer,
            TrendingAnswer.serializer(),
        ) { a -> a.coins.half()?.let { a.copy(coins = it) } }
    }

    private suspend fun rateFor(currency: String?): UsdRate? =
        currency?.takeIf { it != USD && CURRENCY.matches(it) }?.let { rates.usdRate(it) }

    private fun CoinMarket.toAnswer(rate: UsdRate?): MarketAnswer {
        fun inCurrency(value: BigDecimal?) = rate?.let { r -> value?.let { fiat(it, r) } }
        return MarketAnswer(
            source = SOURCE,
            symbol = symbol,
            name = name,
            marketCapRank = marketCapRank,
            priceUsd = usdPrice?.let(::usd),
            change24hPercent = change24hPercent?.let(::percent),
            change7dPercent = change7dPercent?.let(::percent),
            marketCapUsd = marketCapUsd?.let(::usd),
            volume24hUsd = volume24hUsd?.let(::usd),
            high24hUsd = high24hUsd?.let(::usd),
            low24hUsd = low24hUsd?.let(::usd),
            allTimeHighUsd = allTimeHighUsd?.let(::usd),
            allTimeHighDate = allTimeHighAt?.let { LocalDate.ofInstant(it, ZoneOffset.UTC).toString() },
            fromAllTimeHighPercent = fromAllTimeHighPercent?.let(::percent),
            currency = rate?.currency,
            priceInCurrency = inCurrency(usdPrice),
            marketCapInCurrency = inCurrency(marketCapUsd),
            allTimeHighInCurrency = inCurrency(allTimeHighUsd),
            rate = rate?.let { RateDto(it.currency, it.rate.toPlainString(), it.asOf.toString()) },
            circulatingSupply = circulatingSupply?.let(::amount),
            maxSupply = maxSupply?.let(::amount),
            categories = categories,
            homepage = homepage,
            description = description,
            updatedAt = updatedAt?.toString(),
        )
    }

    private fun argsOf(argumentsJson: String): CryptoArgs =
        runCatching { CircasoJson.decodeFromString(CryptoArgs.serializer(), argumentsJson) }.getOrNull() ?: CryptoArgs()

    private companion object {
        const val GET_MARKET = "getCryptoMarket"
        const val GET_TRENDING = "getTrendingCrypto"
        const val SOURCE = "CoinGecko"
        const val USD = "USD"
        const val MAX_LOOKUPS_PER_TURN = 5
        const val MAX_COIN_CHARS = 50
        val CURRENCY = Regex("[A-Z]{3}")
        const val NO_DATA =
            "No market data for that coin right now: the name or symbol matched nothing, or the price feed can't be " +
                "reached. Tell the user you couldn't get it right now. Don't guess or use what you remember."
        const val NO_TRENDING =
            "The trending coins couldn't be read right now. Tell the user so. Don't guess or use what you remember."
        const val TURN_LIMIT = "No more coin lookups in this answer. Answer with what you have."

        /** A dollar value: cents from one dollar up, four significant digits below it (a coin at $0.00001234). */
        fun usd(value: BigDecimal): String = money(value, 2)

        fun fiat(dollars: BigDecimal, rate: UsdRate): String {
            val digits =
                runCatching { Currency.getInstance(rate.currency).defaultFractionDigits }
                    .getOrNull()
                    ?.takeIf { it >= 0 } ?: 2
            return money(dollars * rate.rate, digits)
        }

        private fun money(value: BigDecimal, digits: Int): String =
            if (value.abs() >= BigDecimal.ONE || value.signum() == 0) {
                value.setScale(digits, RoundingMode.HALF_UP).toPlainString()
            } else {
                value.round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
            }

        fun percent(value: BigDecimal): String = value.setScale(2, RoundingMode.HALF_UP).toPlainString()

        fun amount(value: BigDecimal): String = value.setScale(0, RoundingMode.HALF_UP).toPlainString()
    }
}

/** `getCryptoMarket` and `getTrendingCrypto` (their schemas are in `AshaPrompt`). */
@Serializable
private data class CryptoArgs(val coin: String = "", val currency: String? = null)

@Serializable
private data class RateDto(val currency: String, val rate: String, val asOf: String)

/** One coin's market data; `…InCurrency` values are in `currency` at the wallet's rate. */
@Serializable
private data class MarketAnswer(
    val source: String,
    val symbol: String,
    val name: String,
    val marketCapRank: Int? = null,
    val priceUsd: String? = null,
    val change24hPercent: String? = null,
    val change7dPercent: String? = null,
    val marketCapUsd: String? = null,
    val volume24hUsd: String? = null,
    val high24hUsd: String? = null,
    val low24hUsd: String? = null,
    val allTimeHighUsd: String? = null,
    val allTimeHighDate: String? = null,
    val fromAllTimeHighPercent: String? = null,
    val currency: String? = null,
    val priceInCurrency: String? = null,
    val marketCapInCurrency: String? = null,
    val allTimeHighInCurrency: String? = null,
    val rate: RateDto? = null,
    val circulatingSupply: String? = null,
    val maxSupply: String? = null,
    val categories: List<String> = emptyList(),
    val homepage: String? = null,
    val description: String? = null,
    val updatedAt: String? = null,
)

@Serializable
private data class TrendingDto(
    val symbol: String,
    val name: String,
    val marketCapRank: Int? = null,
    val priceUsd: String? = null,
    val priceInCurrency: String? = null,
    val change24hPercent: String? = null,
)

@Serializable
private data class TrendingAnswer(val source: String, val currency: String? = null, val coins: List<TrendingDto>)
