package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory

/**
 * Asha's text model (`@cf/moonshotai/kimi-k2.6` by default) on the OpenAI-compatible chat completions route. Our
 * conversation becomes OpenAI messages: the system prompt first, a tool call the model made earlier goes back as an
 * assistant message with `tool_calls`, and its result as a `tool` message with the same call id, so a round trip of
 * tool calls reads to the model exactly as it happened. Nothing of the conversation is logged.
 *
 * [maxOutputTokens] caps the answer, reasoning included, as `max_completion_tokens` (Kimi K2.6 documents `max_tokens`
 * as its deprecated alias). The call is never retried: one turn waits at most the client's 60 s. Asha's turns use
 * [stream], so her reply reaches the user as it is written.
 */
class WorkersAiLanguageModel(
    private val gateway: WorkersAiGateway,
    private val model: String,
    private val maxOutputTokens: Int,
) : LanguageModel {
    private val log = LoggerFactory.getLogger(WorkersAiLanguageModel::class.java)

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val body =
            when (val built = body(request, isStreamed = false)) {
                is Resource.Success -> built.data
                is Resource.Error -> return built
            }
        return when (val reply = gateway.post(CHAT_COMPLETIONS, "chat_completion", body, retried = false)) {
            is Resource.Success -> read(reply.data)
            is Resource.Error -> reply
        }
    }

    /**
     * The answer as Kimi writes it (`stream: true`, decision 2026-10-27), asking for the token usage in the last
     * chunk (`stream_options.include_usage`). Never retried, as [complete].
     */
    override suspend fun stream(request: ModelRequest): Resource<ModelStream> {
        val body =
            when (val built = body(request, isStreamed = true)) {
                is Resource.Success -> built.data
                is Resource.Error -> return built
            }
        return when (val opened = gateway.stream(CHAT_COMPLETIONS, "chat_completion_stream", body)) {
            is Resource.Success -> Resource.success(ChatCompletionStream(opened.data, body.length))
            is Resource.Error -> opened
        }
    }

    /** The request as JSON; a tool whose schema isn't a JSON object is refused before any call. */
    private fun body(request: ModelRequest, isStreamed: Boolean): Resource<String> {
        request.tools.firstOrNull { !isSchemaObject(it.parametersJsonSchema) }?.let {
            return Resource.error(
                IntegrationError.Rejected("Tool '${it.name}' has a parameters schema that isn't JSON."),
            )
        }
        val body =
            ChatRequest(
                model = model,
                maxCompletionTokens = maxOutputTokens,
                messages = messages(request),
                tools = request.tools.map(::tool).ifEmpty { null },
                stream = if (isStreamed) true else null,
                streamOptions = if (isStreamed) StreamOptions(includeUsage = true) else null,
            )
        return Resource.success(WorkersAiJson.encodeToString(ChatRequest.serializer(), body))
    }

    /** An answer with neither text nor a tool call (the budget spent on reasoning, say) is no answer at all. */
    private fun read(reply: GatewayReply): Resource<ModelResponse> {
        val answer = reply.decode(ChatCompletion.serializer())
        val choice = answer?.choices?.firstOrNull()
        val message = choice?.message
        if (message == null) {
            log.warn("workers-ai chat completion had no message")
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        val text = message.content?.takeIf { it.isNotBlank() }
        val toolCalls = message.toolCalls.mapNotNull(::toolCall)
        if (text == null && toolCalls.isEmpty()) {
            log.warn("workers-ai chat completion was empty: finish_reason={}", choice.finishReason)
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        return Resource.success(ModelResponse(text, toolCalls, answer.usage?.toModel() ?: ModelUsage(0, 0)))
    }

    private fun toolCall(call: ChatToolCall): ToolCall? {
        val id = call.id ?: return null
        val name = call.function?.name ?: return null
        return ToolCall(id, name, call.function.arguments ?: "{}")
    }

    private fun messages(request: ModelRequest): List<ChatMessage> =
        listOf(ChatMessage(role = "system", content = request.system)) + request.messages.map(::message)

    private fun message(message: ModelMessage): ChatMessage =
        when (message) {
            is ModelMessage.User -> {
                ChatMessage(role = "user", content = message.text)
            }

            is ModelMessage.Assistant -> {
                ChatMessage(
                    role = "assistant",
                    content = message.text,
                    toolCalls = message.toolCalls.map(::toolCallOut).ifEmpty { null },
                )
            }

            is ModelMessage.ToolResult -> {
                ChatMessage(
                    role = "tool",
                    content = message.content,
                    toolCallId = message.callId,
                    name = message.name,
                )
            }
        }

    private fun toolCallOut(call: ToolCall) =
        ChatToolCall(
            id = call.id,
            type = "function",
            function = ChatFunctionCall(name = call.name, arguments = call.argumentsJson),
        )

    private fun tool(tool: ToolSpec) =
        ChatTool(
            type = "function",
            function = ChatFunctionSpec(tool.name, tool.description, tool.parametersJsonSchema),
        )
}
