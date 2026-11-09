package com.circaso.integrations.api

import com.circaso.shared.Resource

/**
 * The model behind Asha, in our language: a conversation and the tools she may call go in, text or tool calls come
 * out. Prompts, tools and what to do with an answer belong to the assistant; the vendor only runs the model.
 */
interface LanguageModel {
    suspend fun complete(request: ModelRequest): Resource<ModelResponse>

    /**
     * The same answer as [complete], handed over while the model writes it, so the user reads (and hears) Asha's
     * reply at once instead of after it is whole (decision 2026-10-27). A refusal before the first word is the
     * [Resource.Error]; once the answer has started, a failure is a [ModelStreamEvent.Failed].
     *
     * The default streams [complete]'s answer as one piece, for providers that can't stream (the development models).
     */
    suspend fun stream(request: ModelRequest): Resource<ModelStream> =
        when (val answer = complete(request)) {
            is Resource.Success -> Resource.success(CompletedStream(answer.data))
            is Resource.Error -> answer
        }

    /**
     * Whether the model answers at all, as cheaply as the provider allows (decision 2026-11-07): Asha's health monitor
     * asks while she is down for maintenance, and marks her back on the first success. Never retried.
     *
     * The default asks [complete] for a one-word answer, for providers without anything cheaper.
     */
    suspend fun probe(): Resource<Unit> =
        when (val answer = complete(PROBE_REQUEST)) {
            is Resource.Success -> Resource.success(Unit)
            is Resource.Error -> answer
        }

    companion object {
        /** The question a probe asks: no tools, no history, a one-word answer. */
        val PROBE_REQUEST: ModelRequest =
            ModelRequest(system = "Answer with one word.", messages = listOf(ModelMessage.User("Ping")))
    }
}

/**
 * One answer as the model writes it. Pull-based, like the live voice sessions, so the contract needs nothing beyond
 * the standard library: the caller reads [next] until it returns null, and calls [close] however it stops.
 */
interface ModelStream {
    /** The next event, waiting for it; null after [ModelStreamEvent.Done] or [ModelStreamEvent.Failed]. */
    suspend fun next(): ModelStreamEvent?

    /** Stops the answer (the caller no longer wants it) and lets go of the connection. Safe to call twice. */
    suspend fun close()
}

sealed interface ModelStreamEvent {
    /** The next piece of the answer's text, in order. */
    data class Text(val delta: String) : ModelStreamEvent

    /**
     * A piece of a tool call. The pieces of one call share its [index]; its [id] and [name] come once, its arguments
     * a fragment at a time. [Done] carries the calls whole, so most callers can ignore these.
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String,
    ) : ModelStreamEvent

    /** The whole answer, as [LanguageModel.complete] would have returned it; [next] returns null after it. */
    data class Done(val response: ModelResponse) : ModelStreamEvent

    /** The answer broke off (or came out empty); [next] returns null after it. */
    data class Failed(val error: IntegrationError) : ModelStreamEvent
}

/** A whole answer as a stream: its text in one piece, then [ModelStreamEvent.Done]. */
private class CompletedStream(response: ModelResponse) : ModelStream {
    private val events =
        ArrayDeque(
            listOfNotNull(
                response.text?.takeIf { it.isNotEmpty() }?.let { ModelStreamEvent.Text(it) },
                ModelStreamEvent.Done(response),
            ),
        )

    override suspend fun next(): ModelStreamEvent? = events.removeFirstOrNull()

    override suspend fun close() = events.clear()
}

/**
 * One call to the model. [purpose] lets the provider pick a model for the moment: a spoken turn wants the first words
 * fast, a typed turn can wait for a stronger model. Only the model may differ; the conversation and the tools don't.
 *
 * The system prompt is [system], the part that reads the same on every call, then [systemTail], the short part about
 * this turn (the mode, today's date). A provider that caches a prompt's start caches [system] and not [systemTail];
 * one that doesn't reads them as one text, a blank line between ([systemPrompt]).
 */
data class ModelRequest(
    val system: String,
    val messages: List<ModelMessage>,
    val tools: List<ToolSpec> = emptyList(),
    val purpose: ModelPurpose = ModelPurpose.CHAT,
    val systemTail: String? = null,
) {
    /** The whole system prompt as one text. */
    val systemPrompt: String get() = if (systemTail.isNullOrEmpty()) system else system + "\n\n" + systemTail
}

/** What a model call is for; a provider with a single model ignores it. */
enum class ModelPurpose {
    /** A typed turn: the configured text model. */
    CHAT,

    /** A turn of a live voice conversation: a faster model with the same tools, if one is configured. */
    VOICE,
}

sealed interface ModelMessage {
    /** [images]: pictures for the model to read with the text (a picture of the app screen); most turns have none. */
    data class User(val text: String, val images: List<ModelImage> = emptyList()) : ModelMessage

    data class Assistant(val text: String?, val toolCalls: List<ToolCall> = emptyList()) : ModelMessage

    data class ToolResult(val callId: String, val name: String, val content: String) : ModelMessage
}

/**
 * A picture in a user message: [bytes] encoded as [mediaType] (`image/jpeg`, `image/png` or `image/webp`). Held for
 * the one model call that reads it, never stored. Equal by content, so a conversation compares as it reads.
 */
class ModelImage(val bytes: ByteArray, val mediaType: String) {
    override fun equals(other: Any?): Boolean =
        other is ModelImage && mediaType == other.mediaType && bytes.contentEquals(other.bytes)

    override fun hashCode(): Int = 31 * mediaType.hashCode() + bytes.contentHashCode()

    override fun toString(): String = "ModelImage($mediaType, ${bytes.size} bytes)"
}

/** [parametersJsonSchema] is a JSON Schema object describing the arguments. */
data class ToolSpec(val name: String, val description: String, val parametersJsonSchema: String)

data class ToolCall(val id: String, val name: String, val argumentsJson: String)

/** [model] names the model that wrote the answer, when the provider says (for the turn's log line). */
data class ModelResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val usage: ModelUsage,
    val model: String? = null,
)

/**
 * The tokens one call used. [inputTokens] is all of the input, whether read from the provider's prompt cache or not;
 * [cacheReadTokens] and [cacheWriteTokens] say how much of it was read from, or written to, that cache (zero for a
 * provider without one), so a cost report can price them apart.
 */
data class ModelUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
)

interface SpeechToText {
    /** Turns a short clip into text; the audio is not kept. */
    suspend fun transcribe(
        audio: ByteArray,
        contentType: String,
        language: String? = null,
    ): Resource<Transcript>
}

data class Transcript(val text: String, val seconds: Int)

interface TextToSpeech {
    /**
     * A [language] the voice can't speak fails with [IntegrationError.UnsupportedLanguage], before any vendor call.
     * [voice] is an opaque voice id the caller has already checked against its own list (the provider's speaker
     * name); null is the provider's default voice.
     */
    suspend fun speak(
        text: String,
        language: String? = null,
        voice: String? = null,
    ): Resource<SpokenAudio>
}

class SpokenAudio(val bytes: ByteArray, val contentType: String)

/**
 * Reads a photo for Asha, in our language: an image and what to look for go in, text comes out. The assistant
 * puts that text into the conversation for [LanguageModel], so the text model never needs to see images.
 */
interface VisionModel {
    suspend fun describe(image: ImageInput, instruction: String): Resource<VisionAnswer>
}

/** A photo as its bytes: the assistant reads it from storage itself, so no provider ever fetches our files. */
class ImageInput(val bytes: ByteArray, val contentType: String)

data class VisionAnswer(val text: String, val usage: ModelUsage)
