package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.Resource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/**
 * Asha's text model (`@cf/moonshotai/kimi-k2.6` by default) on the OpenAI-compatible chat completions route. Our
 * conversation becomes OpenAI messages: the system prompt first, a tool call the model made earlier goes back as an
 * assistant message with `tool_calls`, and its result as a `tool` message with the same call id, so a round trip of
 * tool calls reads to the model exactly as it happened. Nothing of the conversation is logged.
 *
 * [maxOutputTokens] caps the answer, reasoning included, as `max_completion_tokens` (Kimi K2.6 documents `max_tokens`
 * as its deprecated alias). The call is never retried: one turn waits at most the client's 60 s.
 */
class WorkersAiLanguageModel(
    private val gateway: WorkersAiGateway,
    private val model: String,
    private val maxOutputTokens: Int,
) : LanguageModel {
    private val log = LoggerFactory.getLogger(WorkersAiLanguageModel::class.java)

    override suspend fun complete(request: ModelRequest): Resource<ModelResponse> {
        val tools =
            request.tools.map { tool ->
                toolJson(tool)
                    ?: return Resource.error(
                        IntegrationError.Rejected("Tool '${tool.name}' has a parameters schema that isn't JSON."),
                    )
            }
        val body =
            buildJsonObject {
                put("model", model)
                put("max_completion_tokens", maxOutputTokens)
                put("messages", messagesJson(request))
                if (tools.isNotEmpty()) put("tools", JsonArray(tools))
            }

        return when (val reply = gateway.post(CHAT_COMPLETIONS, "chat_completion", body, retried = false)) {
            is Resource.Success -> read(reply.data)
            is Resource.Error -> reply
        }
    }

    /** An answer with neither text nor a tool call (the budget spent on reasoning, say) is no answer at all. */
    private fun read(reply: GatewayReply): Resource<ModelResponse> {
        val answer = reply.json()
        val message = answer?.firstMessage()
        if (answer == null || message == null) {
            log.warn("workers-ai chat completion had no message")
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        val text = message.contentText()?.takeIf { it.isNotBlank() }
        val toolCalls = toolCalls(message)
        if (text == null && toolCalls.isEmpty()) {
            log.warn("workers-ai chat completion was empty: finish_reason={}", answer.finishReason())
            return Resource.error(IntegrationError.Unavailable(WorkersAiGateway.VENDOR))
        }
        return Resource.success(ModelResponse(text, toolCalls, answer.usage()))
    }

    private fun toolCalls(message: JsonObject): List<ToolCall> =
        (message["tool_calls"] as? JsonArray).orEmpty().mapNotNull { call ->
            val fields = call as? JsonObject ?: return@mapNotNull null
            val function = fields["function"] as? JsonObject ?: return@mapNotNull null
            val id = fields.text("id") ?: return@mapNotNull null
            val name = function.text("name") ?: return@mapNotNull null
            ToolCall(id, name, argumentsText(function["arguments"]))
        }

    /** Arguments come as a JSON-encoded string; a model that sends an object instead still works. */
    private fun argumentsText(arguments: JsonElement?): String =
        when (arguments) {
            null -> "{}"
            is JsonPrimitive -> arguments.content
            else -> arguments.toString()
        }

    private fun messagesJson(request: ModelRequest) =
        buildJsonArray {
            add(
                buildJsonObject {
                    put("role", "system")
                    put("content", request.system)
                },
            )
            request.messages.forEach { add(messageJson(it)) }
        }

    private fun messageJson(message: ModelMessage): JsonObject =
        when (message) {
            is ModelMessage.User -> {
                buildJsonObject {
                    put("role", "user")
                    put("content", message.text)
                }
            }

            is ModelMessage.Assistant -> {
                buildJsonObject {
                    put("role", "assistant")
                    put("content", message.text)
                    if (message.toolCalls.isNotEmpty()) {
                        putJsonArray("tool_calls") { message.toolCalls.forEach { add(toolCallJson(it)) } }
                    }
                }
            }

            is ModelMessage.ToolResult -> {
                buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", message.callId)
                    put("name", message.name)
                    put("content", message.content)
                }
            }
        }

    private fun toolCallJson(call: ToolCall) =
        buildJsonObject {
            put("id", call.id)
            put("type", "function")
            putJsonObject("function") {
                put("name", call.name)
                put("arguments", call.argumentsJson)
            }
        }

    private fun toolJson(tool: ToolSpec): JsonObject? {
        val parameters = runCatching { Json.parseToJsonElement(tool.parametersJsonSchema) }.getOrNull() as? JsonObject
        parameters ?: return null
        return buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", parameters)
            }
        }
    }
}
