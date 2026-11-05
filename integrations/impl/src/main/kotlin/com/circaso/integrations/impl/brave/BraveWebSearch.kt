package com.circaso.integrations.impl.brave

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * Brave's Search API (decision 2026-11-05, Pasaka): `GET /res/v1/web/search` with the key in `X-Subscription-Token`.
 * Its own index (not a reseller of another engine's), a plain per-query price and a page age per result, which lets
 * Asha tell this week's news from an old page. The query is the only thing sent and is never logged, nor is the key.
 * A GET is safe to repeat, so the named client's retry table applies; a refusal or an answer we can't read is
 * unavailable and the model goes on without it.
 */
class BraveWebSearch(private val http: HttpClient, private val key: String) : WebSearch {
    private val log = LoggerFactory.getLogger(BraveWebSearch::class.java)

    override suspend fun search(query: String, limit: Int): Resource<List<WebResult>> {
        val text = query.trim()
        if (text.isEmpty()) return Resource.error(IntegrationError.Rejected("The search is empty."))
        val count = limit.coerceIn(1, MAX_RESULTS)
        return try {
            val response =
                http.get(BASE_URL + PATH) {
                    operation("web_search")
                    header(TOKEN_HEADER, key)
                    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
                    parameter("q", text.take(MAX_QUERY_CHARS))
                    parameter("count", count)
                    parameter("safesearch", "moderate")
                    parameter("text_decorations", false)
                }
            when {
                response.status in REJECTED -> {
                    Resource.error(IntegrationError.Rejected("The search couldn't be run."))
                }

                !response.status.isSuccess() -> {
                    log.warn("brave search refused: status={}", response.status.value)
                    Resource.error(IntegrationError.Unavailable(VENDOR))
                }

                else -> {
                    results(response.bodyAsText(), count)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CircuitOpenException) {
            Resource.error(IntegrationError.Unavailable(VENDOR))
        } catch (e: Exception) {
            log.warn("brave search unreachable: error={}", e::class.simpleName)
            Resource.error(IntegrationError.Unavailable(VENDOR))
        }
    }

    private fun results(body: String, limit: Int): Resource<List<WebResult>> {
        val answer =
            runCatching { json.decodeFromString(BraveAnswer.serializer(), body) }.getOrNull()
                ?: run {
                    log.warn("brave search answered something we can't read")
                    return Resource.error(IntegrationError.Unavailable(VENDOR))
                }
        val results =
            answer.web
                ?.results
                .orEmpty()
                .filter { it.url.isNotBlank() }
                .take(limit)
                .map { WebResult(plain(it.title), it.url, plain(it.description), publishedAt(it.pageAge)) }
        return Resource.success(results)
    }

    @Serializable
    private data class BraveAnswer(val web: BraveWeb? = null)

    @Serializable
    private data class BraveWeb(val results: List<BraveResult> = emptyList())

    @Serializable
    private data class BraveResult(
        val title: String = "",
        val url: String = "",
        val description: String = "",
        @SerialName("page_age") val pageAge: String? = null,
    )

    companion object {
        const val BASE_URL = "https://api.search.brave.com"
        private const val PATH = "/res/v1/web/search"
        private const val TOKEN_HEADER = "X-Subscription-Token"
        private const val VENDOR = "Web search"

        /** What Brave answers a query it won't run (too long, malformed): the query's fault, not Brave being down. */
        private val REJECTED = setOf(HttpStatusCode.BadRequest, HttpStatusCode.UnprocessableEntity)

        /** Brave answers up to 20 a page; Asha needs a few. */
        const val MAX_RESULTS = 10

        /** Brave refuses queries over 400 characters. */
        private const val MAX_QUERY_CHARS = 400
        private val TAG = Regex("<[^>]*>")
        private val SPACE = Regex("\\s+")
        private val json = Json { ignoreUnknownKeys = true }

        /** Snippets can still carry a tag or an entity; they are taken out, as are runs of whitespace. */
        internal fun plain(text: String): String =
            text
                .replace(TAG, "")
                .replace("&amp;", "&")
                .replace("&#39;", "'")
                .replace("&#x27;", "'")
                .replace("&quot;", "\"")
                .replace(SPACE, " ")
                .trim()

        /** `page_age` is a local date-time with no zone (`2026-10-30T08:15:00`), read as UTC; anything else is none. */
        internal fun publishedAt(raw: String?): Instant? {
            val text = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return runCatching { Instant.parse(text) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(text).toInstant(ZoneOffset.UTC) }.getOrNull()
                ?: runCatching { LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant() }.getOrNull()
        }
    }
}
