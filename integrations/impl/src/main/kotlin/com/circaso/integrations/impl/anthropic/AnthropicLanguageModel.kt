package com.circaso.integrations.impl.anthropic

import ai.koog.http.client.KoogHttpClient
import ai.koog.http.client.ktor.KtorKoogHttpClient
import ai.koog.prompt.executor.clients.anthropic.AnthropicClientSettings
import ai.koog.prompt.executor.clients.anthropic.AnthropicLLMClient
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.streaming.StreamFrame
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.math.roundToLong

/** How hard the model thinks about its answer; thinking itself is always off (see [AnthropicPrompts]). */
enum class AnthropicEffort(val wire: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    ;

    companion object {
        /** `low`, `medium` or `high`; anything else (`xhigh`, `max` need thinking on) stops startup. */
        fun parse(name: String): AnthropicEffort =
            entries.firstOrNull { it.wire == name.trim().lowercase() }
                ?: throw IllegalStateException("Anthropic effort must be low, medium or high (was '$name')")
    }
}

/**
 * The model for one [ModelPurpose]: its id (sent exactly as written), effort, answer cap and timeouts. [requestTimeout]
 * bounds the whole streamed answer, [socketTimeout] a silence inside it.
 */
data class AnthropicMode(
    val model: String,
    val effort: AnthropicEffort,
    val maxTokens: Int,
    val connectTimeout: Duration,
    val socketTimeout: Duration,
    val requestTimeout: Duration,
)

/** Prices per million tokens, in US dollars, for the cost estimate on each call's log line. */
data class AnthropicPrices(
    val inputPerMillion: Double,
    val outputPerMillion: Double,
    val cacheReadMultiplier: Double = 0.1,
    val cacheWriteMultiplier: Double = 1.25,
) {
    internal fun microDollars(usage: TokenUsage): Long =
        (
            usage.input * inputPerMillion +
                usage.cacheRead * inputPerMillion * cacheReadMultiplier +
                usage.cacheWrite * inputPerMillion * cacheWriteMultiplier +
                usage.output * outputPerMillion
        ).roundToLong()
}

/**
 * Asha's text model on Claude (decision 2026-11-07: Claude Haiku 5.5 for chat and voice), called through JetBrains
 * Koog's Anthropic client and nothing else of Koog: our own loop in the assistant stays, one streamed request per step.
 *
 * Koog's client runs on [http], our `HttpClients.named("anthropic")`, so the call has the shared client's circuit
 * breaker, attempt logging and tracing; Koog adds Server-Sent Events and its timeouts ([AnthropicMode]). The key goes in
 * the `x-api-key` header and nowhere else. Koog logs whole prompts at DEBUG, so its loggers are held at WARN
 * (`logback.xml`).
 *
 * Each [ModelPurpose] has its own [AnthropicMode]. The answer streams as [ModelStreamEvent.Text]; a tool call is taken
 * whole from Koog's `ToolCallComplete` (Anthropic sends one content block at a time, so a call's pieces never interleave
 * with another's). The answer's end:
 * - `refusal`: [IntegrationError.Rejected], so the caller can fall back (Haiku 5.5 has no server-side fallback);
 * - `max_tokens`: the text so far is the answer, a half-written tool call is dropped;
 * - neither text nor a tool call: [IntegrationError.Unavailable], as Workers AI does.
 *
 * Each call logs one line at INFO with its tokens (fresh input, cache read, cache write, output), the time to the first
 * token and the estimated cost, and never anything of the conversation.
 */
