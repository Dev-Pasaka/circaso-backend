package com.circaso.assistant

import com.circaso.assistant.data.tools.OwnTools
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.SearchAllowance
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.assistant.domain.support.today
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The tools that need no agent token: Asha's own allowance and the public web (decision 2026-10-31). */
class OwnToolsTest {
    private class RecordingSearch : WebSearch {
        val asked = mutableListOf<Pair<String, Int>>()
        var results: List<WebResult> = emptyList()
        var isDown = false

        override suspend fun search(query: String, limit: Int): Resource<List<WebResult>> {
            asked += query to limit
            return if (isDown) Resource.error(IntegrationError.Unavailable("Web search")) else Resource.success(results)
        }
    }

    private val search = RecordingSearch()
    private val quota = MemoryQuota()
    private val plans = Plans(Plan.FREE)
    private val clock = Clock.systemUTC()
    private val config = AssistantConfig()
    private val tools =
        OwnTools(search, UsageReader(plans, quota, config, clock), SearchAllowance(plans, quota, config, clock))
    private val me = UserId.new()
    private val context = ToolContext(me, agentToken = null, currency = "KES")

    private fun call(name: String, arguments: String) = runBlocking { tools.call(name, arguments, context) }

    @Test
    fun `they are offered even without an agent token`() {
        assertEquals(listOf("getAshaUsage", "searchWeb"), tools.specs(hasAgentToken = false).map { it.name })
    }

    @Test
    fun `the search sends only the query, asks for 5, and cuts long results`() {
        search.results =
            List(8) { WebResult("Title $it " + "t".repeat(300), "https://example.org/$it", "s".repeat(2_000)) }

        val text = (call("searchWeb", """{"query":"how does USDC work"}""") as Resource.Success).data

        assertEquals(listOf("how does USDC work" to 5), search.asked)
        assertEquals(5, Regex("\"url\"").findAll(text).count())
        assertTrue(text.length <= 12_000)
        assertTrue("s".repeat(401) !in text, "snippets are capped")
        assertEquals(
            "Searching the web for “how does USDC work”…",
            tools.label("searchWeb", """{"query":"how does USDC work"}"""),
        )
    }

    @Test
    fun `an empty query asks nothing, and a search that fails is an error`() {
        assertTrue((call("searchWeb", "{}") as Resource.Success).data.contains("message"))
        assertTrue(search.asked.isEmpty())

        search.isDown = true
        assertIs<Resource.Error>(call("searchWeb", """{"query":"fees"}"""))
    }

    @Test
    fun `the allowance is the asking user's own`() =
        runBlocking<Unit> {
            quota.countTextTurn(me, clock.today(), 20)
            quota.countTextTurn(UserId.new(), clock.today(), 20)

            val text = (tools.call("getAshaUsage", "{}", context) as Resource.Success).data

            assertTrue(""""plan":"FREE"""" in text, text)
            assertTrue(""""questionsUsed":1""" in text, text)
            assertTrue(""""questionsLeft":19""" in text, text)
        }

    @Test
    fun `a result's published date reaches the model as a day, and a search is counted in the day's usage`() =
        runBlocking<Unit> {
            search.results =
                listOf(
                    WebResult("News", "https://example.org/n", "Rates moved.", Instant.parse("2026-11-04T09:30:00Z")),
                    WebResult("Old", "https://example.org/o", "A guide."),
                )

            val text = (call("searchWeb", """{"query":"kes usd news"}""") as Resource.Success).data

            assertTrue(""""published":"2026-11-04"""" in text, text)
            assertEquals(1, Regex("\"published\":\"").findAll(text).count(), "an unknown date is null, not made up")
            assertEquals(1L, quota.webSearches(me, clock.today()))
            val usage = (tools.call("getAshaUsage", "{}", context) as Resource.Success).data
            assertTrue(""""searchesUsed":1""" in usage, usage)
            assertTrue(""""searchesLeft":9""" in usage, usage)
        }

    @Test
    fun `at most two searches run in one turn, and the third is answered in words without a call`() {
        val third = ToolContext(me, null, "KES", calledBefore = listOf("searchWeb", "getWallet", "searchWeb"))

        val text = (runBlocking { tools.call("searchWeb", """{"query":"fees"}""", third) } as Resource.Success).data

        assertTrue("No more web searches" in text, text)
        assertTrue(search.asked.isEmpty())
        assertEquals(0L, runBlocking { quota.webSearches(me, clock.today()) }, "a refused search isn't counted")
    }

    @Test
    fun `the day's searches are capped by the plan, and the refused one is given back`() =
        runBlocking<Unit> {
            repeat(10) { quota.countWebSearch(me, clock.today(), 10) }

            val text = (call("searchWeb", """{"query":"fees"}""") as Resource.Success).data

            assertTrue("used up" in text, text)
            assertTrue(search.asked.isEmpty())
            assertEquals(10L, quota.webSearches(me, clock.today()))
        }
}
