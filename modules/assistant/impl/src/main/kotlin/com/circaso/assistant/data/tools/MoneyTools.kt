package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.Calculator
import com.circaso.assistant.domain.support.MoneyNames
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.ExchangeRates
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant

/**
 * Asha's money maths (decision 2026-11-07, Pasaka: she never guesses a price or does money maths herself, in chat or
 * in voice). Three tools, all exact [BigDecimal], none needing an agent token:
 * - `getExchangeRate`: the fiat rate between two currencies, from [ExchangeRates] (ExchangeRate-API's open endpoint,
 *   one table a day, kept in Redis), with its time and source.
 * - `convertAmount`: an amount between any two of fiat and coins: fiat through the rates, a coin through its US dollar
 *   price ([CryptoPrices], the wallet's own CoinGecko prices, kept about a minute; any other coin through
 *   [CryptoMarkets], the same feed), coin to coin through dollars. Every rate and price used comes back with it; a leg
 *   with no rate or price names itself and no number comes back.
 * - `calculate`: [Calculator], for any other sum she needs.
 *
 * The user's own holdings stay on the wallet's tools (getWallet, getAssetValues), so her numbers match the app's.
 * Nothing here calls a vendor itself: the ports keep their tables, and each tool is capped per answer.
 */
class MoneyTools(
    private val rates: ExchangeRates,
    private val prices: CryptoPrices,
    private val markets: CryptoMarkets,
    private val clock: Clock = Clock.systemUTC(),
) : AgentTools {
    override fun specs(hasAgentToken: Boolean) =
        listOf(AshaPrompt.getExchangeRate, AshaPrompt.convertAmount, AshaPrompt.calculate)

    override fun handles(toolName: String) = toolName in NAMES

    override fun label(toolName: String, argumentsJson: String): String {
        val args = argsOf(argumentsJson)
        return when (toolName) {
            GET_RATE -> "Checking the ${nameOf(args.from)} to ${nameOf(args.to)} rate…"
            CONVERT -> "Converting ${nameOf(args.from)} to ${nameOf(args.to)}…"
            else -> "Working it out…"
        }
    }

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        context: ToolContext,
    ): Resource<String> {
        if (context.calledBefore.count { it == toolName } >= MAX_CALLS_PER_TURN) return ToolOutput.message(TURN_LIMIT)
        val args = argsOf(argumentsJson)
        return when (toolName) {
            GET_RATE -> exchangeRate(args.from, args.to ?: context.currency)
            CONVERT -> convert(args.amount, args.from, args.to ?: context.currency)
            else -> calculate(args.expression)
        }
    }

    // ---- getExchangeRate ----

    private suspend fun exchangeRate(from: String?, to: String?): Resource<String> {
        val base = from?.let(MoneyNames::of)
        val quote = to?.let(MoneyNames::of)
        if (base == null || quote == null) return ToolOutput.message(NAME_BOTH)
        if (base is MoneyNames.Coin || quote is MoneyNames.Coin) {
            return ToolOutput.message(
                "${(if (base is MoneyNames.Coin) base else quote).code} is a coin, not a currency: use " +
                    "convertAmount for a coin's worth in money, or getCryptoMarket for its market data.",
            )
        }
        val baseRate = usdRate(base.code)
        val quoteRate = usdRate(quote.code)
        val missing = listOfNotNull(base.takeIf { baseRate == null }, quote.takeIf { quoteRate == null })
        if (baseRate == null || quoteRate == null) {
            return ToolOutput.message(noRate(missing.map { it.code }))
        }
        val rate = quoteRate.value.divide(baseRate.value, RATE_CONTEXT)
        val inverse = baseRate.value.divide(quoteRate.value, RATE_CONTEXT)
        val answer =
            RateAnswer(
                from = base.code,
                to = quote.code,
                rate = plain(rate),
                inverse = plain(inverse),
                says = "1 ${base.code} = ${plain(rate)} ${quote.code}",
                asOf = older(baseRate.asOf, quoteRate.asOf)?.toString(),
                source = RATES_SOURCE,
                legs = listOf(baseRate, quoteRate).filter { it.currency != USD }.map { it.toLeg() },
            )
        return ToolOutput.encode(answer, RateAnswer.serializer()) { null }
    }

    // ---- convertAmount ----

    private suspend fun convert(
        amountText: String?,
        from: String?,
        to: String?,
    ): Resource<String> {
        val amount =
            when (val parsed = amountText?.let(Calculator::evaluate)) {
                is Calculator.Outcome.Value -> parsed.value
                else -> return ToolOutput.message("Give the amount as a number, for example 2500 or 2,500.50.")
            }
        if (amount.signum() < 0) return ToolOutput.message("The amount can't be below zero.")
        val source = from?.let(MoneyNames::of)
        val target = to?.let(MoneyNames::of)
        if (source == null || target == null) return ToolOutput.message(NAME_BOTH)

        val sourceSide = sideOf(source)
        val targetSide = sideOf(target)
        val missing = listOfNotNull(sourceSide.missing, targetSide.missing)
        if (missing.isNotEmpty()) return ToolOutput.message(noAnswer(missing))
        val fromSide = sourceSide as Side.Known
        val toSide = targetSide as Side.Known

        // amount in dollars = amount × (coin: its price | fiat: ÷ its rate); then × target rate or ÷ target price.
        // One division at the end, so nothing is rounded on the way.
        var numerator = amount
        var denominator = BigDecimal.ONE
        when (val leg = fromSide.leg) {
            is Leg.Price -> numerator *= leg.usd
            is Leg.Rate -> denominator *= leg.perUsd
        }
        when (val leg = toSide.leg) {
            is Leg.Price -> denominator *= leg.usd
            is Leg.Rate -> numerator *= leg.perUsd
        }
        val isFiat = toSide.leg is Leg.Rate
        val digits = if (isFiat) FIAT_DIGITS else coinDigits(toSide.code)
        val sameThing = fromSide.code == toSide.code
        val rounded = if (sameThing) amount else numerator.divide(denominator, digits, RoundingMode.HALF_UP)
        val result = if (isFiat) rounded.setScale(FIAT_DIGITS, RoundingMode.HALF_UP) else trimmed(rounded)

        val answer =
            ConvertAnswer(
                amount = plain(amount),
                from = fromSide.code,
                fromName = fromSide.name,
                to = toSide.code,
                toName = toSide.name,
                result = result.toPlainString(),
                says = "${plain(amount)} ${fromSide.code} = ${result.toPlainString()} ${toSide.code}",
                rounding =
                    if (isFiat) "2 decimal places" else "up to $digits decimal places (the coin's own), zeros cut",
                path = "${fromSide.kind} to ${toSide.kind}",
                used =
                    if (sameThing) {
                        emptyList()
                    } else {
                        listOf(fromSide.leg, toSide.leg).filterNot { it.isDollarItself() }.map { it.toUsed() }
                    },
            )
        return ToolOutput.encode(answer, ConvertAnswer.serializer()) { null }
    }

    private sealed interface Leg {
        val asOf: Instant?
        val source: String

        /** One US dollar is [perUsd] of [currency]. */
        data class Rate(
            val currency: String,
            val perUsd: BigDecimal,
            override val asOf: Instant?,
            override val source: String,
        ) : Leg

        /** One [coin] is [usd] US dollars. */
        data class Price(
            val coin: String,
            val usd: BigDecimal,
            override val asOf: Instant?,
            override val source: String,
        ) : Leg

        /** The US dollar's own rate (one), which is no rate worth listing. */
        fun isDollarItself() = this is Rate && currency == USD

        fun toUsed(): UsedDto =
            when (this) {
                is Rate -> {
                    UsedDto("rate", "1 USD = ${plain(perUsd)} $currency", plain(perUsd), asOf?.toString(), source)
                }

                is Price -> {
                    UsedDto("price", "1 $coin = ${plain(usd)} USD", plain(usd), asOf?.toString(), source)
                }
            }
    }

    private sealed interface Side {
        val missing: String?

        data class Known(
            val code: String,
            val name: String?,
            val kind: String,
            val leg: Leg,
        ) : Side {
            override val missing: String? = null
        }

        data class Missing(override val missing: String) : Side
    }

    private suspend fun sideOf(named: MoneyNames.Named): Side =
        when (named) {
            is MoneyNames.Fiat -> {
                usdRate(named.code)?.let {
                    Side.Known(named.code, null, "fiat", Leg.Rate(named.code, it.value, it.asOf, it.source))
                } ?: Side.Missing("no exchange rate for ${named.code} ($RATES_SOURCE)")
            }

            is MoneyNames.Coin -> {
                coinPrice(named.code) ?: Side.Missing("no price for ${named.code} (CoinGecko)")
            }
        }

    /** The wallet's own price first (so a conversion agrees with the app), else the feed's coin search. */
    private suspend fun coinPrice(query: String): Side.Known? {
        val symbol = query.uppercase()
        if (SYMBOL.matches(symbol)) {
            prices.usdPrice(symbol)?.let { usd ->
                val stable = symbol in STABLECOINS
                return Side.Known(
                    symbol,
                    null,
                    "coin",
                    Leg.Price(
                        symbol,
                        usd,
                        if (stable) null else clock.instant(),
                        if (stable) STABLECOIN_SOURCE else PRICES_SOURCE,
                    ),
                )
            }
        }
        val coin = markets.coin(query) ?: return null
        val usd = coin.usdPrice?.takeIf { it.signum() > 0 } ?: return null
        return Side.Known(coin.symbol, coin.name, "coin", Leg.Price(coin.symbol, usd, coin.updatedAt, PRICES_SOURCE))
    }

    // ---- calculate ----

    private fun calculate(expression: String?): Resource<String> =
        when (val outcome = Calculator.evaluate(expression.orEmpty())) {
            is Calculator.Outcome.Refused -> {
                ToolOutput.message(outcome.reason + " Don't work it out yourself: say you couldn't.")
            }

            is Calculator.Outcome.Value -> {
                ToolOutput.encode(
                    CalculateAnswer(
                        expression = expression.orEmpty().trim(),
                        result = outcome.value.toPlainString(),
                        display = Calculator.display(outcome.value),
                        isExact = outcome.isExact,
                    ),
                    CalculateAnswer.serializer(),
                ) { null }
            }
        }

    // ---- shared ----

    private data class FoundRate(
        val currency: String,
        val value: BigDecimal,
        val asOf: Instant?,
        val source: String,
    ) {
        fun toLeg() = LegDto(currency, plain(value), asOf?.toString(), source)
    }

    /** What one US dollar is in [currency]: one for USD itself, else the port's rate. */
    private suspend fun usdRate(currency: String): FoundRate? {
        if (currency == USD) return FoundRate(USD, BigDecimal.ONE, null, RATES_SOURCE)
        val rate = rates.usdRate(currency)?.takeIf { it.rate.signum() > 0 } ?: return null
        return FoundRate(currency, rate.rate, rate.asOf, RATES_SOURCE)
    }

    private fun older(a: Instant?, b: Instant?): Instant? =
        when {
            a == null -> b
            b == null -> a
            else -> minOf(a, b)
        }

    private fun nameOf(text: String?): String =
        when (val named = text?.let(MoneyNames::of)) {
            null -> "your currency"
            is MoneyNames.Fiat -> named.code
            is MoneyNames.Coin -> if (SYMBOL.matches(named.code)) named.code else quoted(named.code)
        }

    private fun argsOf(argumentsJson: String): MoneyArgs =
        runCatching { CircasoJson.decodeFromString(MoneyArgs.serializer(), argumentsJson) }.getOrNull() ?: MoneyArgs()

    internal companion object {
        const val GET_RATE = "getExchangeRate"
        const val CONVERT = "convertAmount"
        const val CALCULATE = "calculate"
        val NAMES = setOf(GET_RATE, CONVERT, CALCULATE)

        const val USD = "USD"
        const val RATES_SOURCE = "ExchangeRate-API"
        const val PRICES_SOURCE = "CoinGecko"
        const val STABLECOIN_SOURCE = "Circaso: a US dollar stablecoin is one US dollar"
        const val FIAT_DIGITS = 2
        const val MAX_CALLS_PER_TURN = 8
        private val STABLECOINS = setOf("USDC", "USDT")
        private val SYMBOL = Regex("[A-Z0-9.]{2,12}")
        private val RATE_CONTEXT = MathContext(12, RoundingMode.HALF_UP)

        /** Each coin's own decimals; a coin we don't know is shown to 8, as Bitcoin is. */
        private val COIN_DIGITS =
            mapOf(
                "BTC" to 8,
                "WBTC" to 8,
                "CBBTC" to 8,
                "ETH" to 18,
                "WETH" to 18,
                "POL" to 18,
                "AVAX" to 18,
                "SOL" to 9,
                "USDC" to 6,
                "USDT" to 6,
                "EURC" to 6,
            )

        fun coinDigits(symbol: String): Int = COIN_DIGITS[symbol] ?: 8

        const val NAME_BOTH = "Say both sides, as currency codes or names (KES, dollars) or coins (BTC, Solana)."
        const val TURN_LIMIT = "No more of these in this answer. Answer with what you have."

        fun noRate(codes: List<String>): String =
            "No exchange rate for ${codes.joinToString(" or ")} right now ($RATES_SOURCE). Tell the user you " +
                "couldn't get the rate right now. Don't estimate or use a rate you remember."

        fun noAnswer(missing: List<String>): String =
            "Can't convert: ${missing.joinToString("; ")}. Tell the user which one you couldn't get right now. " +
                "Don't estimate or work it out yourself."

        /** [value] as written, without trailing zeros or an exponent: 129.250 is 129.25, 1E+2 is 100. */
        fun plain(value: BigDecimal): String = trimmed(value).toPlainString()

        fun trimmed(value: BigDecimal): BigDecimal {
            val stripped = value.stripTrailingZeros()
            return if (stripped.scale() < 0) stripped.setScale(0) else stripped
        }
    }
}

