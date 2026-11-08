package com.circaso.integrations.impl.anthropic

import com.circaso.integrations.api.AiBill
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.MemoryRedis
import com.circaso.shared.Resource
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Anthropic's Admin API usage and cost reports over a fake server (MockEngine): the shapes and paging documented on
 * platform.claude.com (Usage and Cost API, read 2026-11-08), cents read exactly, the key in its header only, answers
 * kept a few minutes and a failure turned into "unavailable".
 */
class AnthropicAdminBillingTest {
    private val from = LocalDate.of(2026, 11, 1)
    private val until = LocalDate.of(2026, 11, 3)
    private val now = Instant.parse("2026-11-08T09:00:00Z")
    private val redis = MemoryRedis("aib")
    private var status = 200
    private var down = false

    private val costPage1 =
        """
        {"data":[{"starting_at":"2026-11-01T00:00:00Z","ending_at":"2026-11-02T00:00:00Z","results":[
           {"amount":"123.78912","currency":"USD","cost_type":"tokens","description":"Claude Haiku 5.5 Usage - Input Tokens",
            "model":"claude-haiku-5-5","token_type":"uncached_input_tokens","workspace_id":null},
           {"amount":"0.5","currency":"USD","cost_type":"tokens","model":"claude-haiku-5-5","workspace_id":"wrkspc_other"}]}],
         "has_more":true,"next_page":"page_2"}
        """.trimIndent()
    private val costPage2 =
        """
        {"data":[{"starting_at":"2026-11-02T00:00:00Z","ending_at":"2026-11-03T00:00:00Z","results":[
           {"amount":"10","currency":"USD","cost_type":"web_search","model":null,"workspace_id":null}]}],
         "has_more":false,"next_page":null}
        """.trimIndent()
    private val usagePage =
        """
        {"data":[
          {"starting_at":"2026-11-01T00:00:00Z","ending_at":"2026-11-02T00:00:00Z","results":[
            {"model":"claude-haiku-5-5","uncached_input_tokens":1500,"cache_read_input_tokens":200,
             "cache_creation":{"ephemeral_1h_input_tokens":7,"ephemeral_5m_input_tokens":30},"output_tokens":500,
             "server_tool_use":{"web_search_requests":2},"workspace_id":null,"service_tier":"standard"}]},
          {"starting_at":"2026-11-02T00:00:00Z","ending_at":"2026-11-03T00:00:00Z","results":[]}],
         "has_more":false,"next_page":null}
        """.trimIndent()

    private val clients =
        FakeHttpClients { request ->
            if (down) throw IOException("down")
            when {
                status != 200 -> {
                    json("""{"type":"error"}""", status)
                }

                request.url.encodedPath == "/v1/organizations/cost_report" -> {
                    json(if (request.url.parameters["page"] == "page_2") costPage2 else costPage1)
                }

                else -> {
                    json(usagePage)
                }
            }
        }

    private fun billing(workspace: String? = null) =
        AnthropicAdminBilling(
            http = clients.named("anthropic-admin") { baseUrl = AnthropicAdminBilling.BASE_URL },
            key = "sk-ant-admin01-secret",
            cache = redis,
            workspaceId = workspace,
            clock = Clock.fixed(now, ZoneOffset.UTC),
        )

