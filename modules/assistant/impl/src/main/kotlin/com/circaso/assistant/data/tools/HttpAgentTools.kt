package com.circaso.assistant.data.tools

import com.circaso.assistant.domain.ports.AgentTools
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ToolSpec
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.users.api.AgentContactsResponse
import com.circaso.web.DefaultResponse
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

/**
 * Asha's tools call the other modules' read-only `/v1/agent/` routes with the user's agent token, as an outside
 * client would, so they are bound by the same rules: the gateway refuses the agent token anywhere else and for
 * anything but `GET`, and gives it its own rate-limit bucket. Whatever comes back is the user's own data, and
 * possibly other people's words, so the agent treats it as untrusted.
 *
 * Typed both ways (decision 2026-10-27): the model's arguments (an opaque JSON string on the wire) are read into one
 * class per tool, and a route's answer into the envelope around its data, which alone goes back to the model.
 */
class HttpAgentTools(private val http: HttpClient, private val baseUrl: String) : AgentTools {
    private val log = LoggerFactory.getLogger(HttpAgentTools::class.java)

    override fun specs(): List<ToolSpec> = listOf(AshaPrompt.searchContacts, AshaPrompt.listContacts)

    override fun handles(toolName: String) = toolName == SEARCH_CONTACTS || toolName == LIST_CONTACTS

    override suspend fun call(
        toolName: String,
        argumentsJson: String,
        agentToken: String,
    ): Resource<String> {
        val response =
            runCatching {
                when (toolName) {
                    SEARCH_CONTACTS -> searchContacts(arguments(argumentsJson, SearchContactsArgs()), agentToken)
                    else -> listContacts(arguments(argumentsJson, ListContactsArgs()), agentToken)
                }
            }.getOrElse { return Resource.error(IntegrationError.Unavailable(CONTACTS_LOOKUP), it) }

        if (!response.status.isSuccess()) {
            return Resource.error(IntegrationError.Rejected("status ${response.status.value}"))
        }
        val data =
            dataOf(response, AgentContactsResponse.serializer()) ?: run {
                log.warn("agent route answer didn't decode: operation={}", toolName)
                return Resource.error(IntegrationError.Unavailable(CONTACTS_LOOKUP))
            }
        return Resource.success(CircasoJson.encodeToString(AgentContactsResponse.serializer(), data))
    }

    private suspend fun searchContacts(arguments: SearchContactsArgs, agentToken: String) =
        http.get("$baseUrl/v1/agent/users/contacts/search") {
            header(HttpHeaders.Authorization, "Bearer $agentToken")
            parameter("q", arguments.query)
            parameter("limit", SEARCH_LIMIT)
        }

    private suspend fun listContacts(arguments: ListContactsArgs, agentToken: String) =
        http.get("$baseUrl/v1/agent/users/contacts") {
            header(HttpHeaders.Authorization, "Bearer $agentToken")
            parameter("limit", (arguments.limit ?: LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT))
        }

    /** The model's arguments; ones that don't read as the tool's (the model's mistake) fall back to its defaults. */
    private inline fun <reified T> arguments(json: String, fallback: T): T =
        runCatching { CircasoJson.decodeFromString<T>(json) }.getOrDefault(fallback)

    /** The route's data, read from its envelope; null when the answer isn't one (only the data goes to the model). */
    private suspend fun <T : Any> dataOf(response: HttpResponse, serializer: KSerializer<T>): T? =
        runCatching {
            CircasoJson.decodeFromString(DefaultResponse.serializer(serializer), response.bodyAsText()).data
        }.getOrNull()

    private companion object {
        const val CONTACTS_LOOKUP = "the contacts lookup"
        const val SEARCH_CONTACTS = "searchContacts"
        const val LIST_CONTACTS = "listContacts"
        const val SEARCH_LIMIT = 5
        const val LIST_LIMIT = 20
        const val MAX_LIST_LIMIT = 50
    }
}

/** `searchContacts` (its schema is [AshaPrompt.searchContacts]). */
@Serializable
private data class SearchContactsArgs(val query: String = "")

/** `listContacts` (its schema is [AshaPrompt.listContacts]). */
@Serializable
private data class ListContactsArgs(val limit: Int? = null)
