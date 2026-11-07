package com.circaso.assistant.domain.support

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Asha's calculator (decision 2026-11-07, Pasaka: she never does money maths herself). A small hand-written
 * recursive-descent parser over [BigDecimal]: no script engine, no eval, nothing but this grammar.
 *
 * ```
 * expression := term (("+" | "-") term)*
 * term       := unary (("*" | "×" | "x" | "/" | "÷" | "of") unary)*
 * unary      := ("+" | "-") unary | percent
 * percent    := primary "%"?
 * primary    := number | "(" expression ")"
 * number     := 12 | 12.5 | .5 | 2,400 | 1,234,567.89   (commas only as thousands separators)
 * ```
 *
 * `15%` is 0.15, so "15% of 2,400" is 360. After `+` or `-`, a bare percentage is a share of what is on its left, as
 * on a calculator and as people say it: "2,400 + 15%" is 2,760, "2,400 - 10%" is 2,160. Sums, differences and
 * products are exact; a division that doesn't end (1 ÷ 3) is carried to [PRECISION] significant digits and marked not
 * exact. Anything outside the grammar is refused, as are expressions longer than [MAX_LENGTH] characters, nested deeper
 * than [MAX_DEPTH], or with a number of more than [MAX_DIGITS] digits.
 */
object Calculator {
    const val MAX_LENGTH = 200
    const val MAX_DEPTH = 20
    const val MAX_DIGITS = 30
    private const val PRECISION = 34
    private val CONTEXT = MathContext(PRECISION, RoundingMode.HALF_EVEN)
    private val HUNDRED = BigDecimal(100)

    sealed interface Outcome {
        /** [value] is the result; [isExact] is false when a division had to be cut at 34 significant digits. */
        data class Value(val value: BigDecimal, val isExact: Boolean) : Outcome

        /** The expression can't be worked out; [reason] says why, in words Asha can pass on. */
        data class Refused(val reason: String) : Outcome
    }

    fun evaluate(expression: String): Outcome {
        if (expression.isBlank()) return Outcome.Refused("There is nothing to work out.")
        if (expression.length > MAX_LENGTH) {
            return Outcome.Refused("That is too long to work out in one go (at most $MAX_LENGTH characters).")
        }
        return try {
            val parser = Parser(tokenize(expression))
            val result = parser.parse()
            Outcome.Value(result.value.normalised(), result.isExact)
        } catch (e: Refusal) {
            Outcome.Refused(e.message ?: "That can't be worked out.")
        }
    }

    /** [value] for showing: 2 decimals from one up, 4 significant digits below one (0.0001235), trailing zeros kept. */
    fun display(value: BigDecimal): String =
        if (value.abs() >= BigDecimal.ONE || value.signum() == 0) {
            value.setScale(2, RoundingMode.HALF_UP).toPlainString()
        } else {
            value.round(MathContext(4, RoundingMode.HALF_UP)).stripTrailingZeros().toPlainString()
        }

    private fun BigDecimal.normalised(): BigDecimal {
        val stripped = stripTrailingZeros()
        return if (stripped.scale() < 0) stripped.setScale(0) else stripped
    }

    private class Refusal(message: String) : RuntimeException(message)

    private sealed interface Token {
        data class Number(val value: BigDecimal) : Token

        data class Symbol(val char: Char) : Token
    }

    private val NUMBER = Regex("""\d{1,3}(,\d{3})+(\.\d+)?|\d+(\.\d+)?|\.\d+""")

    private fun tokenize(text: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace() -> {
                    i++
                }

                c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                    val match = NUMBER.matchAt(text, i) ?: throw Refusal("A number there can't be read.")
                    val end = match.range.last + 1
                    if (end < text.length && (text[end].isDigit() || text[end] == ',' || text[end] == '.')) {
                        throw Refusal("A number there can't be read: use commas only between groups of three digits.")
                    }
                    val digits = match.value.replace(",", "")
                    if (digits.count { it.isDigit() } > MAX_DIGITS) throw Refusal("A number there is too long.")
                    tokens += Token.Number(BigDecimal(digits))
                    i = end
                }