class AnthropicLanguageModel(
    http: HttpClient,
    apiKey: String,
    private val chat: AnthropicMode,
    private val voice: AnthropicMode,
    private val prices: AnthropicPrices = AnthropicPrices(HAIKU_INPUT_PER_MILLION, HAIKU_OUTPUT_PER_MILLION),
    baseUrl: String = BASE_URL,
    private val nanoTime: () -> Long = System::nanoTime,
) : LanguageModel {
    private val log = LoggerFactory.getLogger(AnthropicLanguageModel::class.java)

    private val routes =
        mapOf(ModelPurpose.CHAT to chat, ModelPurpose.VOICE to voice).mapValues { (_, mode) ->
            Route(mode, LLModel(LLMProvider.Anthropic, mode.model, CAPABILITIES, CONTEXT_TOKENS, MAX_OUTPUT_TOKENS))
        }

    private val settings =
        AnthropicClientSettings(
            modelVersionsMap = routes.values.associate { it.llm to it.mode.model },
            baseUrl = baseUrl,
        )

    /** One Koog client per purpose, each with its own timeouts, all over the one shared Ktor client. */
    private val clients: Map<ModelPurpose, KoogHttpClient> =
        routes.mapValues { (purpose, route) ->
            KtorKoogHttpClient.Factory(baseClient = http).create(
                clientName = "anthropic-${purpose.name.lowercase()}",
                baseUrl = baseUrl,
                headers = mapOf(API_KEY_HEADER to apiKey, VERSION_HEADER to API_VERSION),
                requestTimeoutMillis = route.mode.requestTimeout.toMillis(),
                connectTimeoutMillis = route.mode.connectTimeout.toMillis(),
                socketTimeoutMillis = route.mode.socketTimeout.toMillis(),
                json = Json { ignoreUnknownKeys = true },
            )
        }

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val stream =
            when (val opened = stream(request)) {
                is Resource.Success -> opened.data
                is Resource.Error -> return opened
            }
        try {
            while (true) {
                when (val event = stream.next() ?: return Resource.error(IntegrationError.Unavailable(VENDOR))) {
                    is ModelStreamEvent.Done -> return Resource.success(event.response)
                    is ModelStreamEvent.Failed -> return Resource.error(event.error)
                    else -> Unit
                }
            }
        } finally {
            stream.close()
        }
    }

    /**
     * Opens the answer. A failure before Anthropic's first event (refused, unreachable, overloaded) is the
     * [Resource.Error]; after it, a [ModelStreamEvent.Failed].
     */
    override suspend fun stream(request: ModelRequest): Resource<ModelStream> {
        val route = routes.getValue(request.purpose)
        val tools =
            when (val built = AnthropicTools.descriptors(request.tools)) {
                is Resource.Success -> built.data
                is Resource.Error -> return built
            }
        val prompt = AnthropicPrompts.prompt(request, route.mode)
        val tap = AnthropicUsageTap(clients.getValue(request.purpose))
        val client = AnthropicLLMClient(settings = settings, httpClient = tap)
        val call = Call(request.purpose, route.mode, tap, nanoTime())
        val opened = CompletableDeferred<Resource<Unit>>()
        val events = Channel<ModelStreamEvent>(Channel.UNLIMITED)
        val reader =
            CoroutineScope(currentCoroutineContext()).launch {
                try {
                    client.executeStreaming(prompt, route.llm, tools).collect { frame ->
                        opened.complete(Resource.success(Unit))
                        call.read(frame)?.let { events.send(it) }
                    }
                    opened.complete(Resource.success(Unit))
                    events.send(call.end())
                } catch (e: CancellationException) {
                    opened.complete(Resource.error(IntegrationError.Unavailable(VENDOR), e))
                    throw e
                } catch (e: Exception) {
                    val failure = AnthropicFailure.of(e)
                    call.failed(failure)
                    if (!opened.complete(
                            Resource.error(failure.error, e),
                        )
                    ) {
                        events.send(ModelStreamEvent.Failed(failure.error))
                    }
                } finally {
                    events.close()
                }
            }
        return when (val start = opened.await()) {
            is Resource.Success -> Resource.success(Answer(events, reader))
            is Resource.Error -> start
        }
    }

    private class Route(val mode: AnthropicMode, val llm: LLModel)

    /** One answer as the reader sees it: its text, its whole tool calls, how it ended, and its clock. */
    private inner class Call(
        private val purpose: ModelPurpose,
        private val mode: AnthropicMode,
        private val tap: AnthropicUsageTap,
        private val startedAt: Long,
    ) {
        private val text = StringBuilder()
        private val calls = mutableListOf<ToolCall>()
        private var stopReason: String? = null
        private var metaInput: Int? = null
        private var metaOutput: Int? = null
        private var firstAt = -1L

        fun read(frame: StreamFrame): ModelStreamEvent? =
            when (frame) {
                is StreamFrame.TextDelta -> {
                    if (frame.text.isEmpty()) {
                        null
                    } else {
                        firstToken()
                        text.append(frame.text)
                        ModelStreamEvent.Text(frame.text)
                    }
                }

                is StreamFrame.ToolCallDelta -> {
                    firstToken()
                    ModelStreamEvent.ToolCallDelta(frame.index ?: 0, frame.id, frame.name, frame.content.orEmpty())
                }

                is StreamFrame.ToolCallComplete -> {
                    toolCall(frame)?.let { calls += it }
                    null
                }

                is StreamFrame.End -> {
                    stopReason = frame.finishReason
                    metaInput = frame.metaInfo.inputTokensCount
                    metaOutput = frame.metaInfo.outputTokensCount
                    null
                }

                else -> {
                    null
                }
            }

        private fun firstToken() {
            if (firstAt < 0) firstAt = nanoTime()
        }

        /** A call whose arguments aren't a JSON object (cut off by `max_tokens`) is dropped; none at all is `{}`. */
        private fun toolCall(frame: StreamFrame.ToolCallComplete): ToolCall? {
            val id = frame.id ?: return null
            if (frame.name.isEmpty()) return null
            val arguments = frame.content.ifBlank { "{}" }
            val isObject =
                arguments == "{}" || StreamFrame.ToolCallComplete(id, frame.name, arguments).contentJsonResult.isSuccess
            if (!isObject) {
                log.warn("anthropic tool call dropped, arguments not JSON: purpose={} tool={}", purpose, frame.name)
                return null
            }
            return ToolCall(id, frame.name, arguments)
        }

        fun end(): ModelStreamEvent {
            val usage =
                if (tap.hasUsage) tap.usage else TokenUsage(metaInput ?: 0, metaOutput ?: 0, 0, 0)
            logUsage(usage)
            val answer = text.toString().takeIf { it.isNotBlank() }
            return when {
                stopReason == REFUSAL -> {
                    log.warn("anthropic declined to answer: purpose={} model={}", purpose, mode.model)
                    ModelStreamEvent.Failed(IntegrationError.Rejected("The model declined to answer."))
                }

                answer == null && calls.isEmpty() -> {
                    log.warn("anthropic answer was empty: purpose={} model={} stop={}", purpose, mode.model, stopReason)
                    ModelStreamEvent.Failed(IntegrationError.Unavailable(VENDOR))
                }

                else -> {
                    if (stopReason == MAX_TOKENS) {
                        log.warn("anthropic answer hit max_tokens: purpose={} maxTokens={}", purpose, mode.maxTokens)
                    }
                    ModelStreamEvent.Done(
                        ModelResponse(
                            answer,
                            calls.toList(),
                            ModelUsage(usage.totalInput, usage.output, usage.cacheRead, usage.cacheWrite),
                            mode.model,
                        ),
                    )
                }
            }
        }

        fun failed(failure: AnthropicFailure) {
            val args = arrayOf<Any>(purpose, mode.model, failure.reason, firstAt >= 0)
            if (failure.isKeyProblem) {
                log.error(
                    "anthropic call failed, check ANTHROPIC_API_KEY: purpose={} model={} reason={} afterText={}",
                    *args,
                )
            } else {
                log.warn("anthropic call failed: purpose={} model={} reason={} afterText={}", *args)
            }
        }

        private fun logUsage(usage: TokenUsage) {
            val now = nanoTime()
            log.info(
                "asha model call: purpose={} model={} effort={} input={} cacheRead={} cacheWrite={} output={} " +
                    "stop={} firstTokenMs={} totalMs={} costMicroUsd={}",
                purpose,
                mode.model,
                mode.effort.wire,
                usage.input,
                usage.cacheRead,
                usage.cacheWrite,
                usage.output,
                stopReason,
                if (firstAt < 0) null else (firstAt - startedAt) / NANOS_PER_MILLI,
                (now - startedAt) / NANOS_PER_MILLI,
                prices.microDollars(usage),
            )
        }
    }

    /** The events as the reader puts them out; closing stops the reader and lets go of the connection. */
    private class Answer(private val events: Channel<ModelStreamEvent>, private val reader: Job) : ModelStream {
        override suspend fun next(): ModelStreamEvent? = events.receiveCatching().getOrNull()

        override suspend fun close() {
            reader.cancel()
            events.cancel()
        }
    }

    companion object {
        const val BASE_URL = "https://api.anthropic.com/"
        const val VENDOR = AnthropicFailure.VENDOR

        /** Claude Haiku 5.5 list prices for prompts up to 100k tokens (USD per million tokens, 2026-11). */
        const val HAIKU_INPUT_PER_MILLION = 0.10
        const val HAIKU_OUTPUT_PER_MILLION = 0.50

        private const val API_KEY_HEADER = "x-api-key"
        private const val VERSION_HEADER = "anthropic-version"
        private const val API_VERSION = "2023-06-01"
        private const val REFUSAL = "refusal"
        private const val MAX_TOKENS = "max_tokens"
        private const val NANOS_PER_MILLI = 1_000_000L
        private const val CONTEXT_TOKENS = 1_000_000L
        private const val MAX_OUTPUT_TOKENS = 128_000L
        private val CAPABILITIES = listOf(LLMCapability.Completion, LLMCapability.Tools, LLMCapability.Document)
    }
}