    @Test
    fun `reads both reports page by page, cents exactly as dollars, with the key in its header only`() =
        runBlocking<Unit> {
            val bill = assertIs<Resource.Success<AiBill>>(billing().billed(from, until)).data

            // 123.78912 + 0.5 + 10 cents.
            assertEquals(0, BigDecimal("1.3428912").compareTo(bill.costUsd))
            assertEquals(listOf(from, from.plusDays(1)), bill.days.map { it.day })
            assertEquals(0, BigDecimal("1.2428912").compareTo(bill.days[0].costUsd))
            val haiku = bill.models.first { it.model == "claude-haiku-5-5" }
            assertEquals(1500L, haiku.inputTokens)
            assertEquals(200L, haiku.cacheReadTokens)
            assertEquals(37L, haiku.cacheWriteTokens)
            assertEquals(500L, haiku.outputTokens)
            assertEquals(2L, bill.webSearches)
            assertEquals(0, BigDecimal("1.2428912").compareTo(haiku.costUsd))
            assertEquals(0, BigDecimal("0.1").compareTo(bill.models.first { it.model == "other" }.costUsd))
            assertEquals("organisation", bill.scope)
            assertEquals(now, bill.fetchedAt)

            assertEquals(3, clients.requests.size) // two cost pages, one usage page
            for (request in clients.requests) {
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("api.anthropic.com", request.url.host)
                assertEquals("sk-ant-admin01-secret", request.headers["x-api-key"])
                assertEquals("2023-06-01", request.headers["anthropic-version"])
                assertTrue("sk-ant" !in request.url.toString(), "the key goes in the header only")
                assertEquals("2026-11-01T00:00:00Z", request.url.parameters["starting_at"])
                assertEquals("2026-11-03T00:00:00Z", request.url.parameters["ending_at"])
                assertEquals("1d", request.url.parameters["bucket_width"])
                assertEquals("31", request.url.parameters["limit"])
            }
            val cost = clients.requests.filter { it.url.encodedPath == "/v1/organizations/cost_report" }
            assertEquals(listOf("description"), cost[0].url.parameters.getAll("group_by[]"))
            assertEquals(null, cost[0].url.parameters["page"])
            assertEquals("page_2", cost[1].url.parameters["page"])
            val usage = clients.requests.single { it.url.encodedPath == "/v1/organizations/usage_report/messages" }
            assertEquals(listOf("model"), usage.url.parameters.getAll("group_by[]"))
        }

    @Test
    fun `an answer is kept five minutes, so a second look doesn't ask Anthropic again`() =
        runBlocking<Unit> {
            val first = billing().billed(from, until)
            val second = billing().billed(from, until)

            assertEquals(3, clients.requests.size)
            assertEquals(
                (first as Resource.Success).data.costUsd.stripTrailingZeros(),
                (second as Resource.Success).data.costUsd.stripTrailingZeros(),
            )
            assertEquals(Duration.ofMinutes(5), redis.ttls.getValue("bill:org:$from:$until"))
        }

    @Test
    fun `one workspace counts only its own lines`() =
        runBlocking<Unit> {
            val bill = (billing("wrkspc_other").billed(from, until) as Resource.Success).data

            assertEquals(0, BigDecimal("0.005").compareTo(bill.costUsd))
            assertEquals("workspace wrkspc_other", bill.scope)
            val usage = clients.requests.first { it.url.encodedPath == "/v1/organizations/usage_report/messages" }
            assertEquals(listOf("wrkspc_other"), usage.url.parameters.getAll("workspace_ids[]"))
            val cost = clients.requests.first { it.url.encodedPath == "/v1/organizations/cost_report" }
            assertEquals(listOf("description", "workspace_id"), cost.url.parameters.getAll("group_by[]"))
        }

    @Test
    fun `a refused key, a failing server or an unreadable answer is unavailable, and isn't asked again for a minute`() =
        runBlocking<Unit> {
            status = 401
            val refused = billing().billed(from, until)
            assertIs<IntegrationError.Unavailable>((refused as Resource.Error).error)
            assertEquals(Duration.ofSeconds(60), redis.ttls.getValue("failed:org:$from:$until"))

            status = 200
            val asked = clients.requests.size
            assertIs<Resource.Error>(billing().billed(from, until))
            assertEquals(asked, clients.requests.size, "a failure keeps Anthropic from being asked again at once")

            redis.values.clear()
            down = true
            assertIs<Resource.Error>(billing().billed(from, until))
            redis.values.clear()
            down = false
            status = 500
            assertIs<Resource.Error>(billing().billed(from, until))
        }

    @Test
    fun `an empty range is refused without asking`() =
        runBlocking<Unit> {
            assertIs<Resource.Error>(billing().billed(from, from))
            assertEquals(0, clients.requests.size)
        }
}