                c in "+-*/()%×÷" -> {
                    tokens += Token.Symbol(c)
                    i++
                }

                c == '−' || c == '–' -> {
                    tokens += Token.Symbol('-')
                    i++
                }

                c == 'x' || c == 'X' -> {
                    tokens += Token.Symbol('×')
                    i++
                }

                text.regionMatches(i, "of", 0, 2, ignoreCase = true) &&
                    (i + 2 == text.length || !text[i + 2].isLetter()) -> {
                    tokens += Token.Symbol('o')
                    i += 2
                }

                else -> {
                    throw Refusal("Only numbers, + − × ÷, brackets, % and \"of\" can be worked out.")
                }
            }
        }
        if (tokens.isEmpty()) throw Refusal("There is nothing to work out.")
        return tokens
    }

    /** A value on its way through the parser; [isPercent] is a bare `n%` (for "2,400 + 15%"). */
    private data class Part(val value: BigDecimal, val isExact: Boolean, val isPercent: Boolean = false)

    private class Parser(private val tokens: List<Token>) {
        private var at = 0
        private var depth = 0

        fun parse(): Part {
            val result = expression()
            if (at < tokens.size) throw Refusal("Something after the end can't be read.")
            return result.copy(isPercent = false)
        }

        private fun peek(): Token? = tokens.getOrNull(at)

        private fun isSymbol(vararg chars: Char): Char? {
            val token = peek()
            return if (token is Token.Symbol && token.char in chars) token.char else null
        }

        private fun deeper() {
            if (++depth > MAX_DEPTH) throw Refusal("That is nested too deeply to work out.")
        }

        private fun expression(): Part {
            deeper()
            var left = term()
            while (true) {
                val op = isSymbol('+', '-') ?: break
                at++
                val right = term()
                val amount = if (right.isPercent) left.value * right.value else right.value
                left =
                    Part(
                        if (op == '+') left.value + amount else left.value - amount,
                        left.isExact && right.isExact,
                    )
            }
            depth--
            return left
        }

        private fun term(): Part {
            var left = unary()
            while (true) {
                val op = isSymbol('*', '×', 'o', '/', '÷') ?: break
                at++
                val right = unary()
                left =
                    if (op == '/' || op == '÷') {
                        divide(left, right)
                    } else {
                        Part(left.value * right.value, left.isExact && right.isExact)
                    }
            }
            return left
        }

        private fun divide(left: Part, right: Part): Part {
            if (right.value.signum() == 0) throw Refusal("It can't be worked out: it divides by zero.")
            return try {
                Part(left.value.divide(right.value), left.isExact && right.isExact)
            } catch (_: ArithmeticException) {
                Part(left.value.divide(right.value, CONTEXT), isExact = false)
            }
        }

        private fun unary(): Part {
            val sign = isSymbol('+', '-')
            if (sign != null) {
                at++
                deeper()
                val inner = unary()
                depth--
                return if (sign == '-') inner.copy(value = inner.value.negate()) else inner
            }
            return percent()
        }

        private fun percent(): Part {
            val value = primary()
            if (isSymbol('%') != null) {
                at++
                return Part(value.value.divide(HUNDRED), value.isExact, isPercent = true)
            }
            return value
        }

        private fun primary(): Part {
            val token = peek() ?: throw Refusal("The expression ends too soon.")
            if (token is Token.Number) {
                at++
                return Part(token.value, isExact = true)
            }
            if (isSymbol('(') == null) throw Refusal("Something there is out of place.")
            at++
            val inner = expression()
            if (isSymbol(')') == null) throw Refusal("A bracket is opened and never closed.")
            at++
            return inner.copy(isPercent = false)
        }
    }
}
