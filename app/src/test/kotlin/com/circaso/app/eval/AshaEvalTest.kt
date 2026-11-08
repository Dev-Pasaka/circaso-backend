package com.circaso.app.eval

import com.circaso.app.DotEnv
import com.circaso.assistant.eval.ArmResult
import com.circaso.assistant.eval.EvalArm
import com.circaso.assistant.eval.EvalCases
import com.circaso.assistant.eval.EvalJudge
import com.circaso.assistant.eval.EvalReport
import com.circaso.assistant.eval.EvalRunner
import com.circaso.assistant.eval.Mode
import com.circaso.assistant.eval.Prices
import com.circaso.assistant.eval.StubModels
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.http.VendorHttpClients
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.impl.integrationsModule
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Tag
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.test.Test

/**
 * Asha's eval (decision 2026-11-07): is Claude Haiku 5.5 at effort `low` smart enough, or does chat need `medium`, and
 * how does it compare with today's Workers AI models? Not a test: tagged `eval`, so `test` and CI leave it out.
 *
 * ```
 * ./gradlew :app:ashaEval -PashaEval.arms=oracle,workers-ai,haiku-low,haiku-medium \
 *     -PashaEval.envFile=/path/to/.env -PashaEval.judge=haiku-judge -PashaEval.reps=2
 * ```
 *
 * Settings (Gradle properties, all optional but `arms`):
 * - `ashaEval.arms`: the models under test, in order from cheapest. Stubs: `oracle`, `null`, `guesser`. Real:
 *   `workers-ai` (Kimi K2.6 for chat, PhoneLLM Mini for voice), `haiku-low`, `haiku-medium` (Claude Haiku 5.5 through
 *   the `anthropic` provider; there is no fallback model). An arm whose keys or provider are missing is skipped and named.
 * - `ashaEval.arm.<name>`: `KEY=value;KEY=value` settings for an arm, over the built-in ones (or a new arm).
 * - `ashaEval.price.<name>`: `input,output` US dollars per million tokens, for an arm's cost.
 * - `ashaEval.judge`: the arm that judges tone (should not be a model under test); none skips the judged checks.
 * - `ashaEval.envFile`: a `.env` to read keys from. Only the keys an arm needs are read, into this process; they are
 *   never printed, and every file the eval writes is scrubbed of them.
 * - `ashaEval.reps` (1), `ashaEval.concurrency` (4), `ashaEval.cases` (ids, comma separated; all by default).
 */
@Tag("eval")
class AshaEvalTest {
    private fun setting(name: String): String? = System.getProperty("ashaEval.$name")?.takeIf { it.isNotBlank() }

    /** A model the eval can build: the settings it runs on and the secrets it needs. */
    private data class ArmSpec(
        val settings: Map<String, String>,
        val needs: List<String>,
        val prices: Prices? = null,
        val expectedModel: String? = null,
        val note: String? = null,
    )

    private val haikuPrices = Prices(BigDecimal("0.10"), BigDecimal("0.50"))

    private fun haiku(effort: String) =
        ArmSpec(
            mapOf(
                "INTEGRATIONS_LLM_PROVIDER" to "anthropic",
                "INTEGRATIONS_ANTHROPIC_MODEL_TEXT" to "claude-haiku-5-5",
                "INTEGRATIONS_ANTHROPIC_MODEL_VOICE" to "claude-haiku-5-5",
                "INTEGRATIONS_ANTHROPIC_EFFORT_TEXT" to effort,
                "INTEGRATIONS_ANTHROPIC_EFFORT_VOICE" to effort,
            ),
            needs = listOf(ANTHROPIC_KEY),
            prices = haikuPrices,
            expectedModel = "claude-haiku-5-5",
            note = "Claude Haiku 5.5, effort `$effort` in chat and voice; no fallback model.",
        )

    private val builtIn: Map<String, ArmSpec> =
        mapOf(
            "workers-ai" to
                ArmSpec(
                    mapOf("INTEGRATIONS_LLM_PROVIDER" to "workers-ai"),
                    needs = WORKERS_AI_KEYS,
                    note =
                        "Workers AI: chat on `@cf/moonshotai/kimi-k2.6`, voice on " +
                            "`@cf/daily-co/phonellm-mini-alpha-2`; billed in neurons, so tokens are shown and cost is not.",
                ),
            "haiku-low" to haiku("low"),
            "haiku-medium" to haiku("medium"),
            // The judge: Haiku 5.5 at `high`, a setting no arm runs at. The provider sends thinking disabled, which
            // Sonnet 5.5 refuses (400), so a stronger judge needs the provider to allow thinking first.
            "haiku-judge" to
                ArmSpec(
                    mapOf(
                        "INTEGRATIONS_LLM_PROVIDER" to "anthropic",
                        "INTEGRATIONS_ANTHROPIC_MODEL_TEXT" to "claude-haiku-5-5",
                        "INTEGRATIONS_ANTHROPIC_EFFORT_TEXT" to "high",
                    ),
                    needs = listOf(ANTHROPIC_KEY),
                    prices = haikuPrices,
                ),
        )

