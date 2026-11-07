package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.HttpClients
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.impl.anthropic.AnthropicEffort
import com.circaso.integrations.impl.anthropic.AnthropicLanguageModel
import com.circaso.integrations.impl.anthropic.AnthropicMode
import com.circaso.integrations.impl.anthropic.AnthropicPrices
import com.circaso.integrations.impl.dev.FakeLanguageModel
import com.circaso.integrations.impl.dev.LogLanguageModel
import com.circaso.integrations.impl.llm.ModelRoute
import com.circaso.integrations.impl.llm.RoutedLanguageModel
import com.circaso.integrations.impl.workersai.WorkersAiLanguageModel
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Asha's text model for typed turns (`INTEGRATIONS_LLM_PROVIDER`) and for live voice
 * (`INTEGRATIONS_LLM_VOICE_PROVIDER`, the same as the first unless set): `log`, `fake`, `workers-ai` or `anthropic`
 * (Claude through Koog, decision 2026-11-07).
 *
 * `anthropic` needs the secret `ANTHROPIC_API_KEY`. Without it that purpose keeps Workers AI (when its settings are
 * there, else `log`) and says why once at startup, so setting the provider before the key changes nothing. With it,
 * Workers AI (when configured) answers any turn Claude can't before its first word ([RoutedLanguageModel]). Settings,
 * all optional: `INTEGRATIONS_ANTHROPIC_MODEL_TEXT` / `_MODEL_VOICE` (`claude-haiku-5-5`), `_EFFORT_TEXT` / `_EFFORT_VOICE`
 * (`low`), `_MAX_TOKENS_TEXT` (4096) / `_MAX_TOKENS_VOICE` (1024), `_BASE_URL`, `_PRICE_INPUT_PER_MTOK` /
 * `_PRICE_OUTPUT_PER_MTOK` (for the cost on each call's log line). An unknown provider name stops startup.
 */
internal fun ashaLanguageModel(
    config: Config,
    secrets: Secrets,
    clients: HttpClients,
): LanguageModel {
    val log = LoggerFactory.getLogger("com.circaso.integrations.AshaModel")
    val integrations = config.section("integrations")
    val ai = integrations.section("ai")
    val chatProvider = provider(integrations.string("llm.provider", "log"), "integrations.llm.provider")
    val voiceProvider =
        provider(integrations.string("llm.voice-provider", chatProvider), "integrations.llm.voice-provider")
    val key = secrets.get(ANTHROPIC_KEY)?.takeIf { it.isNotBlank() }
    val hasWorkersAi = !config.string("cloudflare.account-id").isNullOrBlank()

    val workersAi by lazy {
        WorkersAiLanguageModel(
            workersAi(config, secrets, clients),
            ai.string("model.text", WORKERS_AI_TEXT_MODEL),
            maxOutputTokens = ai.int("max-output-tokens", WORKERS_AI_MAX_OUTPUT_TOKENS),
            // Live voice turns: PhoneLLM Mini (decision 2026-10-31), measured from the sandbox on real Asha questions
            // with 8 tools: right tool 3/3 in 0.3–0.5 s and a spoken answer 0.3 s after the result.
            voiceModel = ai.string("model.voice-text", WORKERS_AI_VOICE_TEXT_MODEL),
        )
    }
    val logModel by lazy { LogLanguageModel() }
    val fakeModel by lazy { FakeLanguageModel() }
    val claude by lazy { anthropic(integrations.section("anthropic"), key!!, clients) }

    fun without(purpose: String, provider: String): String {
        if (provider != ANTHROPIC || key != null) return provider
        val instead = if (hasWorkersAi) WORKERS_AI else "log"
        log.warn(
            "asha {} model stays on {}: the provider is anthropic but ANTHROPIC_API_KEY is not set",
            purpose,
            instead,
        )
        return instead
    }
    val chatName = without("chat", chatProvider)
    val voiceName = without("voice", voiceProvider)

    fun model(name: String): LanguageModel =
        when (name) {
            WORKERS_AI -> workersAi
            ANTHROPIC -> claude
            "fake" -> fakeModel
            else -> logModel
        }

    fun route(name: String): ModelRoute =
        if (name == ANTHROPIC && hasWorkersAi) {
            ModelRoute(model(name), name, workersAi, WORKERS_AI)
        } else {
            ModelRoute(model(name), name)
        }

    val chat = route(chatName)
    val voice = route(voiceName)
    log.info(
        "asha models: chat={} voice={} fallback={}",
        chatName,
        voiceName,
        (chat.fallbackName ?: voice.fallbackName) ?: "none",
    )
    // One provider and no fallback: the provider itself, exactly as before the routing existed.
    return if (chat.model === voice.model && chat.fallback == null && voice.fallback == null) {
        chat.model
    } else {
        RoutedLanguageModel(chat, voice)
    }
}

private fun provider(name: String, key: String): String {
    if (name !in PROVIDERS) {
        throw IllegalStateException("$key must be one of ${PROVIDERS.joinToString()} (was '$name')")
    }
    return name
}

/** Claude for both purposes on `HttpClients.named("anthropic")`, which never retries: the fallback is quicker. */
private fun anthropic(
    settings: Config,
    key: String,
    clients: HttpClients,
): AnthropicLanguageModel {
    val baseUrl = settings.string("base-url", AnthropicLanguageModel.BASE_URL)

    fun mode(
        purpose: String,
        maxTokens: Int,
        socket: Duration,
        request: Duration,
    ) = AnthropicMode(
        model = settings.string("model.$purpose", ANTHROPIC_MODEL),
        effort = AnthropicEffort.parse(settings.string("effort.$purpose", "low")),
        maxTokens = settings.int("max-tokens.$purpose", maxTokens),
        connectTimeout = CONNECT_TIMEOUT,
        socketTimeout = socket,
        requestTimeout = request,
    )
    return AnthropicLanguageModel(
        http =
            clients.named("anthropic") {
                this.baseUrl = baseUrl
                connectTimeout = CONNECT_TIMEOUT
                requestTimeout = TEXT_REQUEST_TIMEOUT
                maxAttempts = 1
            },
        apiKey = key,
        chat = mode("text", TEXT_MAX_TOKENS, TEXT_SOCKET_TIMEOUT, TEXT_REQUEST_TIMEOUT),
        voice = mode("voice", VOICE_MAX_TOKENS, VOICE_SOCKET_TIMEOUT, VOICE_REQUEST_TIMEOUT),
        prices =
            AnthropicPrices(
                inputPerMillion =
                    price(settings, "price.input-per-mtok", AnthropicLanguageModel.HAIKU_INPUT_PER_MILLION),
                outputPerMillion =
                    price(settings, "price.output-per-mtok", AnthropicLanguageModel.HAIKU_OUTPUT_PER_MILLION),
            ),
        baseUrl = baseUrl,
    )
}

private fun price(
    settings: Config,
    key: String,
    default: Double,
): Double =
    settings.string(key)?.let {
        it.toDoubleOrNull() ?: throw IllegalStateException("Configuration '$key' must be a number (was '$it')")
    } ?: default

private const val ANTHROPIC = "anthropic"
private const val WORKERS_AI = "workers-ai"
private val PROVIDERS = listOf("log", "fake", WORKERS_AI, ANTHROPIC)

/** The secret `ANTHROPIC_API_KEY`: sent as `x-api-key` to Anthropic, never logged. */
private const val ANTHROPIC_KEY = "anthropic.api-key"

/** Claude Haiku 5.5 for chat and voice (decision 2026-11-07). */
private const val ANTHROPIC_MODEL = "claude-haiku-5-5"

// A spoken reply is a few sentences and every token is text (thinking is off); a typed one may carry blocks.
private const val VOICE_MAX_TOKENS = 1024
private const val TEXT_MAX_TOKENS = 4096
private val CONNECT_TIMEOUT: Duration = Duration.ofSeconds(5)
private val VOICE_SOCKET_TIMEOUT: Duration = Duration.ofSeconds(10)
private val VOICE_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(30)
private val TEXT_SOCKET_TIMEOUT: Duration = Duration.ofSeconds(20)
private val TEXT_REQUEST_TIMEOUT: Duration = Duration.ofSeconds(60)
