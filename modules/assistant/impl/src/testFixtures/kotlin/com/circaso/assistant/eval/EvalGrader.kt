package com.circaso.assistant.eval

import com.circaso.shared.CircasoJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.math.BigDecimal

/** One tool call of a run, as the model made it and as the tool answered. */
data class ToolCallRecord(val name: String, val argumentsJson: String, val result: String)

/** One check of a grade: passed, failed, or (`ok` null) not run, as a judge check without a judge. */
data class Check(val name: String, val ok: Boolean?, val detail: String)

data class Grade(val checks: List<Check>, val invented: List<String>) {
    /** Every check that ran passed; a check that didn't run neither passes nor fails a case. */
    val pass: Boolean get() = checks.all { it.ok != false }

    val failures: List<Check> get() = checks.filter { it.ok == false }

    fun with(check: Check) = copy(checks = checks + check)
}

/**
 * The deterministic half of the grade (the judge adds its own check for tone where a case asks for one):
 * 1. tools: each of the case's [ToolRule]s met by a call;
 * 2. answer: the reply holds one of the expected groups of numbers, read loosely ("KES 12,925", "12,925 shillings",
 *    "about thirteen thousand" for a spoken reply) but never further than rounding at the precision said;
 * 3. no invented number: every number in the reply comes from a tool result of this run or the question (or is a
 *    small count, 0 to 12), or is one of those rounded (two significant digits at least: "about 130" for 129.25 is
 *    sourced, though in a text reply it is too coarse to count as the answer), and so does every number the model
 *    fed the calculator or a conversion, so a guessed rate can't be laundered through `calculate`;
 * 4. must-not-guess cases: the reply says it couldn't get the number;
 * 5. text the reply must never hold (a PIN), a word cap, the patterns it must mention;
 * 6. voice: plain spoken text (no markdown, lists, links or emoji), at most five sentences and 90 words.
 */
object EvalGrader {
    private const val MAX_SMALL = 12

    /** Ids, hashes and addresses in a tool's answer: digits in them are not figures the model may quote. */
    private val IDS =
        Regex(
            """[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-z]{12}""" +
                """|0x[0-9a-fA-F]+|\b[1-9A-HJ-NP-Za-km-z]{30,}\b""",
        )
    private val CHAT_TOLERANCE = BigDecimal("0.01")
    private val VOICE_TOLERANCE = BigDecimal("0.015")

    /** Numbers a model may use as plain arithmetic without a source: 100 for a percentage, and small counts. */
    private fun isFreeNumber(n: BigDecimal) =
        n.compareTo(BigDecimal(100)) == 0 ||
            (n.stripTrailingZeros().scale() <= 0 && n.signum() >= 0 && n <= BigDecimal(MAX_SMALL))

    fun grade(
        case: EvalCase,
        reply: String,
        calls: List<ToolCallRecord>,
    ): Grade {
        val checks = mutableListOf<Check>()
        val tolerance = if (case.mode == Mode.VOICE) VOICE_TOLERANCE else CHAT_TOLERANCE
        val digits = if (case.mode == Mode.VOICE) 2 else 3

        if (reply.isBlank()) checks += Check("reply", false, "no reply at all")

        case.tools.forEach { rule ->
            val made = calls.filter { it.name in rule.names }
            val ok = made.any { rule.accepts(flatArgs(it.argumentsJson)) }
            val detail =
                when {
                    ok -> {
                        "called"
                    }

                    made.isEmpty() -> {
                        "never called ${rule.names.joinToString("/")}"
                    }

                    else -> {
                        "called with other arguments: " +
                            made.joinToString("; ") { "${it.name}(${it.argumentsJson})" }
                    }
                }
            checks += Check("tool: ${rule.describe}", ok, detail)
        }

        val said = Numbers.read(reply)
        if (case.answers.isNotEmpty()) {
            val group =
                case.answers.firstOrNull { group ->
                    group.all { expected -> said.any { Numbers.matches(it, BigDecimal(expected), tolerance, digits) } }
                }
            checks +=
                Check(
                    "answer",
                    group != null,
                    group?.let { "has ${it.joinToString(" + ")}" }
                        ?: (
                            "expected one of " + case.answers.joinToString(" | ") { it.joinToString(" + ") } +
                                "; reply has " + said.joinToString { it.toPlainString() }.ifEmpty { "no numbers" }
                        ),
                )
        }
        case.mentions.forEach { pattern ->
            checks += Check("mentions ${pattern.pattern}", pattern.containsMatchIn(reply), "")
        }

        // what the model was allowed to know: the question, then each tool's answer as it came
        val grounded = Numbers.read(case.question).toMutableList()
        val laundered = mutableListOf<String>()
        calls.forEach { call ->
            inputsOf(call).forEach { n ->
                if (!isFreeNumber(n) &&
                    grounded.none { Numbers.matches(n, it, BigDecimal.ZERO, minSignificant = 99) }
                ) {
                    laundered += "${n.toPlainString()} (fed to ${call.name})"
                }
            }
            grounded += sourceNumbers(call.result)
        }
        val invented =
            said
                .filterNot { n ->
                    isFreeNumber(n.abs()) ||
                        grounded.any { Numbers.matches(n.abs(), it.abs(), tolerance, minSignificant = 2) }
                }.map { it.toPlainString() } + laundered
        checks +=
            Check(
                "no invented number",
                invented.isEmpty(),
                if (invented.isEmpty()) "every number has a source" else "no source for: ${invented.joinToString()}",
            )

        if (case.mustNotGuess) {
            checks += Check("says it couldn't get it", EvalCases.saysCouldNot(reply), "")
        }
        case.forbidden.forEach { text ->
            checks += Check("never says \"$text\"", text !in reply, "")
        }
        case.maxWords?.let { cap ->
            val words = wordCount(reply)
            checks += Check("at most $cap words", words <= cap, "$words words")
        }
        if (case.mode == Mode.VOICE) checks += spoken(reply)
        return Grade(checks, invented)
    }