    /** The secrets the run may read: from the environment, else the `.env` named, and nothing else from it. */
    private val secrets: Map<String, String> by lazy {
        val file = setting("envFile")?.let { DotEnv.parse(File(it).readLines()).toMap() }.orEmpty()
        SECRET_KEYS
            .mapNotNull { key ->
                (System.getenv(key) ?: file[key])?.takeIf { it.isNotBlank() }?.let { key to it }
            }.toMap()
    }

    private fun scrub(text: String): String =
        secrets.values.filter { it.length >= 8 }.fold(text) { acc, secret -> acc.replace(secret, "***") }

    private val clients = VendorHttpClients()

    /** The arm's model, or why it can't run. */
    private fun build(name: String): Pair<EvalArm?, String?> {
        StubModels.of(name)?.let { return EvalArm(name, it, note = "stub ($name), no model call") to null }
        val custom =
            setting("arm.$name")
                ?.split(';')
                ?.mapNotNull { pair ->
                    pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() }
                }?.toMap()
        // a custom arm gets only its provider's keys, so each arm is exactly one provider
        val spec =
            builtIn[name]
                ?: custom?.let {
                    val keys =
                        if (it["INTEGRATIONS_LLM_PROVIDER"] ==
                            "anthropic"
                        ) {
                            listOf(ANTHROPIC_KEY)
                        } else {
                            WORKERS_AI_KEYS
                        }
                    ArmSpec(it, needs = keys)
                }
                ?: return null to "$name: unknown arm"
        val missing = spec.needs.filter { it !in secrets }
        if (missing.isNotEmpty()) {
            return null to
                "$name: missing ${missing.joinToString()} (set them, or pass -PashaEval.envFile)"
        }
        val settings = spec.settings + custom.orEmpty() + spec.needs.associateWith { secrets.getValue(it) }
        val prices =
            setting("price.$name")?.split(',')?.map { BigDecimal(it.trim()) }?.let { Prices(it[0], it[1]) }
                ?: spec.prices
        return try {
            val koin =
                koinApplication {
                    modules(
                        module { single<HttpClients> { clients } },
                        integrationsModule(EnvConfig(settings), EnvSecrets(settings)),
                    )
                }.koin
            EvalArm(name, koin.get<LanguageModel>(), prices, spec.expectedModel, spec.note) to null
        } catch (e: Exception) {
            null to
                "$name: the provider couldn't be built on this branch (${e::class.simpleName}: ${scrub(
                    e.message.orEmpty(),
                )})"
        }
    }

    @Test
    fun `asha eval`() {
        val names = setting("arms")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: listOf("oracle")
        val built = names.map { it to build(it) }
        val skipped = built.mapNotNull { it.second.second }
        val arms = built.mapNotNull { it.second.first }
        val judgeArm = setting("judge")?.let { build(it) }
        val judge = judgeArm?.first?.let { EvalJudge(it.model, it.name) }
        val skippedAll = skipped + listOfNotNull(judgeArm?.second?.let { "judge $it" })
        skippedAll.forEach { println("[asha-eval] skipped: $it") }

        val wanted = setting("cases")?.split(',')?.map { it.trim() }?.toSet()
        val cases = EvalCases.all.filter { wanted == null || it.id in wanted }
        val reps = setting("reps")?.toInt() ?: 1
        val runner = EvalRunner(reps = reps, concurrency = setting("concurrency")?.toInt() ?: 4, judge = judge)

        val results: List<ArmResult> =
            runBlocking {
                arms.map { arm ->
                    println("[asha-eval] running ${arm.name}: ${cases.size} cases × $reps")
                    runner.run(arm, cases).also { r ->
                        println(
                            "[asha-eval] ${arm.name}: ${r.results.count {
                                it.grade.pass
                            }}/${r.results.size} passed, ${r.errors.size} errors",
                        )
                    }
                }
            }
        val out =
            File(
                setting("out") ?: "build/asha-eval",
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")),
            )
        val report =
            EvalReport(out, ::scrub, judge?.name, reps, skippedAll).apply {
                judgePrices =
                    judgeArm?.first?.prices
            }
        val file = report.write(results)
        Mode.entries.forEach { mode ->
            val lowest = results.firstOrNull { report.passes(it, mode) && StubModels.of(it.arm.name) == null }
            println(
                "[asha-eval] ${mode.name.lowercase()}: lowest arm that passes the bar: ${lowest?.arm?.name ?: "none"}",
            )
        }
        println("[asha-eval] report: ${file.absolutePath}")
        clients.close()
    }

    private companion object {
        const val ANTHROPIC_KEY = "ANTHROPIC_API_KEY"
        val WORKERS_AI_KEYS = listOf("CLOUDFLARE_ACCOUNT_ID", "CLOUDFLARE_AI_TOKEN", "INTEGRATIONS_AI_GATEWAY")
        val SECRET_KEYS = listOf(ANTHROPIC_KEY) + WORKERS_AI_KEYS
    }
}
