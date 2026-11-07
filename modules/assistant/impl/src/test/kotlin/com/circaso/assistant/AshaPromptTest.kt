package com.circaso.assistant

import com.circaso.assistant.data.tools.AllAgentTools
import com.circaso.assistant.data.tools.CryptoTools
import com.circaso.assistant.data.tools.HttpAgentTools
import com.circaso.assistant.data.tools.MoneyTools
import com.circaso.assistant.data.tools.OwnTools
import com.circaso.assistant.domain.support.AshaPrompt
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.PromptContext
import com.circaso.assistant.domain.support.SearchAllowance
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.integrations.api.WebResult
import com.circaso.integrations.api.WebSearch
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondOk
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What Asha is told (decision 2026-11-05): her character, the rules on values and web search, no mascot tag, and the
 * tools she is given. The tool list is a snapshot: a tool added or taken away changes it on purpose.
 */
class AshaPromptTest {
    private object NoSearch : WebSearch {
        override suspend fun search(query: String, limit: Int): Resource<List<WebResult>> =
            Resource.success(emptyList())
    }

    private val config = AssistantConfig()
    private val quota = MemoryQuota()
    private val plans = Plans()
    private val clock = Clock.systemUTC()
    private val tools =
        AllAgentTools(
            listOf(
                HttpAgentTools(HttpClient(MockEngine { respondOk() }), "http://localhost"),
                OwnTools(
                    NoSearch,
                    UsageReader(plans, quota, config, clock),
                    SearchAllowance(plans, quota, config, clock),
                ),
                CryptoTools(FakeMarkets(), FakeUsdRates()),
                MoneyTools(FakeUsdRates(), FakePrices(), FakeMarkets()),
            ),
        )
    private val text = AshaPrompt.of(PromptContext(isVoice = false, "2026-11-05", "KES", canReadChats = true))
    private val voice = AshaPrompt.of(PromptContext(isVoice = true, "2026-11-05", "KES", canReadChats = true))

    /** The prompt on one line, so a rule is found whatever the line breaks. */
    private fun flat(prompt: String) = prompt.replace('\n', ' ').replace(Regex(" +"), " ")

    @Test
    fun `the server tools are these, in this order`() {
        assertEquals(
            listOf(
                "getProfile",
                "searchContacts",
                "listContacts",
                "listBlocked",
                "getSecurityOverview",
                "listChats",
                "getWallet",
                "listWallets",
                "getAssetValues",
                "getFeeQuote",
                "listTransactions",
                "summarizeTransactions",
                "getTransaction",
                "listChamas",
                "getChama",
                "getMyMoments",
                "getAshaUsage",
                "searchWeb",
                "getCryptoMarket",
                "getTrendingCrypto",
                "getExchangeRate",
                "convertAmount",
                "calculate",
            ),
            tools.specs(hasAgentToken = true).map { it.name },
        )
        assertEquals(
            listOf(
                "getAshaUsage",
                "searchWeb",
                "getCryptoMarket",
                "getTrendingCrypto",
                "getExchangeRate",
                "convertAmount",
                "calculate",
            ),
            tools.specs(hasAgentToken = false).map { it.name },
        )
    }

    @Test
    fun `every tool's parameters are a JSON object schema, and no tool is about spaces`() {
        val specs = tools.specs(hasAgentToken = true) + listOf(AshaPrompt.searchChats, AshaPrompt.readChat)
        specs.forEach {
            val schema = Json.parseToJsonElement(it.parametersJsonSchema).jsonObject
            assertEquals("\"object\"", schema["type"].toString(), it.name)
            assertFalse("space" in it.name.lowercase(), it.name)
            assertTrue(tools.handles(it.name) || it.name in setOf("searchChats", "readChat"), it.name)
        }
    }

    @Test
    fun `Asha is warm and glad to help, concise, and never flippant about money`() {
        assertTrue("happy, warm assistant who is always glad to help" in flat(text))
        assertTrue("Be concise" in flat(text))
        assertTrue("never flippant, sarcastic or joking about their money" in flat(text))
        assertTrue("let the warmth be in your words and keep them brief" in flat(voice))
    }

    @Test
    fun `values come only from the wallet's own rate, and the web is searched before an uncertain fact`() {
        val flat = flat(text)
        assertTrue("always from getAssetValues or the wallet tools, never from the web" in flat)
        assertTrue("the same numbers the app uses for balances, payments and fees" in flat)
        assertTrue("A fee or a limit for a payment: ask getFeeQuote" in flat)
        assertTrue("search the web first with searchWeb" in flat)
        assertTrue("At most two searches per answer" in flat)
        assertTrue("Never search for the value of USDC, USDT or a balance" in flat)
        assertTrue("Cite the sources you used briefly" in flat)
    }

    @Test
    fun `every crypto question goes to the price feed, never to memory or the web`() {
        val flat = flat(text)
        assertTrue("call getCryptoMarket or getTrendingCrypto first" in flat)
        assertTrue("never from memory or the web, even for a coin you think you know" in flat)
        assertTrue("If the tool has no answer, say you couldn't get it right now; never guess a price" in flat)
        assertTrue("Never search for a coin's price or market data: that is getCryptoMarket" in flat)
        assertTrue("never for a coin's price or market data (getCryptoMarket)" in AshaPrompt.searchWeb.description)
        assertTrue("never answer those from memory" in AshaPrompt.getCryptoMarket.description)
    }

    @Test
    fun `every price, rate, conversion and sum comes from a tool, in text and in voice`() {
        listOf(text, voice).map(::flat).forEach { flat ->
            assertTrue("Every figure in your answer comes from a tool result in this conversation" in flat)
            assertTrue("that includes the working you show" in flat)
            assertTrue("an exchange rate between currencies (\"dollar to shilling rate\"): getExchangeRate" in flat)
            assertTrue(
                "any other arithmetic" in flat && "calculate, with the numbers exactly as the tools gave" in flat,
            )
            assertTrue("Write the whole sum as one expression" in flat)
            assertTrue("A balance with no network named means the totals over all networks" in flat)
            assertTrue("tell the user you couldn't get it right now" in flat)
            assertTrue("rather than estimating or using a rate you remember" in flat)
            assertTrue("Never search for an exchange rate: that is getExchangeRate" in flat)
        }
        val voiceRules = flat(voice)
        assertTrue("every number still comes from a tool's result" in voiceRules)
        assertTrue("Never work a number out in your head because you are speaking" in voiceRules)
        assertFalse("If you must add, double-check" in flat(text))
        assertTrue("never multiply a rate yourself" in AshaPrompt.convertAmount.description)
        assertTrue("never do maths yourself" in AshaPrompt.calculate.description)
        assertTrue("never for an exchange rate" in AshaPrompt.searchWeb.description)
    }

    @Test
    fun `the mascot tag is no longer asked for, in text or in voice`() {
        assertFalse("<mascot" in text)
        assertFalse("mascot" in voice.lowercase())
        assertEquals("asha-2026-11-07-v2", AshaPrompt.VERSION)
    }

    @Test
    fun `the sections are these, in this order`() {
        assertEquals(
            listOf(
                "# Who you are",
                "# What you can see",
                "# What you never do",
                "# Money, exactly",
                "# How you work",
                "# How you answer in a text chat",
                "# How you answer in a voice conversation",
                "# This conversation",
            ),
            text.lines().filter { it.startsWith("# ") },
        )
    }
}
