package com.circaso.integrations.impl.anthropic

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.anthropic.AnthropicCacheControl
import ai.koog.prompt.executor.clients.anthropic.AnthropicParams
import ai.koog.prompt.executor.clients.anthropic.models.AnthropicThinking
import ai.koog.prompt.message.AttachmentContent
import ai.koog.prompt.message.AttachmentSource
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.message.ResponseMetaInfo
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ToolCall
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Our conversation as a Koog [Prompt] for Anthropic's Messages API.
 *
 * - The system prompt is two parts: the fixed one with a cache breakpoint, then this turn's part without one, so the
 *   fixed part (with the tools before it) is read from the cache by every user's next call.
 * - A tool call the model made goes back as an assistant `tool_use` block, after any text it wrote first; empty text is
 *   left out (Anthropic refuses an empty text block).
 * - Tool results that follow one another go back in **one** user message, one `tool_result` block each: Anthropic
 *   wants every result of an assistant turn's calls together (Asha runs every call of a step).
 * - Thinking is off ([AnthropicThinking.Disabled]): Koog 1.3.0 doesn't stream a thinking block's signature, and our
 *   conversation can't carry one through a tool round trip or a pause for the phone. Effort is the dial instead,
 *   sent as `output_config.effort`, which Koog doesn't model, through its additional properties.
 * - A user message's pictures (Asha's "Show Asha my screen", 2026-11-09) go as image blocks before its text.
 * - The request also asks Anthropic to cache the conversation itself (top-level `cache_control`), so the second step of
 *   a tool loop, and the next turn, read what was already sent.
 */
internal object AnthropicPrompts {
    private val json = Json

    fun prompt(request: ModelRequest, mode: AnthropicMode): Prompt =
        Prompt(
            messages = listOf(system(request)) + conversation(request.messages),
            id = "asha",
            params =
                AnthropicParams(
                    maxTokens = mode.maxTokens,
                    thinking = AnthropicThinking.Disabled(),
                    cacheControl = AnthropicCacheControl.Default,
                    additionalProperties =
                        mapOf(
                            "output_config" to
                                json.encodeToJsonElement(OutputConfig.serializer(), OutputConfig(mode.effort.wire)),
                        ),
                ),
        )

    private fun system(request: ModelRequest): Message.System =
        Message.System(
            listOfNotNull(
                MessagePart.Text(request.system, AnthropicCacheControl.Default),
                request.systemTail?.takeIf { it.isNotBlank() }?.let { MessagePart.Text(it) },
            ),
            RequestMetaInfo.Empty,
        )

    private fun conversation(messages: List<ModelMessage>): List<Message> {
        val out = mutableListOf<Message>()
        val results = mutableListOf<MessagePart.Tool.Result>()

        fun flushResults() {
            if (results.isEmpty()) return
            out += Message.User(results.toList(), RequestMetaInfo.Empty)
            results.clear()
        }
        for (message in messages) {
            when (message) {
                is ModelMessage.ToolResult -> {
                    results += MessagePart.Tool.Result(message.callId, message.name, message.content)
                }

                is ModelMessage.User -> {
                    flushResults()
                    out += user(message)
                }

                is ModelMessage.Assistant -> {
                    flushResults()
                    assistant(message)?.let { out += it }
                }
            }
        }
        flushResults()
        return out
    }

    /** A user's message; its pictures go first, then the text, as Anthropic recommends for vision. */
    private fun user(message: ModelMessage.User): Message.User =
        if (message.images.isEmpty()) {
            Message.User(message.text, RequestMetaInfo.Empty)
        } else {
            val parts: List<MessagePart.RequestPart> =
                message.images.map { image ->
                    MessagePart.Attachment(
                        AttachmentSource.Image(
                            content = AttachmentContent.Binary.Bytes(image.bytes),
                            format = image.mediaType.substringAfter('/'),
                            mimeType = image.mediaType,
                            fileName = "screen." + image.mediaType.substringAfter('/'),
                        ),
                    )
                } + MessagePart.Text(message.text)
            Message.User(parts, RequestMetaInfo.Empty)
        }

    private fun assistant(message: ModelMessage.Assistant): Message.Assistant? {
        val parts =
            listOfNotNull(message.text?.takeIf { it.isNotBlank() }?.let { MessagePart.Text(it) }) +
                message.toolCalls.map(::call)
        return if (parts.isEmpty()) null else Message.Assistant(parts, ResponseMetaInfo.Empty)
    }

    /** A call goes back with its arguments as a JSON object; arguments that aren't one go back as `{}`. */
    private fun call(call: ToolCall): MessagePart.Tool.Call {
        val part = MessagePart.Tool.Call(call.id, call.name, call.argumentsJson)
        return if (runCatching { part.argsJson }.isSuccess) part else MessagePart.Tool.Call(call.id, call.name, "{}")
    }
}

/** Anthropic's `output_config`, for its `effort` only. */
@Serializable
private data class OutputConfig(val effort: String)
