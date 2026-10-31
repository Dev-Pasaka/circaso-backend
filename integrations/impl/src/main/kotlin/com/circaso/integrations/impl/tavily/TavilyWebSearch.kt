package com.circaso.integrations.impl.tavily

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Tavily's search API (decision 2026-10-31, Pasaka: Cloudflare offers no web search; Tavily is built for agents and
 * answers clean text snippets). `POST /search` with the key as a Bearer token; the query is the only thing sent and
 * is never logged, nor is the key. The POST carries no idempotency key, so the named client never repeats it: a
 * failed search is answered as unavailable and the model goes on without it.
 */
class TavilyWebSearch(private val http: HttpClient, private val key: String) : WebSearch {
    private val log = LoggerFactory.getLogger(TavilyWebSearch::class.java)

    override suspend fun search(query: String, limit: Int): Resource<List<WebResult>> {
        val text = query.trim()
        if (text.isEmpty()) return Resource.error(IntegrationError.Rejected("The search is empty."))
        val request = TavilyRequest(text.take(MAX_QUERY_CHARS), limit.coerceIn(1, MAX_RESULTS))
        return try {
            val response =
                http.post(BASE_URL + PATH) {
                    operation("web_search")
                    header(HttpHeaders.Authorization, "Bearer $key")
                    contentType(ContentType.Application.Json)
                    setBody(json.encodeToString(TavilyRequest.serializer(), request))
                }
            when {
                response.status == HttpStatusCode.BadRequest -> {
                    Resource.error(IntegrationError.Rejected("The search couldn't be run."))
                }

                !response.status.isSuccess() -> {
                    log.warn("tavily search refused: status={}", response.status.value)
                    Resource.error(IntegrationError.Unavailable(VENDOR))
                }

                else -> {
                    results(response.bodyAsText(), request.maxResults)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CircuitOpenException) {
            Resource.error(IntegrationError.Unavailable(VENDOR))
        } catch (e: Exception) {
            log.warn("tavily search unreachable: error={}", e::class.simpleName)
            Resource.error(IntegrationError.Unavailable(VENDOR))
        }
    }

    private fun results(body: String, limit: Int): Resource<List<WebResult>> {
        val answer =
            runCatching { json.decodeFromString(TavilyAnswer.serializer(), body) }.getOrNull()
                ?: run {
                    log.warn("tavily search answered something we can't read")
                    return Resource.error(IntegrationError.Unavailable(VENDOR))
                }
        val results =
            answer.results
                .filter { it.url.isNotBlank() }
                .take(limit)
                .map { WebResult(plain(it.title), it.url, plain(it.content)) }
        return Resource.success(results)
    }

    @Serializable
    private data class TavilyRequest(
        val query: String,
        @SerialName("max_results") val maxResults: Int,
        @SerialName("search_depth") val searchDepth: String = "basic",
        @SerialName("include_answer") val includeAnswer: Boolean = false,
    )

    @Serializable
    private data class TavilyAnswer(val results: List<TavilyResult> = emptyList())

    @Serializable
    private data class TavilyResult(val title: String = "", val url: String = "", val content: String = "")

    companion object {
        const val BASE_URL = "https://api.tavily.com"
        private const val PATH = "/search"
        private const val VENDOR = "Web search"

        /** Tavily answers up to 20; Asha needs a few. */
        const val MAX_RESULTS = 10
        private const val MAX_QUERY_CHARS = 400
        private val TAG = Regex("<[^>]*>")
        private val SPACE = Regex("\\s+")
        private val json =
            Json {
                ignoreUnknownKeys = true
                encodeDefaults = true
            }

        /** Snippets are mostly plain already; any markup or run of whitespace left is taken out. */
        internal fun plain(text: String): String =
            text
                .replace(TAG, "")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&quot;", "\"")
                .replace(SPACE, " ")
                .trim()
    }
}
