package com.circaso.assistant.eval

import com.circaso.assistant.data.blocks.JsonReplyBlocks
import com.circaso.assistant.domain.ports.DeviceTools
import com.circaso.assistant.domain.ports.PendingMessage
import com.circaso.assistant.domain.support.AgentOutcome
import com.circaso.assistant.domain.support.AgentTurn
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.shared.Resource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.math.BigDecimal
import java.util.UUID
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * A model under test. [prices] are US dollars per million tokens (null when the provider doesn't bill by token, or
 * the price isn't known); [expectedModel], when set, must be the model the provider says answered every call.
 */
data class EvalArm(
    val name: String,
    val model: LanguageModel,
    val prices: Prices? = null,
    val expectedModel: String? = null,
    val note: String? = null,
)

data class Prices(
    val inputPerMTok: BigDecimal,
    val outputPerMTok: BigDecimal,
    val cacheReadPerMTok: BigDecimal = inputPerMTok.multiply(BigDecimal("0.1")),
    val cacheWritePerMTok: BigDecimal = inputPerMTok.multiply(BigDecimal("1.25")),
)

/** Tokens over one or more model calls, as the provider reported them. [input] includes cached input. */
data class Tokens(
    val calls: Int = 0,
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheWrite: Long = 0,
) {
    operator fun plus(other: Tokens) =
        Tokens(
            calls + other.calls,
            input + other.input,
            output + other.output,
            cacheRead + other.cacheRead,
            cacheWrite + other.cacheWrite,
        )

    /** US dollars: uncached input at the input price, cached reads and writes at theirs. */
    fun cost(prices: Prices): BigDecimal {
        val uncached = (input - cacheRead - cacheWrite).coerceAtLeast(0)
        return (
            prices.inputPerMTok * BigDecimal(uncached) + prices.outputPerMTok * BigDecimal(output) +
                prices.cacheReadPerMTok * BigDecimal(cacheRead) + prices.cacheWritePerMTok * BigDecimal(cacheWrite)
        ).movePointLeft(6)
    }

    companion object {
        /**
         * One call's usage. The cache counts and the answering model are read only if this build's port has them
         * (they arrive with the Claude provider), so the eval compiles on either side of that change.
         */
        fun of(usage: ModelUsage) =
            Tokens(
                1,
                usage.inputTokens.toLong(),
                usage.outputTokens.toLong(),
                extra(usage, "getCacheReadTokens"),
                extra(usage, "getCacheWriteTokens"),
            )

        private fun extra(usage: ModelUsage, getter: String): Long =
            runCatching { (usage.javaClass.getMethod(getter).invoke(usage) as Number).toLong() }.getOrDefault(0)

        fun servedModel(response: ModelResponse): String? =
            runCatching { response.javaClass.getMethod("getModel").invoke(response) as String? }.getOrNull()
    }
}

/** One graded run of one case. */
data class CaseResult(
    val arm: String,
    val case: EvalCase,
    val rep: Int,
    val reply: String,
    val calls: List<ToolCallRecord>,
    val trace: List<TraceTurn>,
    val grade: Grade,
    val tokens: Tokens,
    val judgeTokens: Tokens,
    val latencyMillis: Long,
    val attempts: Int,
    val servedModels: Set<String>,
)

/** A run that produced nothing to grade (the provider failed, the clock ran out): never counted as a wrong answer. */
data class CaseError(
    val arm: String,
    val caseId: String,
    val rep: Int,
    val kind: String,
    val message: String,
    val attempts: Int,
    val tokens: Tokens,
)

data class TraceTurn(val role: String, val content: String, val name: String? = null)

data class ArmResult(val arm: EvalArm, val results: List<CaseResult>, val errors: List<CaseError>)

/**
 * Runs cases through Asha's real loop: [AshaAgent] with [AshaPrompt][com.circaso.assistant.domain.support.AshaPrompt]
 * as production builds it, the real tools over an [EvalWorld], and the arm's model. A turn that stops for the phone
 * (`searchChats`, `readChat`) is carried on as ContinueTurn does, with the fake phone's answer.
 *
 * Each case and repetition gets a fresh world. A provider failure is retried (2 s, then 6 s, with jitter, at most
 * [maxAttempts] in all) and, if it never answers, recorded as an error, apart from the grades. Every case has a hard
 * [caseTimeout].
 */
class EvalRunner(
    private val reps: Int = 1,
    private val concurrency: Int = 4,
    private val judge: EvalJudge? = null,
    private val caseTimeout: Duration = 4.minutes,
    private val maxAttempts: Int = 3,
    private val config: AssistantConfig = AssistantConfig(),
) {
    suspend fun run(arm: EvalArm, cases: List<EvalCase> = EvalCases.all): ArmResult =
        coroutineScope {
            val gate = Semaphore(concurrency)
            val runs =
                cases
                    .flatMap { case -> (0 until reps).map { rep -> case to rep } }
                    .map { (case, rep) ->
                        async(Dispatchers.IO) { gate.withPermit { runCase(arm, case, rep) } }
                    }.awaitAll()
            ArmResult(arm, runs.mapNotNull { it.first }, runs.mapNotNull { it.second })
        }

    private suspend fun runCase(
        arm: EvalArm,
        case: EvalCase,
        rep: Int,
    ): Pair<CaseResult?, CaseError?> {
        var spent = Tokens()
        var lastProblem = ""
        var kind = "provider"
        for (attempt in 1..maxAttempts) {
            val recorder = Recording(arm.model)
            val started = System.nanoTime()
            try {
                val turn = withTimeout(caseTimeout) { once(recorder, case) }
                spent += recorder.tokens
                if (turn is Resource.Error) {
                    lastProblem =
                        turn.error.message + (turn.cause?.let { " (${it::class.simpleName}: ${it.message})" } ?: "")
                    kind = "provider"
                } else {
                    val answered = (turn as Resource.Success).data
                    val mismatch =
                        arm.expectedModel
                            ?.let { want ->
                                recorder.served.filterNot { it.startsWith(want) }
                            }.orEmpty()
                    if (mismatch.isNotEmpty()) {
                        return null to
                            CaseError(
                                arm.name,
                                case.id,
                                rep,
                                "served-model",
                                "answered by ${mismatch.joinToString()}",
                                attempt,
                                spent,
                            )
                    }
                    val latency = (System.nanoTime() - started) / 1_000_000
                    var grade = EvalGrader.grade(case, answered.reply, answered.calls)
                    var judged = Tokens()
                    if (case.judge != null || case.mode == Mode.VOICE) {
                        if (judge == null) {
                            grade = grade.with(Check("judge", null, "no judge configured"))
                        } else {
                            val verdict = judge.judge(case, answered.reply)
                            grade = grade.with(verdict.check)
                            verdict.usage?.let { judged = Tokens.of(it) }
                        }
                    }
                    return CaseResult(
                        arm.name,
                        case,
                        rep,
                        answered.reply,
                        answered.calls,
                        answered.trace,
                        grade,
                        spent,
                        judged,
                        latency,
                        attempt,
                        recorder.served,
                    ) to null
                }
            } catch (e: TimeoutCancellationException) {
                spent += recorder.tokens
                return null to
                    CaseError(arm.name, case.id, rep, "timeout", "no answer within $caseTimeout", attempt, spent)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                spent += recorder.tokens
                lastProblem = "${e::class.simpleName}: ${e.message}"
                kind = "harness"
            }
            if (attempt < maxAttempts) delay(2_000L * (1 shl (attempt - 1)) * 3 / 2 - 1_000 + Random.nextLong(500))
        }
        return null to CaseError(arm.name, case.id, rep, kind, lastProblem, maxAttempts, spent)
    }

    private data class Answered(val reply: String, val calls: List<ToolCallRecord>, val trace: List<TraceTurn>)

    /** One turn, carried on through every pause for the phone, as StartTurn and ContinueTurn run it. */
    private suspend fun once(model: Recording, case: EvalCase): Resource<Answered> {
        val world = EvalWorld()
        val agent = AshaAgent(model, world.tools, config, JsonReplyBlocks(), world.scenes)
        val turn =
            AgentTurn(
                turnId = UUID.randomUUID(),
                userId = world.userId,
                agentToken = EvalWorld.AGENT_TOKEN,
                currency = EvalWorld.CURRENCY,
                deviceTools = case.deviceTools,
                purpose = if (case.mode == Mode.VOICE) ModelPurpose.VOICE else ModelPurpose.CHAT,
                today = EvalWorld.TODAY,
                question = case.question,
            )
        var outcome =
            when (val r = agent.run(listOf(ModelMessage.User(case.question)), turn)) {
                is Resource.Success -> r.data
                is Resource.Error -> return r
            }
        var pauses = 0
        while (outcome is AgentOutcome.WaitingForDevice && pauses < MAX_PAUSES) {
            pauses++
            val call = outcome.call
            val snippets =
                if (call.name ==
                    DeviceTools.READ_CHAT
                ) {
                    world.readChat(call.argumentsJson)
                } else {
                    world.searchChats(call.argumentsJson)
                }
            val conversation =
                outcome.conversation.map(::toModel) +
                    ModelMessage.ToolResult(call.id, call.name, AshaAgent.snippetsAsText(snippets))
            outcome =
                when (
                    val r =
                        agent.run(
                            conversation,
                            turn.copy(deviceTools = true),
                            startSteps = outcome.steps,
                            shownBefore = AshaAgent.shownSoFar(outcome.conversation),
                        )
                ) {
                    is Resource.Success -> r.data
                    is Resource.Error -> return r
                }
        }
        val reply = (outcome as? AgentOutcome.Answered)?.text ?: ""
        val messages = model.lastRequest?.messages.orEmpty()
        return Resource.success(Answered(reply, callsOf(messages), traceOf(model.lastRequest, reply)))
    }

    private fun toModel(message: PendingMessage): ModelMessage =
        when (message) {
            is PendingMessage.User -> {
                ModelMessage.User(message.text)
            }

            is PendingMessage.Assistant -> {
                ModelMessage.Assistant(
                    message.text,
                    message.calls.map {
                        ToolCall(it.id, it.name, it.argumentsJson)
                    },
                )
            }

            is PendingMessage.ToolResult -> {
                ModelMessage.ToolResult(message.callId, message.name, message.content)
            }
        }

    /** The calls that ran, with what they answered; calls the loop skipped or refused didn't run. */
    private fun callsOf(messages: List<ModelMessage>): List<ToolCallRecord> {
        val results = messages.filterIsInstance<ModelMessage.ToolResult>().associateBy { it.callId }
        return messages
            .filterIsInstance<ModelMessage.Assistant>()
            .flatMap { it.toolCalls }
            .mapNotNull { call ->
                val result = results[call.id]?.content ?: return@mapNotNull null
                if (result.startsWith("Skipped:") || result == "That tool is not available.") return@mapNotNull null
                ToolCallRecord(call.name, call.argumentsJson, result)
            }
    }

    private fun traceOf(last: ModelRequest?, reply: String): List<TraceTurn> {
        if (last == null) return listOf(TraceTurn("assistant", reply))
        val turns = mutableListOf(TraceTurn("system", last.system))
        last.messages.forEach { m ->
            when (m) {
                is ModelMessage.User -> {
                    turns += TraceTurn("user", m.text)
                }

                is ModelMessage.Assistant -> {
                    m.text?.takeIf { it.isNotBlank() }?.let { turns += TraceTurn("assistant", it) }
                    m.toolCalls.forEach { turns += TraceTurn("tool_call", it.argumentsJson, it.name) }
                }

                is ModelMessage.ToolResult -> {
                    turns += TraceTurn("tool_result", m.content, m.name)
                }
            }
        }
        turns += TraceTurn("assistant", reply)
        return turns
    }

    private companion object {
        const val MAX_PAUSES = 5
    }
}

/** Passes calls through to the model under test, keeping the token counts, the answering model and the last request. */
private class Recording(private val model: LanguageModel) : LanguageModel {
    @Volatile var tokens = Tokens()
    val served = mutableSetOf<String>()

    @Volatile var lastRequest: ModelRequest? = null

    private fun record(response: ModelResponse) =
        synchronized(this) {
            tokens += Tokens.of(response.usage)
            Tokens.servedModel(response)?.let { served += it }
        }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        lastRequest = request
        return model.complete(request).also { if (it is Resource.Success) record(it.data) }
    }

    override suspend fun stream(request: ModelRequest): Resource<ModelStream> {
        lastRequest = request
        return when (val opened = model.stream(request)) {
            is Resource.Error -> {
                opened
            }

            is Resource.Success -> {
                Resource.success(
                    object : ModelStream {
                        override suspend fun next(): ModelStreamEvent? =
                            opened.data.next()?.also {
                                if (it is ModelStreamEvent.Done) record(it.response)
                            }

                        override suspend fun close() = opened.data.close()
                    },
                )
            }
        }
    }
}
