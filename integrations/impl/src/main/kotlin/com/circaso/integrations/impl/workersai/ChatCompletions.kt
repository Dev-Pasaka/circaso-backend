package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.ModelUsage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonPrimitive

/** The OpenAI-compatible chat completions route of the AI REST API, shared by the text and image models. */
internal const val CHAT_COMPLETIONS = "v1/chat/completions"

/**
 * How the Workers AI bodies are read and written: anything Cloudflare adds is ignored, a number sent as a string is
 * still read, and a vendor message with several shapes is told apart by its `type` (Deepgram's live messages).
 */
internal val WorkersAiJson =
    Json {
        ignoreUnknownKeys = true
        isLenient = true
        // a field sent as `null` that we default (a chunk's `"tool_calls": null`) reads as its default
        coerceInputValues = true
        classDiscriminator = "type"
    }

// ---- request ---------------------------------------------------------------------------------------------------

/** A chat completion request; a field left null is left out (it has a default), except [ChatMessage.content]. */
@Serializable
internal class ChatRequest(
    val model: String,
    @SerialName("max_completion_tokens") val maxCompletionTokens: Int,
    val messages: List<ChatMessage>,
    val tools: List<ChatTool>? = null,
    val stream: Boolean? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null,
)

/** One message of the conversation: `content` is always sent, as `null` for a turn that only called tools. */
@Serializable
internal class ChatMessage(
    val role: String,
    val content: String?,
    @SerialName("tool_calls") val toolCalls: List<ChatToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
)

@Serializable
internal class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean,
)

@Serializable
internal class ChatTool(val type: String, val function: ChatFunctionSpec)

/** [parameters] is the tool's JSON Schema, which the assistant writes as text; it goes out as JSON, unchanged. */
@Serializable
internal class ChatFunctionSpec(
    val name: String,
    val description: String,
    @Serializable(with = EmbeddedJson::class) val parameters: String,
)

// ---- response, whole or streamed --------------------------------------------------------------------------------

/** A chat completion, or one chunk of a streamed one (`delta` in place of `message`). */
@Serializable
internal class ChatCompletion(
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null,
    /** An error reported in the body (a chunk that breaks a stream off); only its presence matters. */
    @Serializable(with = RawJsonText::class) val error: String? = null,
)

@Serializable
internal class ChatChoice(
    val message: ChatReply? = null,
    val delta: ChatReply? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/** The model's words, or the next piece of them. Reasoning (`reasoning_content`) is not read. */
@Serializable
internal class ChatReply(
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ChatToolCall> = emptyList(),
)

/** A tool call; in a stream, a piece of one (its [index] joins the pieces). */
@Serializable
internal class ChatToolCall(
    val index: Int? = null,
    val id: String? = null,
    val type: String? = null,
    val function: ChatFunctionCall? = null,
)

/** [arguments] is the model's JSON-encoded string, opaque to us (decision 2026-10-27). */
@Serializable
internal class ChatFunctionCall(
    val name: String? = null,
    @Serializable(with = RawJsonText::class) val arguments: String? = null,
)

@Serializable
internal class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
) {
    fun toModel() = ModelUsage(inputTokens = promptTokens, outputTokens = completionTokens)
}

/**
 * Reads any JSON value as text: a string as itself, anything else as its JSON (a model that sends tool arguments as
 * an object rather than the JSON-encoded string). Written back as a string.
 */
internal object RawJsonText : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("RawJsonText", PrimitiveKind.STRING)

    override fun deserialize(decoder: Decoder): String {
        val element = (decoder as JsonDecoder).decodeJsonElement()
        return if (element is JsonPrimitive && element.isString) element.content else element.toString()
    }

    override fun serialize(encoder: Encoder, value: String) = encoder.encodeString(value)
}

/** Writes JSON held as text (a tool's schema) as the JSON itself, not as a string. */
internal object EmbeddedJson : KSerializer<String> {
    override val descriptor = PrimitiveSerialDescriptor("EmbeddedJson", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: String) =
        (encoder as JsonEncoder).encodeJsonElement(Json.parseToJsonElement(value))

    override fun deserialize(decoder: Decoder): String = (decoder as JsonDecoder).decodeJsonElement().toString()
}