/** `getExchangeRate`, `convertAmount` and `calculate` (their schemas are in `AshaPrompt`). */
@Serializable
private data class MoneyArgs(
    val from: String? = null,
    val to: String? = null,
    val amount: String? = null,
    val expression: String? = null,
)

/** One US dollar in one currency, as the rates source gave it. */
@Serializable
private data class LegDto(
    val usdIn: String,
    val rate: String,
    val asOf: String?,
    val source: String,
)

@Serializable
private data class RateAnswer(
    val from: String,
    val to: String,
    /** One [from] is this many [to]. */
    val rate: String,
    /** One [to] is this many [from]. */
    val inverse: String,
    val says: String,
    /** The older of the two rates' times. */
    val asOf: String?,
    val source: String,
    /** The dollar rates the cross rate was made from. */
    val legs: List<LegDto>,
)

/** A rate or price used by a conversion: `kind` is `rate` (one dollar in a currency) or `price` (a coin in dollars). */
@Serializable
private data class UsedDto(
    val kind: String,
    val says: String,
    val value: String,
    val asOf: String?,
    val source: String,
)

@Serializable
private data class ConvertAnswer(
    val amount: String,
    val from: String,
    val fromName: String? = null,
    val to: String,
    val toName: String? = null,
    val result: String,
    val says: String,
    val rounding: String,
    /** `fiat to fiat`, `coin to fiat`, `fiat to coin` or `coin to coin`. */
    val path: String,
    val used: List<UsedDto>,
)

@Serializable
private data class CalculateAnswer(
    val expression: String,
    /** Exact (or, when [isExact] is false, to 34 significant digits). */
    val result: String,
    /** Rounded for saying: 2 decimals, or 4 significant digits below one. */
    val display: String,
    val isExact: Boolean,
)
