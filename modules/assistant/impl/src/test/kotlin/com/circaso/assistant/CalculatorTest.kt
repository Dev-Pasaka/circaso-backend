package com.circaso.assistant

import com.circaso.assistant.domain.support.Calculator
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/** Asha's calculator (decision 2026-11-07): a hand-written BigDecimal parser, exact, and nothing but its grammar. */
class CalculatorTest {
    private fun value(expression: String): Calculator.Outcome.Value =
        when (val outcome = Calculator.evaluate(expression)) {
            is Calculator.Outcome.Value -> outcome
            is Calculator.Outcome.Refused -> fail("$expression was refused: ${outcome.reason}")
        }

    private fun result(expression: String): String = value(expression).value.toPlainString()

    private fun refused(expression: String): String =
        when (val outcome = Calculator.evaluate(expression)) {
            is Calculator.Outcome.Refused -> outcome.reason
            is Calculator.Outcome.Value -> fail("$expression gave ${outcome.value}")
        }

    @Test
    fun `the four operations, exactly`() {
        assertEquals("5", result("2 + 3"))
        assertEquals("-1", result("2 - 3"))
        assertEquals("6", result("2 * 3"))
        assertEquals("6", result("2 × 3"))
        assertEquals("6", result("2x3"))
        assertEquals("12.5", result("100 / 8"))
        assertEquals("12.5", result("100 ÷ 8"))
        assertEquals("1", result("4 − 3"), "a typographic minus")
        assertEquals("0.3", result("0.1 + 0.2"), "never 0.30000000000000004")
        assertEquals("0.5", result(".25 * 2"))
    }

    @Test
    fun `precedence, associativity and brackets`() {
        assertEquals("14", result("2 + 3 * 4"))
        assertEquals("20", result("(2 + 3) * 4"))
        assertEquals("3", result("10 - 4 - 3"), "left to right")
        assertEquals("2", result("16 / 4 / 2"), "left to right")
        assertEquals("2", result("-3 + 5"))
        assertEquals("-8", result("-(3 + 5)"))
        assertEquals("8", result("--8"))
        assertEquals("1.5", result("2 × 3 ÷ 4"))
        assertEquals("21", result("((1 + 2) * (3 + 4))"))
    }

    @Test
    fun `numbers with thousands commas and decimals`() {
        assertEquals("2469135.78", result("1,234,567.89 * 2"))
        assertEquals("4800", result("2,400 + 2,400"))
        assertEquals("2400.5", result("2400.50"))
    }

    @Test
    fun `percentages`() {
        assertEquals("360", result("15% of 2,400"))
        assertEquals("360", result("15 % OF 2400"))
        assertEquals("360", result("2400 * 15%"))
        assertEquals("0.15", result("15%"))
        assertEquals("2760", result("2,400 + 15%"), "a share of what is on the left")
        assertEquals("2160", result("2,400 - 10%"))
        assertEquals("0.25", result("50% * 50%"))
        assertEquals("2400.15", result("2400 + (15%)"), "in brackets it is just 0.15")
        assertEquals("54", result("(10% of 200) + 34"))
    }

    @Test
    fun `a division that doesn't end is cut at 34 digits and marked not exact`() {
        val third = value("1 / 3")
        assertFalse(third.isExact)
        assertEquals("0.3333333333333333333333333333333333", third.value.toPlainString())
        assertEquals("0.3333", Calculator.display(third.value))

        val split = value("(1200 + 850.50) / 3")
        assertTrue(split.isExact)
        assertEquals("683.5", split.value.toPlainString())
        assertEquals("683.50", Calculator.display(split.value))
    }

    @Test
    fun `the display rounds to cents, or four significant digits below one`() {
        assertEquals("3.33", Calculator.display(BigDecimal("3.333333")))
        assertEquals("2760.00", Calculator.display(BigDecimal("2760")))
        assertEquals("0.0001235", Calculator.display(BigDecimal("0.00012345")))
        assertEquals("0.00", Calculator.display(BigDecimal.ZERO))
    }

    @Test
    fun `dividing by zero is refused`() {
        assertTrue("divides by zero" in refused("5 / 0"))
        assertTrue("divides by zero" in refused("5 / (2 - 2)"))
        assertTrue("divides by zero" in refused("5 ÷ 0%"))
    }

    @Test
    fun `anything outside the grammar is refused`() {
        listOf(
            "",
            "   ",
            "2 +",
            "* 2",
            "(1 + 2",
            "1 + 2)",
            ")(",
            "()",
            "2 ^ 3",
            "2 ** 3",
            "abc",
            "1e5",
            "Runtime.getRuntime().exec(\"ls\")",
            "java.lang.System.exit(0)",
            "1,23",
            "12,34,567",
            "2..3",
            "2.3.4",
            "KES 200",
            "200 shillings",
            "$5 + 2",
            "5 5",
            "15% off 2400",
            "sqrt(4)",
        ).forEach { expression ->
            val outcome = Calculator.evaluate(expression)
            assertTrue(outcome is Calculator.Outcome.Refused, "'$expression' should be refused, gave $outcome")
        }
    }

    @Test
    fun `length, depth and digits are capped`() {
        assertTrue("too long" in refused("1+".repeat(100) + "1"))
        assertEquals("1", result("(".repeat(15) + "1" + ")".repeat(15)))
        assertTrue("nested too deeply" in refused("(".repeat(25) + "1" + ")".repeat(25)))
        assertTrue("nested too deeply" in refused("-".repeat(30) + "1"))
        assertTrue("too long" in refused("1".repeat(31)))
        assertEquals("1".repeat(30), result("1".repeat(30)))
    }
}
