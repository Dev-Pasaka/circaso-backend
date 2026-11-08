package com.circaso.integrations.impl.anthropic

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.RedisScope
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.AiBill
import com.circaso.integrations.api.AiBilledDay
import com.circaso.integrations.api.AiBilledModel
import com.circaso.integrations.api.AiBilling
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Anthropic's own billing for the staff cost report (decision 2026-11-08), through the Admin API's Usage and Cost
 * endpoints with an Admin key (`sk-ant-admin…`, the secret `OPS_ANTHROPIC_ADMIN_KEY`):
 *
 * - `GET /v1/organizations/cost_report`: dollars by day, grouped by `description` (which names the model); `amount` is
 *   a decimal string in **cents**, read exactly and divided by 100.
 * - `GET /v1/organizations/usage_report/messages`: tokens and web searches by day, grouped by `model`.
 *
 * Both take `starting_at` / `ending_at` (RFC 3339, whole UTC days here, the end exclusive), `bucket_width=1d` and
 * at most 31 daily buckets a page; `has_more` / `next_page` page through the rest (a 366-day range is 12 pages each).
 * With [workspaceId] set, only that workspace counts (`default` is the default workspace, which Anthropic reports
 * with no id): the usage report filters on it, the cost report is also grouped by workspace and the others dropped.
 *
 * Anthropic asks for at most one poll a minute and says its figures lag a few minutes, so an answer is kept in Redis
 * ([cache], `aib:`) for [FRESH_FOR], and a failure keeps it from being asked again for [RETRY_AFTER]. The key goes in
 * `x-api-key` only and is never logged; every failure is [IntegrationError.Unavailable], so the report shows our
 * estimate alone. Over `HttpClients.named("anthropic-admin")` like every vendor (GETs, so its retries apply).
 */
