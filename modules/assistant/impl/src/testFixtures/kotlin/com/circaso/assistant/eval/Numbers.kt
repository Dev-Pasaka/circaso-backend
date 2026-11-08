package com.circaso.assistant.eval

import java.math.BigDecimal
import java.math.MathContext

/**
 * Reads the numbers out of a reply, the way a reader would: "KES 12,925", "12925.00 shillings", "8.79 million",
 * "15%" and, for spoken replies, "twelve thousand nine hundred and twenty-five" or "one hundred twenty nine point two
 * five". Dates and times count as numbers too ("3 November" is 3), so every figure in a reply has to come from
 * somewhere. Swahili number words are not read: the voice cases are in English, and text replies use digits.
 */
object Numbers {
    private val DIGITS =
        Regex(
            """(?<![\d.,])(-)?(\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+(?:\.\d+)?)""" +
                """(\s*(?:k\b|K\b|thousand\b|million\b|mn\b|m\b|M\b|billion\b|bn\b|trillion\b))?""",
        )

    private val ONES =
        mapOf(
            "zero" to 0,
            "oh" to 0,
            "one" to 1,
            "two" to 2,
            "three" to 3,
            "four" to 4,
            "five" to 5,
            "six" to 6,
            "seven" to 7,
            "eight" to 8,
            "nine" to 9,
            "ten" to 10,
            "eleven" to 11,
            "twelve" to 12,
            "thirteen" to 13,
            "fourteen" to 14,
            "fifteen" to 15,
            "sixteen" to 16,
            "seventeen" to 17,
            "eighteen" to 18,
            "nineteen" to 19,
        )
    private val TENS =
        mapOf(
            "twenty" to 20,
            "thirty" to 30,
            "forty" to 40,
            "fifty" to 50,
            "sixty" to 60,
            "seventy" to 70,
            "eighty" to 80,
            "ninety" to 90,
        )
    private val SCALES = mapOf("thousand" to 1_000L, "million" to 1_000_000L, "billion" to 1_000_000_000L)

    /** Every number in [text], digits and words. */
    fun read(text: String): List<BigDecimal> = digits(text) + words(DIGITS.replace(text, " "))

    private fun digits(text: String): List<BigDecimal> =
        DIGITS
            .findAll(text)
            .mapNotNull { m ->
                // "2026-11-03" is three numbers, not a subtraction: a minus counts only after a space or the start
                val minus = m.groups[1] != null && (m.range.first == 0 || text[m.range.first - 1].isWhitespace())
                val value =
                    m.groups[2]!!
                        .value
                        .replace(",", "")
                        .toBigDecimalOrNull() ?: return@mapNotNull null
                val scale =
                    when (
                        m.groups[3]
                            ?.value
                            ?.trim()
                            ?.lowercase()
                    ) {
                        null -> BigDecimal.ONE
                        "k", "thousand" -> BigDecimal(1_000)
                        "m", "mn", "million" -> BigDecimal(1_000_000)
                        "bn", "billion" -> BigDecimal(1_000_000_000)
                        "trillion" -> BigDecimal("1000000000000")
                        else -> BigDecimal.ONE
                    }
                (if (minus) value.negate() else value) * scale
            }.toList()

    /** English number words, a run at a time: "two thousand three hundred and five point five" is 2305.5. */
    private fun words(text: String): List<BigDecimal> {
        val tokens =
            text
                .lowercase()
                .replace('-', ' ')
                .split(Regex("[^a-z]+"))
                .filter { it.isNotEmpty() }
        val found = mutableListOf<BigDecimal>()
        var i = 0
        while (i < tokens.size) {
            if (!isNumberWord(tokens[i]) || tokens[i] == "oh" || tokens[i] == "and") {
                // "half" alone is a number ("half a SOL"); "a hundred" starts one
                if (tokens[i] == "half") found += BigDecimal("0.5")
                if (tokens[i] == "a" && i + 1 < tokens.size &&
                    (tokens[i + 1] == "hundred" || tokens[i + 1] in SCALES)
                ) {
                    val (value, next) = run(tokens, i + 1, startWithOne = true)
                    found += value
                    i = next
                    continue
                }
                i++
                continue
            }
            val (value, next) = run(tokens, i, startWithOne = false)
            found += value
            i = next
        }
        return found
    }

    private fun isNumberWord(t: String) = t in ONES || t in TENS || t == "hundred" || t in SCALES || t == "and"

    private fun run(
        tokens: List<String>,
        start: Int,
        startWithOne: Boolean,
    ): Pair<BigDecimal, Int> {
        var total = 0L
        var current = if (startWithOne) 1L else 0L
        var i = start
        var any = startWithOne
        while (i < tokens.size) {
            val t = tokens[i]
            when {
                t in ONES -> {
                    current += ONES.getValue(t)
                }

                t in TENS -> {
                    current += TENS.getValue(t)
                }

                t == "hundred" -> {
                    current = (if (current == 0L) 1 else current) * 100
                }

                t in SCALES -> {
                    total += (if (current == 0L) 1 else current) * SCALES.getValue(t)
                    current = 0
                }

                t == "and" && any && i + 1 < tokens.size && (tokens[i + 1] in ONES || tokens[i + 1] in TENS) -> {
                    Unit
                }

                else -> {
                    break
                }
            }
            any = true
            i++
        }
        var value = BigDecimal(total + current)
        // "point two five": the digits after it, one word each
        if (i < tokens.size && tokens[i] == "point") {
            val decimals = StringBuilder()
            var j = i + 1
            while (j < tokens.size && tokens[j] in ONES && ONES.getValue(tokens[j]) < 10) {
                decimals.append(ONES.getValue(tokens[j]))
                j++
            }
            if (decimals.isNotEmpty()) {
                value = BigDecimal("${total + current}.$decimals")
                i = j
            }
        }
        return value to i
    }

    /**
     * Is [said] a fair reading of [exact]? The same number, or [exact] rounded at [said]'s own precision ("129.3" for
     * 129.25, "12,900" for 12,925) with at least [minSignificant] significant digits and within [relative] of it. In a
     * text reply "130" is not 129.25 (too coarse to quote a rate); spoken, with two digits allowed, it is.
     */
    fun matches(
        said: BigDecimal,
        exact: BigDecimal,
        relative: BigDecimal = BigDecimal("0.01"),
        minSignificant: Int = 3,
    ): Boolean {
        if (said.compareTo(exact) == 0) return true
        if (exact.signum() == 0) return false
        val stripped = said.stripTrailingZeros()
        if (stripped.precision() < minOf(minSignificant, exact.stripTrailingZeros().precision())) return false
        val unit = BigDecimal.ONE.movePointLeft(stripped.scale())
        val off = (said - exact).abs()
        val withinRounding = off <= unit.divide(BigDecimal(2)) || (off < unit && said.precision() >= 3)
        val withinShare = off.divide(exact.abs(), MathContext.DECIMAL64) <= relative
        return withinRounding && withinShare
    }
}
