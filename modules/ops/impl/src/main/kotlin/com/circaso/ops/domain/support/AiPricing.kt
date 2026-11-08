package com.circaso.ops.domain.support

import com.circaso.assistant.api.TokenRates
import com.circaso.assistant.api.UsageRates
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** One model's list prices, US dollars per million tokens. */
data class ModelPrice(val model: String, val inputPerMTok: BigDecimal, val outputPerMTok: BigDecimal)

/**
 * The price table behind the AI cost estimate (decision 2026-11-08), from configuration (`OPS_AI_*`). Defaults are
 * Anthropic's list prices on 2026-11-08, to be updated when they change: Claude Haiku 5.5 at $0.10 / $0.50 per million
 * input / output tokens for prompts up to 100K tokens (over 100K it is $0.50 / $2.50, but the daily totals can't tell a
 * long prompt from a short one, so the estimate assumes prompts under 100K); a cache write is 1.25× input (Asha caches
 * for 5 minutes, Koog's default `cache_control`; the 1-hour cache would be 2×), a cache read 0.1× input; web searches
 * $10 per 1,000.
 *
 * Every figure is an exact decimal: tokens × price is computed in [BigDecimal] and only the shown result is rounded,
 * once, to micro-dollars ([micros]).
 */
class AiPricing(
    val models: List<ModelPrice>,
    val cacheReadMultiplier: BigDecimal,
    val cacheWriteMultiplier: BigDecimal,
    val webSearchPer1000: BigDecimal,
    val asOf: LocalDate,
) {
    private val byModel = models.associateBy { it.model }

    fun priceOf(model: String): ModelPrice? = byModel[model]

    /** The dollars [model]'s tokens cost; null when the model has no price (shown as unpriced, never as free). */
    fun tokenCost(
        model: String,
        input: Long,
        cacheRead: Long,
        cacheWrite: Long,
        output: Long,
    ): BigDecimal? {
        val rates = ratesOf(priceOf(model) ?: return null)
        return rates.input * input + rates.cacheRead * cacheRead + rates.cacheWrite * cacheWrite + rates.output * output
    }

    fun webSearchCost(searches: Long): BigDecimal =
        webSearchPer1000.multiply(BigDecimal.valueOf(searches)).movePointLeft(3)

    /** Dollars per token and per search, as the assistant orders its heaviest users by. */
    fun rates(): UsageRates =
        UsageRates(
            byModel = models.associate { it.model to ratesOf(it) },
            perWebSearch = webSearchPer1000.movePointLeft(3),
        )

    fun cacheReadPerMTok(price: ModelPrice): BigDecimal = price.inputPerMTok.multiply(cacheReadMultiplier)

    fun cacheWritePerMTok(price: ModelPrice): BigDecimal = price.inputPerMTok.multiply(cacheWriteMultiplier)

    private fun ratesOf(price: ModelPrice) =
        TokenRates(
            input = price.inputPerMTok.movePointLeft(6),
            cacheRead = cacheReadPerMTok(price).movePointLeft(6),
            cacheWrite = cacheWritePerMTok(price).movePointLeft(6),
            output = price.outputPerMTok.movePointLeft(6),
        )

    private operator fun BigDecimal.times(count: Long): BigDecimal = multiply(BigDecimal.valueOf(count))

    companion object {
        const val DEFAULT_MODELS = "claude-haiku-5-5=0.10/0.50"
        val DEFAULT_CACHE_READ: BigDecimal = BigDecimal("0.1")
        val DEFAULT_CACHE_WRITE: BigDecimal = BigDecimal("1.25")
        val DEFAULT_WEB_SEARCH_PER_1000: BigDecimal = BigDecimal("10")
        val DEFAULT_AS_OF: LocalDate = LocalDate.of(2026, 11, 8)

        /** Dollars to whole micro-dollars (1 USD = 1_000_000), half up, once. */
        fun micros(usd: BigDecimal): Long =
            usd.movePointRight(MICRO_DIGITS).setScale(0, RoundingMode.HALF_UP).longValueExact()

        /** [total] split evenly over [count], in micro-dollars; null for nobody. */
        fun microsPer(total: BigDecimal, count: Long): Long? =
            if (count <= 0) null else micros(total.divide(BigDecimal.valueOf(count), MathContext.DECIMAL128))

        /**
         * `model=input/output[,model=input/output…]`, dollars per million tokens: `claude-haiku-5-5=0.10/0.50`. Anything
         * else stops startup: a wrong price must never quietly become a wrong cost.
         */
        fun parseModels(raw: String): List<ModelPrice> {
            val models =
                raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.map { entry ->
                    val (model, prices) =
                        entry.split('=').takeIf { it.size == 2 }?.map { it.trim() }
                            ?: invalid("OPS_AI_PRICES", entry)
                    val (input, output) =
                        prices.split('/').takeIf { it.size == 2 }?.map { decimal("OPS_AI_PRICES", it.trim()) }
                            ?: invalid("OPS_AI_PRICES", entry)
                    if (model.isEmpty()) invalid("OPS_AI_PRICES", entry)
                    ModelPrice(model, input, output)
                }
            if (models.map { it.model }.toSet().size != models.size) invalid("OPS_AI_PRICES", "a model listed twice")
            return models
        }

        /** A price or multiplier: a decimal of zero or more. */
        fun decimal(name: String, raw: String): BigDecimal =
            raw.toBigDecimalOrNull()?.takeIf { it.signum() >= 0 } ?: invalid(name, raw)

        private fun invalid(name: String, raw: String): Nothing =
            throw IllegalStateException("Configuration '$name' is not valid (at '$raw')")

        private const val MICRO_DIGITS = 6
    }
}