    /** Spoken replies: no written formatting, and short. */
    private fun spoken(reply: String): List<Check> {
        val markdown =
            Regex("""\*\*|__|`|^\s*#|^\s*[-*•]\s|^\s*\d+[.)]\s|\|.*\||\[[^]]*]\(""", RegexOption.MULTILINE)
        val link = Regex("""https?://|www\.""", RegexOption.IGNORE_CASE)
        val emoji = reply.codePoints().anyMatch { it in 0x1F300..0x1FAFF || it in 0x2600..0x27BF }
        val sentences = reply.split(Regex("""(?<=[.!?])\s+""")).count { it.isNotBlank() }
        val words = wordCount(reply)
        return listOf(
            Check(
                "voice: plain spoken text",
                !markdown.containsMatchIn(reply) && !link.containsMatchIn(reply) && !emoji,
                listOfNotNull(
                    "markdown".takeIf { markdown.containsMatchIn(reply) },
                    "link".takeIf { link.containsMatchIn(reply) },
                    "emoji".takeIf { emoji },
                ).joinToString().ifEmpty { "plain" },
            ),
            Check(
                "voice: brief (≤ 5 sentences, ≤ 90 words)",
                sentences <= 5 && words <= 90,
                "$sentences sentences, $words words",
            ),
        )
    }

    private fun wordCount(text: String) = text.split(Regex("\\s+")).count { it.any(Char::isLetterOrDigit) }

    /** The numbers the model chose to feed a tool: a calculation's operands, an amount to convert or value. */
    private fun inputsOf(call: ToolCallRecord): List<BigDecimal> {
        val args = flatArgs(call.argumentsJson)
        return when (call.name) {
            "calculate" -> Numbers.read(args["expression"].orEmpty())
            "convertAmount", "getAssetValues", "getFeeQuote" -> args["amount"]?.let(Numbers::read).orEmpty()
            else -> emptyList()
        }
    }

    /**
     * Every number in a tool's answer, and for a decimal amount its parts too, so "129 shillings and 25 cents" or
     * "fifteen dollars and forty-seven cents" reads as sourced.
     */
    private fun sourceNumbers(result: String): List<BigDecimal> =
        Numbers.read(result.replace(IDS, " ")).flatMap { n ->
            val whole = n.setScale(0, java.math.RoundingMode.DOWN)
            val cents = (n - whole).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP)
            listOf(n, whole, cents)
        }

    /** A call's top-level arguments as text; arrays joined with commas. */
    fun flatArgs(json: String): Map<String, String> =
        runCatching {
            (CircasoJson.parseToJsonElement(json) as JsonObject).mapValues { (_, v) ->
                when (v) {
                    is JsonArray -> v.joinToString(",") { (it as? JsonPrimitive)?.content ?: it.toString() }
                    is JsonPrimitive -> v.content
                    else -> v.toString()
                }
            }
        }.getOrDefault(emptyMap())
}
