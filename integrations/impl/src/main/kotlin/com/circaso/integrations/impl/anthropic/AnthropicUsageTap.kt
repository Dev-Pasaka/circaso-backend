package com.circaso.integrations.impl.anthropic

import ai.koog.http.client.KoogHttpClient
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * One call's view of the shared Koog client, which also reads the call's token usage from Anthropic's events.
 *
 * Koog 1.3.0 hands a streamed answer's input and output tokens to the caller, but not how many were read from or
 * written to the prompt cache (it does for a whole, unstreamed answer). Those are in `message_start.message.usage` and
 * `message_delta.usage`, so every event's data is read here, typed, before Koog reads it. Nothing else of the event is
 * kept. Made per call, around the one shared client; closing it leaves that client open.
 */
internal class AnthropicUsageTap(private val delegate: KoogHttpClient) : KoogHttpClient {
    override val clientName: String get() = delegate.clientName

    private var input: Int? = null
    private var output: Int? = null
    private var cacheRead: Int? = null
    private var cacheWrite: Int? = null

    /** The call's usage as far as the events have said; zero for what they haven't. */
    val usage: TokenUsage get() = TokenUsage(input ?: 0, output ?: 0, cacheRead ?: 0, cacheWrite ?: 0)

    /** Whether any event has reported usage yet. */
    val hasUsage: Boolean get() = input != null || output != null

    private fun read(data: String) {
        val event = runCatching { json.decodeFromString(UsageEvent.serializer(), data) }.getOrNull() ?: return
        (event.message?.usage ?: event.usage)?.let { counts ->
            counts.inputTokens?.let { input = it }
            counts.outputTokens?.let { output = it }
            counts.cacheReadInputTokens?.let { cacheRead = it }
            counts.cacheCreationInputTokens?.let { cacheWrite = it }
        }
    }

    override suspend fun <R : Any> get(
        path: String,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.get(path, responseType, parameters, headers)

    override suspend fun <T : Any, R : Any> post(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        responseType: KClass<R>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): R = delegate.post(path, requestBody, requestBodyType, responseType, parameters, headers)

    override fun <T : Any, R : Any, O : Any> sse(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        dataFilter: (String?) -> Boolean,
        decodeStreamingResponse: (String) -> R,
        processStreamingChunk: (R) -> O?,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<O> =
        delegate.sse(
            path,
            requestBody,
            requestBodyType,
            dataFilter,
            { data ->
                read(data)
                decodeStreamingResponse(data)
            },
            processStreamingChunk,
            parameters,
            headers,
        )

    override fun <T : Any> lines(
        path: String,
        requestBody: T,
        requestBodyType: KClass<T>,
        parameters: Map<String, String>,
        headers: Map<String, String>,
    ): Flow<String> = delegate.lines(path, requestBody, requestBodyType, parameters, headers)

    /** The shared client stays open for the next call. */
    override fun close() = Unit

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/** A call's tokens: [input] is the part not read from or written to the cache, as Anthropic counts it. */
internal data class TokenUsage(
    val input: Int,
    val output: Int,
    val cacheRead: Int,
    val cacheWrite: Int,
) {
    val totalInput: Int get() = input + cacheRead + cacheWrite
}

@Serializable
private data class UsageEvent(
    val type: String? = null,
    val message: UsageMessage? = null,
    val usage: UsageCounts? = null,
)

@Serializable
private data class UsageMessage(val usage: UsageCounts? = null)

@Serializable
private data class UsageCounts(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Int? = null,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Int? = null,
)
