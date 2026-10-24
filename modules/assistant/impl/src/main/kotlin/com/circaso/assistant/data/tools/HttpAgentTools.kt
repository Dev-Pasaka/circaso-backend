package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Asha's tools call the other modules' read-only `/v1/agent/` routes with the user's agent token, as an outside
 * client would, so they are bound by the same rules: the gateway refuses the agent token anywhere else and for
 * anything but `GET`, and gives it its own rate-limit bucket. Whatever comes back is the user's own data, and
 * possibly other people's words, so the agent treats it as untrusted.
 */
class HttpAgentTools(private val http: HttpClient, private val baseUrl: String) : AgentTools {
    override fun specs(): List<ToolSpec> = listOf(AshaPrompt.searchContacts, AshaPrompt.listContacts)

    override fun handles(toolName: String) = toolName == "searchContacts" || toolName == "listContacts"

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        agentToken: String,
    ): Resource<String> {
        val arguments =
            runCatching {
                CircasoJson.parseToJsonElement(argumentsJson).jsonObject
            }.getOrDefault(JsonObject(emptyMap()))
        val response =
            runCatching {
                when (toolName) {
                    "searchContacts" -> {
                        http.get("$baseUrl/v1/agent/users/contacts/search") {
                            header(HttpHeaders.Authorization, "Bearer $agentToken")
                            parameter("q", (arguments["query"] as? JsonPrimitive)?.contentOrNull.orEmpty())
                            parameter("limit", 5)
                        }
                    }

                    else -> {
                        http.get("$baseUrl/v1/agent/users/contacts") {
                            header(HttpHeaders.Authorization, "Bearer $agentToken")
                            parameter(
                                "limit",
                                ((arguments["limit"] as? JsonPrimitive)?.intOrNull ?: 20).coerceIn(1, 50),
                            )
                        }
                    }
                }
            }.getOrElse { return Resource.error(IntegrationError.Unavailable("the contacts lookup"), it) }

        if (!response.status.isSuccess()) {
            return Resource.error(
                IntegrationError.Rejected("status ${response.status.value}"),
            )
        }
        // Only the envelope's data goes to the model.
        val data = runCatching { CircasoJson.parseToJsonElement(response.bodyAsText()).jsonObject["data"] }.getOrNull()
        return Resource.success(data?.toString().orEmpty())
    }
}
