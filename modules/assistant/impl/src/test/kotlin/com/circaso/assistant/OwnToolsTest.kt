package com.circaso.assistant

import com.circaso.assistant.data.tools.OwnTools
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.assistant.domain.support.today
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Clock
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
    private val tools = OwnTools(search, UsageReader(plans, quota, AssistantConfig(), clock))
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
}