class AnthropicAdminBilling(
    private val http: HttpClient,
    private val key: String,
    private val cache: RedisScope,
    private val workspaceId: String? = null,
    private val clock: Clock = Clock.systemUTC(),
) : AiBilling {
    private val log = LoggerFactory.getLogger(AnthropicAdminBilling::class.java)

    override val isEnabled: Boolean = true

    private val scope: String = workspaceId?.let { "workspace $it" } ?: "organisation"

    override suspend fun billed(from: LocalDate, until: LocalDate): Resource<AiBill> {
        if (!until.isAfter(from)) return Resource.error(IntegrationError.Rejected("The range is empty."))
        val range = "${workspaceId ?: "org"}:$from:$until"
        kept(range)?.let { return Resource.success(it) }
        if (safely { cache.exists("failed:$range") } == true) return unavailable()
        return try {
            val bill = fetch(from, until)
            safely {
                cache.set(
                    "bill:$range",
                    json.encodeToString(StoredBill.serializer(), bill.toStored()),
                    FRESH_FOR,
                )
            }
            Resource.success(bill)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Failure) {
            log.warn("anthropic billing unavailable: {}", e.message)
            failed(range)
        } catch (e: CircuitOpenException) {
            failed(range)
        } catch (e: Exception) {
            log.warn("anthropic billing unreachable: error={}", e::class.simpleName)
            failed(range)
        }
    }

    private suspend fun fetch(from: LocalDate, until: LocalDate): AiBill {
        val startingAt = from.atStartOfDay().toInstant(ZoneOffset.UTC).toString()
        val endingAt = until.atStartOfDay().toInstant(ZoneOffset.UTC).toString()
        val costs =
            pages(COST_PATH, "anthropic_cost_report", CostPage.serializer()) {
                parameter("starting_at", startingAt)
                parameter("ending_at", endingAt)
                parameter("bucket_width", "1d")
                parameter("limit", MAX_DAILY_BUCKETS)
                parameter("group_by[]", "description")
                if (workspaceId != null) parameter("group_by[]", "workspace_id")
            }
        val usage =
            pages(USAGE_PATH, "anthropic_usage_report", UsagePage.serializer()) {
                parameter("starting_at", startingAt)
                parameter("ending_at", endingAt)
                parameter("bucket_width", "1d")
                parameter("limit", MAX_DAILY_BUCKETS)
                parameter("group_by[]", "model")
                if (workspaceId != null && workspaceId != DEFAULT_WORKSPACE) {
                    parameter("workspace_ids[]", workspaceId)
                }
            }

        val costByDay = sortedMapOf<LocalDate, BigDecimal>()
        val costByModel = linkedMapOf<String, BigDecimal>()
        for (bucket in costs) {
            val day = dayOf(bucket.startingAt)
            for (line in bucket.results.filter { inScope(it.workspaceId) }) {
                if (line.currency != null && line.currency != "USD") throw Failure("a cost in ${line.currency}")
                val dollars =
                    line.amount.toBigDecimalOrNull()?.movePointLeft(2) ?: throw Failure("an amount we can't read")
                costByDay.merge(day, dollars, BigDecimal::add)
                costByModel.merge(line.model ?: OTHER, dollars, BigDecimal::add)
            }
        }
        val tokens = linkedMapOf<String, AiBilledModel>()
        for (line in usage.flatMap { it.results }.filter { inScope(it.workspaceId) }) {
            val model = line.model ?: OTHER
            val add =
                AiBilledModel(
                    model = model,
                    costUsd = BigDecimal.ZERO,
                    inputTokens = line.uncachedInputTokens,
                    cacheReadTokens = line.cacheReadInputTokens,
                    cacheWriteTokens =
                        (line.cacheCreation?.ephemeral5mInputTokens ?: 0) +
                            (line.cacheCreation?.ephemeral1hInputTokens ?: 0),
                    outputTokens = line.outputTokens,
                    webSearches = line.serverToolUse?.webSearchRequests ?: 0,
                )
            tokens.merge(model, add) { a, b -> a + b }
        }
        val models =
            (tokens.keys + costByModel.keys)
                .distinct()
                .map { model ->
                    (tokens[model] ?: AiBilledModel(model, BigDecimal.ZERO, 0, 0, 0, 0, 0))
                        .copy(costUsd = costByModel[model] ?: BigDecimal.ZERO)
                }.sortedWith(compareByDescending<AiBilledModel> { it.costUsd }.thenBy { it.model })
        return AiBill(
            provider = PROVIDER,
            scope = scope,
            costUsd = costByDay.values.fold(BigDecimal.ZERO, BigDecimal::add),
            days = costByDay.map { (day, cost) -> AiBilledDay(day, cost) },
            models = models,
            webSearches = models.sumOf { it.webSearches },
            fetchedAt = clock.instant(),
        )
    }

    /** Every page of one report: the same question again with `page=<next_page>` while `has_more`. */
    private suspend fun <B> pages(
        path: String,
        operationName: String,
        serializer: KSerializer<out Page<B>>,
        query: io.ktor.client.request.HttpRequestBuilder.() -> Unit,
    ): List<B> {
        val buckets = mutableListOf<B>()
        var next: String? = null
        repeat(MAX_PAGES) {
            val response =
                http.get(path) {
                    operation(operationName)
                    header(API_KEY_HEADER, key)
                    header(VERSION_HEADER, API_VERSION)
                    header(HttpHeaders.UserAgent, USER_AGENT)
                    query()
                    next?.let { parameter("page", it) }
                }
            when {
                response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden -> {
                    throw Failure(
                        "the admin key was refused (status ${response.status.value}); check OPS_ANTHROPIC_ADMIN_KEY",
                    )
                }

                !response.status.isSuccess() -> {
                    throw Failure("$operationName answered status ${response.status.value}")
                }
            }
            val page =
                runCatching { json.decodeFromString(serializer, response.bodyAsText()) }.getOrNull()
                    ?: throw Failure("$operationName answered something we can't read")
            buckets += page.data
            if (!page.hasMore || page.nextPage.isNullOrEmpty()) return buckets
            next = page.nextPage
        }
        throw Failure("$operationName needed more than $MAX_PAGES pages")
    }

    private fun inScope(lineWorkspace: String?): Boolean =
        when (workspaceId) {
            null -> true
            DEFAULT_WORKSPACE -> lineWorkspace == null
            else -> lineWorkspace == workspaceId
        }

    private suspend fun kept(range: String): AiBill? =
        safely { cache.get("bill:$range") }
            ?.let { runCatching { json.decodeFromString(StoredBill.serializer(), it) }.getOrNull() }
            ?.toBill()

    private suspend fun failed(range: String): Resource<AiBill> {
        safely { cache.set("failed:$range", "1", RETRY_AFTER) }
        return unavailable()
    }

    private fun unavailable(): Resource<AiBill> = Resource.error(IntegrationError.Unavailable(VENDOR))

    /** Redis is only a cache here: when it fails, Anthropic is asked (or not) as if nothing were kept. */
    private suspend fun <T> safely(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("anthropic billing cache unavailable: error={}", e::class.simpleName)
            null
        }

    private fun dayOf(startingAt: String): LocalDate =
        runCatching { LocalDate.ofInstant(Instant.parse(startingAt), ZoneOffset.UTC) }.getOrNull()
            ?: throw Failure("a bucket start we can't read")

    private class Failure(message: String) : RuntimeException(message)

    private operator fun AiBilledModel.plus(other: AiBilledModel) =
        copy(
            inputTokens = inputTokens + other.inputTokens,
            cacheReadTokens = cacheReadTokens + other.cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens + other.cacheWriteTokens,
            outputTokens = outputTokens + other.outputTokens,
            webSearches = webSearches + other.webSearches,
        )

    private interface Page<B> {
        val data: List<B>
        val hasMore: Boolean
        val nextPage: String?
    }

    @Serializable
    private data class CostPage(
        override val data: List<CostBucket> = emptyList(),
        @SerialName("has_more") override val hasMore: Boolean = false,
        @SerialName("next_page") override val nextPage: String? = null,
    ) : Page<CostBucket>

    @Serializable
    private data class CostBucket(
        @SerialName("starting_at") val startingAt: String,
        val results: List<CostLine> = emptyList(),
    )

    @Serializable
    private data class CostLine(
        val amount: String,
        val currency: String? = null,
        val model: String? = null,
        @SerialName("workspace_id") val workspaceId: String? = null,
    )

    @Serializable
    private data class UsagePage(
        override val data: List<UsageBucket> = emptyList(),
        @SerialName("has_more") override val hasMore: Boolean = false,
        @SerialName("next_page") override val nextPage: String? = null,
    ) : Page<UsageBucket>

    @Serializable
    private data class UsageBucket(
        @SerialName("starting_at") val startingAt: String,
        val results: List<UsageLine> = emptyList(),
    )

    @Serializable
    private data class UsageLine(
        val model: String? = null,
        @SerialName("workspace_id") val workspaceId: String? = null,
        @SerialName("uncached_input_tokens") val uncachedInputTokens: Long = 0,
        @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long = 0,
        @SerialName("cache_creation") val cacheCreation: CacheCreation? = null,
        @SerialName("output_tokens") val outputTokens: Long = 0,
        @SerialName("server_tool_use") val serverToolUse: ServerToolUse? = null,
    )

    @Serializable
    private data class CacheCreation(
        @SerialName("ephemeral_5m_input_tokens") val ephemeral5mInputTokens: Long = 0,
        @SerialName("ephemeral_1h_input_tokens") val ephemeral1hInputTokens: Long = 0,
    )

    @Serializable
    private data class ServerToolUse(
        @SerialName("web_search_requests") val webSearchRequests: Long = 0,
    )

    /** The answer as Redis keeps it: dollars as exact decimal strings. */
    @Serializable
    private data class StoredBill(
        val scope: String,
        val costUsd: String,
        val days: List<StoredDay>,
        val models: List<StoredModel>,
        val webSearches: Long,
        val fetchedAt: Long,
    )

    @Serializable
    private data class StoredDay(val day: String, val costUsd: String)

    @Serializable
    private data class StoredModel(
        val model: String,
        val costUsd: String,
        val inputTokens: Long,
        val cacheReadTokens: Long,
        val cacheWriteTokens: Long,
        val outputTokens: Long,
        val webSearches: Long,
    )

    private fun AiBill.toStored() =
        StoredBill(
            scope,
            costUsd.toPlainString(),
            days.map { StoredDay(it.day.toString(), it.costUsd.toPlainString()) },
            models.map {
                StoredModel(
                    it.model,
                    it.costUsd.toPlainString(),
                    it.inputTokens,
                    it.cacheReadTokens,
                    it.cacheWriteTokens,
                    it.outputTokens,
                    it.webSearches,
                )
            },
            webSearches,
            fetchedAt.toEpochMilli(),
        )

    private fun StoredBill.toBill() =
        AiBill(
            PROVIDER,
            scope,
            BigDecimal(costUsd),
            days.map { AiBilledDay(LocalDate.parse(it.day), BigDecimal(it.costUsd)) },
            models.map {
                AiBilledModel(
                    it.model,
                    BigDecimal(it.costUsd),
                    it.inputTokens,
                    it.cacheReadTokens,
                    it.cacheWriteTokens,
                    it.outputTokens,
                    it.webSearches,
                )
            },
            webSearches,
            Instant.ofEpochMilli(fetchedAt),
        )

    companion object {
        const val BASE_URL = "https://api.anthropic.com"
        const val PROVIDER = "anthropic"

        /** `OPS_ANTHROPIC_WORKSPACE_ID=default` counts only the default workspace (no id in Anthropic's reports). */
        const val DEFAULT_WORKSPACE = "default"
        private const val COST_PATH = "/v1/organizations/cost_report"
        private const val USAGE_PATH = "/v1/organizations/usage_report/messages"
        private const val API_KEY_HEADER = "x-api-key"
        private const val VERSION_HEADER = "anthropic-version"
        private const val API_VERSION = "2023-06-01"
        private const val USER_AGENT = "Circaso-Ops/1.0 (https://circaso.com)"
        private const val VENDOR = "Anthropic billing"
        private const val OTHER = "other"

        /** Anthropic's most daily buckets a page. */
        private const val MAX_DAILY_BUCKETS = 31

        /** 366 days are 12 pages; anything past this is an answer that never ends. */
        private const val MAX_PAGES = 24

        /** How long an answer is kept: Anthropic asks for at most a poll a minute and lags about five. */
        val FRESH_FOR: Duration = Duration.ofMinutes(5)

        /** After a failure, Anthropic isn't asked again for this long. */
        val RETRY_AFTER: Duration = Duration.ofSeconds(60)

        /** The connect and request timeouts of one page. */
        val TIMEOUT: Duration = Duration.ofSeconds(15)
        private val json = Json { ignoreUnknownKeys = true }
    }
}
