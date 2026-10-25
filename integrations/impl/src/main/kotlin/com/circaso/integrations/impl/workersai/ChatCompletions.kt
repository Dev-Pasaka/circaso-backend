package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.ModelUsage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** The OpenAI-compatible chat completions route of the AI REST API, shared by the text and image models. */
internal const val CHAT_COMPLETIONS = "v1/chat/completions"

/** `choices[0].message` of a chat completion, or null when the answer has none. */
internal fun JsonObject.firstMessage(): JsonObject? =
    ((this["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject

/** Why the model stopped (`stop`, `length`, `tool_calls`): the only part of an empty answer worth logging. */
internal fun JsonObject.finishReason(): String? =
    ((this["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.text("finish_reason")

/** The message's text; null when the model answered only with tool calls. */
internal fun JsonObject.contentText(): String? =
    when (val content = this["content"]) {
        null, JsonNull -> null
        is JsonPrimitive -> content.content
        else -> null
    }

/** Tokens in and out, as Workers AI counted them; zero for a count it left out. */
internal fun JsonObject.usage(): ModelUsage {
    val usage = this["usage"] as? JsonObject

    fun count(name: String) = (usage?.get(name) as? JsonPrimitive)?.intOrNull ?: 0
    return ModelUsage(inputTokens = count("prompt_tokens"), outputTokens = count("completion_tokens"))
}

/** A string field, or null. */
internal fun JsonObject.text(name: String): String? = (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content
