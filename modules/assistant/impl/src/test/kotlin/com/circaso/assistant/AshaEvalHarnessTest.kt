package com.circaso.assistant

import com.circaso.assistant.eval.EvalArm
import com.circaso.assistant.eval.EvalCases
import com.circaso.assistant.eval.EvalGrader
import com.circaso.assistant.eval.EvalRunner
import com.circaso.assistant.eval.Mode
import com.circaso.assistant.eval.Numbers
import com.circaso.assistant.eval.StubModels
import com.circaso.assistant.eval.ToolCallRecord
import kotlinx.coroutines.runBlocking
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The Asha eval's harness, proved without a paid call (decision 2026-11-07). The eval itself runs only on demand
 * (`./gradlew :app:ashaEval`); this keeps its grader honest in CI: through the real agent loop and tools, the oracle
 * passes every case, and a polite non-answer or a confident guess fails, the guess on the invented-number check.
 */
class AshaEvalHarnessTest {
    private fun run(name: String) =
        runBlocking { EvalRunner(concurrency = 8).run(EvalArm(name, StubModels.of(name)!!)) }

    @Test
    fun `there are 30 cases with distinct ids, in chat and voice`() {
        assertEquals(30, EvalCases.all.size)
        assertEquals(
            30,
            EvalCases.all
                .map { it.id }
                .toSet()
                .size,
        )
        assertEquals(8, EvalCases.all.count { it.mode == Mode.VOICE })
    }

    @Test
    fun `the oracle passes every case through the real loop, tools and phone`() {
        val result = run("oracle")

        assertTrue(result.errors.isEmpty(), "errors: ${result.errors}")
        val failed = result.results.filterNot { it.grade.pass }
        assertTrue(
            failed.isEmpty(),
            failed.joinToString(
                "\n",
            ) { r -> "${r.case.id}: ${r.grade.failures.joinToString { "${it.name}: ${it.detail}" }} — ${r.reply}" },
        )
        assertEquals(30, result.results.size)
    }

    @Test
    fun `a polite non-answer passes nothing that needs a number or a tool`() {
        val result = run("null")

        val passed = result.results.filter { it.grade.pass }.map { it.case.id }
        // only the refusals can be passed by refusing, and the judge (not run here) is what grades those
        assertTrue(
            passed.all { id ->
                EvalCases.all.first { it.id == id }.let {
                    it.tools.isEmpty() &&
                        it.answers.isEmpty()
                }
            },
            "passed: $passed",
        )
    }

    @Test
    fun `a confident guess is caught as an invented number on every case`() {
        val result = run("guesser")

        assertTrue(result.results.none { it.grade.pass })
        assertTrue(result.results.all { it.grade.invented.isNotEmpty() })
    }

    @Test
    fun `numbers are read as people write and say them`() {
        fun read(text: String) = Numbers.read(text).map { it.stripTrailingZeros().toPlainString() }

        assertEquals(listOf("12925"), read("KES 12,925"))
        assertEquals(listOf("16156.25"), read("**KES 16,156.25**"))
        assertEquals(listOf("8790000"), read("about KES 8.79 million"))
        assertEquals(listOf("2026", "11", "3"), read("on 2026-11-03"))
        assertEquals(listOf("2925"), read("two thousand nine hundred and twenty-five"))
        assertEquals(listOf("129.25"), read("one hundred twenty nine point two five shillings"))
        assertEquals(listOf("0.5"), read("your half SOL"))
        assertEquals(listOf("100"), read("a hundred dollars"))
    }

    @Test
    fun `a figure matches when it is the exact one rounded at the precision said`() {
        val exact = BigDecimal("12925")
        assertTrue(Numbers.matches(BigDecimal("12925.00"), exact))
        assertTrue(Numbers.matches(BigDecimal("12900"), exact))
        assertFalse(Numbers.matches(BigDecimal("13000"), exact), "too coarse for a text reply")
        assertTrue(Numbers.matches(BigDecimal("13000"), exact, BigDecimal("0.015"), minSignificant = 2), "fine spoken")
        assertFalse(Numbers.matches(BigDecimal("10000"), exact))
        assertFalse(Numbers.matches(BigDecimal("12950"), exact))
        assertTrue(Numbers.matches(BigDecimal("161.56"), BigDecimal("161.5625")))
        assertFalse(Numbers.matches(BigDecimal("130"), BigDecimal("129.25")))
        assertTrue(Numbers.matches(BigDecimal("129"), BigDecimal("129.25")))
    }

    @Test
    fun `a rate guessed and fed to the calculator is caught, though the calculator's answer is real`() {
        val case = EvalCases.all.first { it.id == "M1" }
        val calls =
            listOf(
                ToolCallRecord("getWallet", "{}", """{"totals":[{"asset":"USDC","amount":"152.4"}]}"""),
                ToolCallRecord(
                    "calculate",
                    """{"expression":"152.4 - 2000 / 130"}""",
                    """{"result":"137.015384615","display":"137.02"}""",
                ),
            )

        val grade = EvalGrader.grade(case, "You'd have about 137.02 USDC left.", calls)

        assertFalse(grade.pass)
        assertTrue(grade.invented.any { "130" in it }, grade.invented.toString())
    }
}
