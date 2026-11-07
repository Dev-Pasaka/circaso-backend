package com.circaso.assistant.eval

import com.circaso.assistant.domain.support.AshaPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.sqrt

/**
 * Writes a run to disk and as one markdown report:
 * - `report.md`: score per arm (overall, chat, voice, with a 95% interval), invented numbers, errors, tokens and cost;
 *   the pass bar per mode; one row per question; every failure with its reasons and transcript;
 * - `<arm>/results.jsonl`: one row per case and repetition (grade, checks, tokens, latency);
 * - `<arm>/errors.jsonl`: runs that produced nothing to grade, apart from the scores;
 * - `<arm>/traces/<case>_rep<k>.json`: the whole conversation, the system prompt and every tool call included.
 *
 * Everything written goes through [scrub], which blanks any secret value the run was given, wherever it appears.
 */
class EvalReport(
    private val outDir: File,
    private val scrub: (String) -> String,
    private val judgeName: String?,
    private val reps: Int,
    private val skipped: List<String> = emptyList(),
    /** The pass bar: at least this share right, and no invented number at all. */
    private val bar: BigDecimal = BigDecimal("0.95"),
) {
    fun write(arms: List<ArmResult>): File {
        outDir.mkdirs()
        arms.forEach(::writeArm)
        val report = File(outDir, "report.md")
        report.writeText(scrub(markdown(arms)))
        return report
    }

    private fun writeArm(arm: ArmResult) {
        val dir = File(outDir, arm.arm.name).apply { mkdirs() }
        val traces = File(dir, "traces").apply { mkdirs() }
        File(dir, "results.jsonl").writeText(
            arm.results.sortedWith(compareBy({ it.case.id }, { it.rep })).joinToString("") {
                scrub(row(it).toString()) +
                    "\n"
            },
        )
        File(dir, "errors.jsonl").writeText(
            arm.errors.joinToString("") { e ->
                scrub(
                    buildJsonObject {
                        put("prompt_id", e.caseId)
                        put("rep", e.rep)
                        put("failure_class", e.kind)
                        put("message", e.message)
                        put("attempts", e.attempts)
                        put("usage", usage(e.tokens))
                    }.toString(),
                ) + "\n"
            },
        )
        arm.results.forEach { r ->
            File(traces, "${r.case.id}_rep${r.rep}.json").writeText(
                scrub(
                    buildJsonArray {
                        r.trace.forEach { t ->
                            add(
                                buildJsonObject {
                                    put("role", t.role)
                                    t.name?.let { put("name", it) }
                                    put("content", t.content)
                                },
                            )
                        }
                    }.toString(),
                ),
            )
        }
    }

    private fun row(r: CaseResult): JsonObject =
        buildJsonObject {
            put("prompt_id", r.case.id)
            put("prompt", r.case.question)
            put(
                "tags",
                JsonArray(
                    listOf(
                        r.case.category,
                        r.case.mode.name
                            .lowercase(),
                    ).map(::JsonPrimitive),
                ),
            )
            put("rep", r.rep)
            put("status", "ok")
            put("grade", r.grade.pass)
            put("invented", JsonArray(r.grade.invented.map(::JsonPrimitive)))
            put(
                "checks",
                buildJsonArray {
                    r.grade.checks.forEach { c ->
                        add(
                            buildJsonObject {
                                put("name", c.name)
                                put("ok", c.ok?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull)
                                put("detail", c.detail)
                            },
                        )
                    }
                },
            )
            put("reply", r.reply)
            put("tool_calls", r.calls.size)
            put("tools", JsonArray(r.calls.map { JsonPrimitive(it.name) }))
            put("latency_s", r.latencyMillis / 1000.0)
            put("attempts", r.attempts)
            put("model", r.servedModels.joinToString())
            put("usage", usage(r.tokens))
            if (judgeName != null && r.judgeTokens.calls > 0) {
                put("judge_model", judgeName)
                put("judge_usage", usage(r.judgeTokens))
            }
        }

    private fun usage(t: Tokens) =
        buildJsonObject {
            put("model_calls", t.calls)
            put("input_tokens", t.input)
            put("output_tokens", t.output)
            put("cache_read_input_tokens", t.cacheRead)
            put("cache_creation_input_tokens", t.cacheWrite)
        }

    // ---- the markdown ----

    private fun markdown(arms: List<ArmResult>): String =
        buildString {
            appendLine("# Asha eval")
            appendLine()
            appendLine(
                "Prompt `${AshaPrompt.VERSION}`, ${EvalCases.all.size} cases (${count(Mode.CHAT)} chat, " +
                    "${count(Mode.VOICE)} voice), $reps repetition(s) each, the real `AshaAgent` loop and tools over " +
                    "the fixed eval world (today ${EvalWorld.TODAY}, display currency ${EvalWorld.CURRENCY}).",
            )
            appendLine()
            appendLine(
                "Pass bar: at least ${pct(bar)} of cases right **and zero invented numbers**, per mode. A run " +
                    "that produced no answer (provider error, timeout) is an error, not a wrong answer, and is " +
                    "left out " +
                    "of the score.",
            )
            if (skipped.isNotEmpty()) {
                appendLine()
                appendLine("**Skipped arms:**")
                skipped.forEach { appendLine("- $it") }
            }
            appendLine()
            summary(arms)
            verdicts(arms)
            perQuestion(arms)
            failures(arms)
            method()
        }

    private fun count(mode: Mode) = EvalCases.all.count { it.mode == mode }

    private fun StringBuilder.summary(arms: List<ArmResult>) {
        appendLine("## Scores")
        appendLine()
        appendLine(
            "| Arm | All | Chat | Voice | 95% interval (all) | Invented numbers | Errors | Model calls | Input " +
                "tok | Cached read | Output tok | Cost (USD) | Judge cost (USD) | Avg latency |",
        )
        appendLine("|---|---|---|---|---|---|---|---|---|---|---|---|---|---|")
        arms.forEach { a ->
            val all = a.results
            val tokens = all.fold(Tokens()) { s, r -> s + r.tokens } + a.errors.fold(Tokens()) { s, e -> s + e.tokens }
            val judgeTokens = all.fold(Tokens()) { s, r -> s + r.judgeTokens }
            val invented = all.count { it.grade.invented.isNotEmpty() }
            val (lo, hi) = wilson(all.count { it.grade.pass }, all.size)
            val cost =
                a.arm.prices?.let { "$" + tokens.cost(it).setScale(4, RoundingMode.HALF_UP).toPlainString() }
                    ?: "not priced"
            val judgeCost =
                judgePrices?.takeIf { judgeTokens.calls > 0 }?.let {
                    "$" +
                        judgeTokens.cost(it).setScale(4, RoundingMode.HALF_UP).toPlainString()
                }
                    ?: "-"
            val latency = if (all.isEmpty()) "-" else "%.1f s".format(all.map { it.latencyMillis }.average() / 1000)
            appendLine(
                "| ${a.arm.name} | ${score(all)} | ${score(all.filter { it.case.mode == Mode.CHAT })} | " +
                    "${score(all.filter { it.case.mode == Mode.VOICE })} | ${pct(lo)}–${pct(hi)} | $invented | " +
                    "${a.errors.size} | ${tokens.calls} | ${tokens.input} | ${tokens.cacheRead} | " +
                    "${tokens.output} | $cost | $judgeCost | $latency |",
            )
        }
        appendLine()
        arms.mapNotNull { a -> a.arm.note?.let { "- **${a.arm.name}**: $it" } }.takeIf { it.isNotEmpty() }?.let {
            it.forEach(::appendLine)
            appendLine()
        }
        val unjudged = arms.sumOf { a -> a.results.count { r -> r.grade.checks.any { it.ok == null } } }
        if (unjudged > 0) {
            appendLine(
                "_$unjudged run(s) had a judge check that did not run (no judge configured, or the judge failed); " +
                    "they are graded on the deterministic checks alone._",
            )
            appendLine()
        }
    }

    /** Judge prices, when the judge is a priced model; set by the caller. */
    var judgePrices: Prices? = null

    private fun StringBuilder.verdicts(arms: List<ArmResult>) {
        appendLine("## Pass bar per mode")
        appendLine()
        appendLine("| Arm | Chat | Voice |")
        appendLine("|---|---|---|")
        arms.forEach { a ->
            appendLine("| ${a.arm.name} | ${verdict(a, Mode.CHAT)} | ${verdict(a, Mode.VOICE)} |")
        }
        appendLine()
    }

    fun passes(arm: ArmResult, mode: Mode): Boolean {
        val runs = arm.results.filter { it.case.mode == mode }
        if (runs.isEmpty()) return false
        val share = BigDecimal(runs.count { it.grade.pass }).divide(BigDecimal(runs.size), 4, RoundingMode.HALF_UP)
        return share >= bar && runs.none { it.grade.invented.isNotEmpty() }
    }

    private fun verdict(arm: ArmResult, mode: Mode): String {
        val runs = arm.results.filter { it.case.mode == mode }
        if (runs.isEmpty()) return "no runs"
        val invented = runs.count { it.grade.invented.isNotEmpty() }
        return (
            if (passes(
                    arm,
                    mode,
                )
            ) {
                "**passes**"
            } else {
                "fails"
            }
        ) + " (${score(runs)}, $invented with invented numbers" +
            (
                arm.errors
                    .count { e ->
                        EvalCases.all.firstOrNull { it.id == e.caseId }?.mode == mode
                    }.takeIf { it > 0 }
                    ?.let { ", $it errors" }
                    ?: ""
            ) +
            ")"
    }

    private fun StringBuilder.perQuestion(arms: List<ArmResult>) {
        appendLine("## Per question")
        appendLine()
        appendLine("| Id | Category | Mode | Question | " + arms.joinToString(" | ") { it.arm.name } + " |")
        appendLine("|---|---|---|---|" + arms.joinToString("") { "---|" })
        EvalCases.all.forEach { case ->
            val cells =
                arms.map { a ->
                    val runs = a.results.filter { it.case.id == case.id }
                    val errors = a.errors.count { it.caseId == case.id }
                    when {
                        runs.isEmpty() && errors > 0 -> {
                            "error"
                        }

                        runs.isEmpty() -> {
                            "-"
                        }

                        else -> {
                            val ok = runs.count { it.grade.pass }
                            val mark = if (ok == runs.size) "pass" else "**FAIL**"
                            val why =
                                runs.firstOrNull { !it.grade.pass }?.grade?.failures?.joinToString(
                                    "; ",
                                ) { it.name }
                            "$mark $ok/${runs.size}" + (why?.let { " ($it)" } ?: "") +
                                (if (errors > 0) " +$errors err" else "")
                        }
                    }
                }
            appendLine(
                "| ${case.id} | ${case.category} | ${case.mode.name.lowercase()} | ${cell(
                    case.question,
                )} | ${cells.joinToString(" | ") { cell(it) }} |",
            )
        }
        appendLine()
    }

    private fun StringBuilder.failures(arms: List<ArmResult>) {
        appendLine("## Failures")
        appendLine()
        var any = false
        arms.forEach { a ->
            a.results.filterNot { it.grade.pass }.sortedWith(compareBy({ it.case.id }, { it.rep })).forEach { r ->
                any = true
                appendLine(
                    "### ${a.arm.name} · ${r.case.id} (rep ${r.rep}) · ${r.case.category}, " +
                        "${r.case.mode.name.lowercase()}",
                )
                appendLine()
                r.grade.failures.forEach { appendLine("- **${it.name}**: ${it.detail}") }
                appendLine()
                appendLine("<details><summary>Transcript</summary>")
                appendLine()
                appendLine(fence(transcript(r)))
                appendLine()
                appendLine("</details>")
                appendLine()
            }
            a.errors.forEach { e ->
                any = true
                appendLine("### ${a.arm.name} · ${e.caseId} (rep ${e.rep}) · error: ${e.kind}")
                appendLine()
                appendLine("- ${e.message} (after ${e.attempts} attempt(s))")
                appendLine()
            }
        }
        if (!any) appendLine("None.")
        appendLine()
    }

    /** The conversation without the system prompt (it is the same for every case; see the traces). */
    private fun transcript(r: CaseResult): String =
        r.trace.filter { it.role != "system" }.joinToString("\n") { t ->
            when (t.role) {
                "tool_call" -> "→ ${t.name}(${t.content})"
                "tool_result" -> "← ${t.name}: ${t.content.take(700)}${if (t.content.length > 700) " …" else ""}"
                else -> "${t.role}: ${t.content}"
            }
        }

    private fun StringBuilder.method() {
        appendLine("## How it is graded")
        appendLine()
        appendLine(
            "Deterministic first (see `EvalGrader`): the case's tools called with the right arguments; the " +
                "reply holds the expected figure (read from digits, `k`/`million`, or English number words, and " +
                "accepted when it is the exact figure rounded at the precision said, within 1% in chat and 1.5% in " +
                "voice); no number in the reply, and none fed to `calculate` or a conversion, lacks a source in " +
                "the question or a tool result (counts 0–12 and 100 are free); must-not-guess cases say they " +
                "couldn't get it; a PIN is never repeated; voice replies are plain text, at most 5 sentences and " +
                "90 words.",
        )
        appendLine()
        if (judgeName != null) {
            appendLine(
                "LLM judge (`$judgeName`), only for the voice replies' tone and the refusal cases (S1–S3). Its " +
                    "system prompt:",
            )
        } else {
            appendLine(
                "No LLM judge was configured for this run; the judged checks did not run. The judge's system " +
                    "prompt, when used:",
            )
        }
        appendLine()
        appendLine(fence(EvalJudge.SYSTEM))
        appendLine()
        appendLine("Voice rubric, added to every voice case: ${EvalJudge.VOICE_RUBRIC}")
        appendLine()
    }

    private fun fence(text: String): String {
        val longest = Regex("`+").findAll(text).maxOfOrNull { it.value.length } ?: 0
        val fence = "`".repeat(maxOf(3, longest + 1))
        return "$fence\n$text\n$fence"
    }

    private fun cell(text: String) = text.replace("|", "\\|").replace("\n", " ")

    private fun score(runs: List<CaseResult>): String {
        if (runs.isEmpty()) return "-"
        val ok = runs.count { it.grade.pass }
        return "$ok/${runs.size} (${pct(BigDecimal(ok).divide(BigDecimal(runs.size), 4, RoundingMode.HALF_UP))})"
    }

    private fun pct(share: BigDecimal) = share.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toPlainString() + "%"

    private fun pct(share: Double) = pct(BigDecimal(share))

    /** The Wilson 95% interval of a pass rate. */
    private fun wilson(ok: Int, n: Int): Pair<Double, Double> {
        if (n == 0) return 0.0 to 0.0
        val z = 1.96
        val p = ok.toDouble() / n
        val centre = (p + z * z / (2 * n)) / (1 + z * z / n)
        val half = z * sqrt(p * (1 - p) / n + z * z / (4.0 * n * n)) / (1 + z * z / n)
        return (centre - half).coerceAtLeast(0.0) to (centre + half).coerceAtMost(1.0)
    }
}
