package com.circaso.assistant

import com.circaso.assistant.data.blocks.JsonReplyBlocks
import com.circaso.assistant.data.tools.AllAgentTools
import com.circaso.assistant.data.tools.CryptoTools
import com.circaso.assistant.data.tools.MoneyTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.assistant.domain.support.AgentTurn
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.integrations.api.CoinMarket
import com.circaso.integrations.api.CryptoPrices
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The wallet's coin prices in US dollars, set by the test; dollar stablecoins are one, as the port promises. */
class FakePrices : CryptoPrices {
    val prices = mutableMapOf("USDC" to BigDecimal.ONE, "USDT" to BigDecimal.ONE)
    val asked = mutableListOf<String>()

    override suspend fun usdPrices(symbols: Set<String>): Map<String, BigDecimal> {
        asked += symbols
        return symbols.mapNotNull { s -> prices[s.uppercase()]?.let { s to it } }.toMap()
    }
}

/**
 * Asha's money maths (decision 2026-11-07): rates from ExchangeRate-API, coin prices from CoinGecko, every conversion
 * and sum worked out exactly by a tool, never by the model.
 */
class MoneyToolsTest {
    private val rates =
        FakeUsdRates().apply {
            rates["KES"] = BigDecimal("129.25")
            rates["EUR"] = BigDecimal("0.92")
            rates["UGX"] = BigDecimal("3700")
        }
    private val prices =
        FakePrices().apply {
            prices["ETH"] = BigDecimal("2232.83")
            prices["SOL"] = BigDecimal("90.04")
            prices["BTC"] = BigDecimal("68000")
        }
    private val markets = FakeMarkets()
    private val now = Instant.parse("2026-11-07T10:00:00Z")
    private val tools = MoneyTools(rates, prices, markets, Clock.fixed(now, ZoneOffset.UTC))
    private val context = ToolContext(UserId.new(), agentToken = null, currency = "KES")

    private fun call(
        name: String,
        arguments: String,
        on: ToolContext = context,
    ): JsonObject {
        val text = (runBlocking { tools.call(name, arguments, on) } as Resource.Success).data
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.text(field: String) = get(field)?.jsonPrimitive?.content

    private fun JsonObject.used() = get("used")!!.jsonArray.map { it.jsonObject }

    // ---- the tools themselves ----

    @Test
    fun `the three tools are offered without an agent token, and say what they are doing`() {
        assertEquals(
            listOf("getExchangeRate", "convertAmount", "calculate"),
            tools.specs(hasAgentToken = false).map { it.name },
        )
        assertEquals(
            "Checking the USD to KES rate…",
            tools.label("getExchangeRate", """{"from":"dollars","to":"shillings"}"""),
        )
        assertEquals(
            "Converting BTC to KES…",
            tools.label("convertAmount", """{"amount":"1","from":"bitcoin","to":"KES"}"""),
        )
        assertEquals("Working it out…", tools.label("calculate", """{"expression":"1+1"}"""))
    }

    // ---- getExchangeRate ----

    @Test
    fun `a rate between two currencies, with its time and source`() {
        val answer = call("getExchangeRate", """{"from":"USD","to":"KES"}""")

        assertEquals("USD", answer.text("from"))
        assertEquals("KES", answer.text("to"))
        assertEquals("129.25", answer.text("rate"))
        assertEquals("0.00773694390716", answer.text("inverse"))
        assertEquals("1 USD = 129.25 KES", answer.text("says"))
        assertEquals("2026-11-06T00:00:00Z", answer.text("asOf"))
        assertEquals("ExchangeRate-API", answer.text("source"))
    }

    @Test
    fun `a cross rate goes through the dollar`() {
        val answer = call("getExchangeRate", """{"from":"euro","to":"KES"}""")

        assertEquals("140.489130435", answer.text("rate"), "129.25 / 0.92")
        assertEquals("0.00711798839458", answer.text("inverse"))
        assertEquals(2, answer["legs"]!!.jsonArray.size)
    }

    @Test
    fun `common names are currencies`() {
        val pairs =
            mapOf(
                "shillings" to "KES",
                "Shillings" to "KES",
                "bob" to "KES",
                "ksh" to "KES",
                "dollars" to "USD",
                "US dollar" to "USD",
                "euro" to "EUR",
                "euros" to "EUR",
                "Ugandan shillings" to "UGX",
                "kes" to "KES",
            )
        pairs.forEach { (name, code) ->
            val answer = call("getExchangeRate", """{"from":"$name","to":"USD"}""")
            assertEquals(code, answer.text("from"), name)
        }
    }

    @Test
    fun `the display currency is the default other side`() {
        assertEquals("KES", call("getExchangeRate", """{"from":"USD"}""").text("to"))
    }

    @Test
    fun `a currency with no rate says so and gives no number`() {
        val answer = call("getExchangeRate", """{"from":"USD","to":"XAF"}""")

        assertTrue("No exchange rate for XAF" in answer.text("message").orEmpty())
        assertTrue("Don't estimate" in answer.text("message").orEmpty())
        assertNull(answer["rate"])
    }

    @Test
    fun `a coin is not an exchange rate`() {
        assertTrue("is a coin" in call("getExchangeRate", """{"from":"BTC","to":"KES"}""").text("message").orEmpty())
        assertTrue("Say both sides" in call("getExchangeRate", "{}").text("message").orEmpty())
    }

    // ---- convertAmount ----

    @Test
    fun `fiat to fiat, to two decimals`() {
        val answer = call("convertAmount", """{"amount":"100","from":"dollars","to":"shillings"}""")

        assertEquals("12925.00", answer.text("result"))
        assertEquals("fiat to fiat", answer.text("path"))
        val rate = answer.used().single()
        assertEquals("rate", rate.text("kind"))
        assertEquals("129.25", rate.text("value"))
        assertEquals("2026-11-06T00:00:00Z", rate.text("asOf"))
        assertEquals("ExchangeRate-API", rate.text("source"))

        assertEquals("19.34", call("convertAmount", """{"amount":"2500","from":"KES","to":"USD"}""").text("result"))
        assertEquals("7024.46", call("convertAmount", """{"amount":"50","from":"EUR","to":"KES"}""").text("result"))
        assertEquals(2, call("convertAmount", """{"amount":"50","from":"EUR","to":"KES"}""").used().size)
    }

    @Test
    fun `the result is exact, with thousands commas read`() {
        val answer = call("convertAmount", """{"amount":"1,234.56","from":"USD","to":"KES"}""")
        assertEquals("159566.88", answer.text("result"), "1234.56 x 129.25 exactly")
        assertEquals("1234.56", answer.text("amount"))
    }

    @Test
    fun `coin to fiat at the coin's dollar price and the dollar rate`() {
        val answer = call("convertAmount", """{"amount":"0.05","from":"ETH","to":"KES"}""")

        assertEquals("14429.66", answer.text("result"), "0.05 x 2232.83 x 129.25 = 14429.663875")
        assertEquals("coin to fiat", answer.text("path"))
        val price = answer.used().first { it.text("kind") == "price" }
        assertEquals("2232.83", price.text("value"))
        assertEquals("CoinGecko", price.text("source"))
        assertEquals("2026-11-07T10:00:00Z", price.text("asOf"))
    }

    @Test
    fun `fiat to coin, to the coin's own decimals`() {
        val answer = call("convertAmount", """{"amount":"5000","from":"shillings","to":"ether"}""")

        assertEquals("0.017325420894462796", answer.text("result"), "5000 / (129.25 x 2232.83), 18 decimals")
        assertEquals("fiat to coin", answer.text("path"))
        assertEquals("87890.00", call("convertAmount", """{"amount":"0.01","from":"bitcoin"}""").text("result"))
    }

    @Test
    fun `coin to coin through the dollar, trailing zeros cut`() {
        val answer = call("convertAmount", """{"amount":"1","from":"ETH","to":"solana"}""")

        assertEquals("24.7982008", answer.text("result"), "2232.83 / 90.04 = 24.798200800 at SOL's 9 decimals")
        assertEquals("coin to coin", answer.text("path"))
        assertEquals(2, answer.used().size)
    }

    @Test
    fun `a stablecoin is one dollar, and says so`() {
        val answer = call("convertAmount", """{"amount":"20","from":"USDC","to":"KES"}""")

        assertEquals("2585.00", answer.text("result"))
        val price = answer.used().first { it.text("kind") == "price" }
        assertEquals("1", price.text("value"))
        assertTrue("stablecoin" in price.text("source").orEmpty())
    }

    @Test
    fun `a coin the wallet doesn't price is found on the feed by name`() {
        markets.coins["dogecoin"] = market("dogecoin", "DOGE", "Dogecoin", BigDecimal("0.2"))

        val answer = call("convertAmount", """{"amount":"1000","from":"dogecoin","to":"USD"}""")

        assertEquals("200.00", answer.text("result"))
        assertEquals("DOGE", answer.text("from"))
        assertEquals("Dogecoin", answer.text("fromName"))
        assertEquals("2026-11-06T09:00:00Z", answer.used().single().text("asOf"))
    }

    @Test
    fun `a missing price or rate names the leg and gives no number`() {
        val coin = call("convertAmount", """{"amount":"5","from":"notacoin","to":"KES"}""")
        assertTrue("no price for NOTACOIN" in coin.text("message").orEmpty(), coin.toString())
        assertTrue("Don't estimate" in coin.text("message").orEmpty())
        assertNull(coin["result"])

        val fiat = call("convertAmount", """{"amount":"5","from":"ETH","to":"XAF"}""")
        assertTrue("no exchange rate for XAF" in fiat.text("message").orEmpty())
        assertNull(fiat["result"])

        prices.prices.remove("BTC")
        val both = call("convertAmount", """{"amount":"1","from":"BTC","to":"XAF"}""").text("message").orEmpty()
        assertTrue("no price for BTC" in both && "no exchange rate for XAF" in both, both)
    }

    @Test
    fun `an amount that isn't a number, or is below zero, is refused`() {
        assertTrue("as a number" in call("convertAmount", """{"amount":"lots","from":"USD"}""").text("message")!!)
        assertTrue("below zero" in call("convertAmount", """{"amount":"-5","from":"USD"}""").text("message")!!)
        assertTrue("as a number" in call("convertAmount", """{"from":"USD"}""").text("message")!!)
    }

    @Test
    fun `the same thing on both sides is the amount itself`() {
        val answer = call("convertAmount", """{"amount":"5","from":"KES","to":"shillings"}""")
        assertEquals("5.00", answer.text("result"))
        assertTrue(answer.used().isEmpty())
    }

    @Test
    fun `calls are capped per answer`() {
        val busy = context.copy(calledBefore = List(8) { "convertAmount" })
        assertTrue(
            "No more" in call("convertAmount", """{"amount":"1","from":"USD"}""", busy).text("message").orEmpty(),
        )
    }

    // ---- calculate ----

    @Test
    fun `calculate gives the exact result and a rounded one`() {
        val split = call("calculate", """{"expression":"(1200 + 850.50) / 3"}""")
        assertEquals("683.5", split.text("result"))
        assertEquals("683.50", split.text("display"))
        assertEquals("true", split.text("isExact"))

        val third = call("calculate", """{"expression":"10 / 3"}""")
        assertEquals("3.33", third.text("display"))
        assertEquals("false", third.text("isExact"))

        assertEquals("360", call("calculate", """{"expression":"15% of 2,400"}""").text("result"))
    }

    @Test
    fun `calculate refuses what it can't work out and tells her not to do it herself`() {
        val zero = call("calculate", """{"expression":"5 / 0"}""").text("message").orEmpty()
        assertTrue("divides by zero" in zero)
        assertTrue("Don't work it out yourself" in zero)
        assertTrue("Only numbers" in call("calculate", """{"expression":"2 ^ 8"}""").text("message").orEmpty())
    }

    // ---- both modes get them ----

    @Test
    fun `chat and voice turns both offer the new tools to the model`() {
        val all =
            AllAgentTools(listOf(CryptoTools(markets, rates), MoneyTools(rates, prices, markets)))
        ModelPurpose.entries.forEach { purpose ->
            val model = ScriptedModel().say("Done.")
            val agent = AshaAgent(model, all, AssistantConfig(), JsonReplyBlocks(), MemoryScenes())
            val turn =
                AgentTurn(
                    turnId = UUID.randomUUID(),
                    userId = UserId.new(),
                    agentToken = null,
                    currency = "KES",
                    deviceTools = false,
                    purpose = purpose,
                    today = "2026-11-07",
                )

            runBlocking { agent.run(listOf(ModelMessage.User("100 dollars in shillings?")), turn) }

            val request = model.requests.single()
            assertEquals(purpose, request.purpose)
            val names = request.tools.map { it.name }
            assertTrue(names.containsAll(listOf("getExchangeRate", "convertAmount", "calculate")), "$purpose: $names")
            val flat = request.systemPrompt.replace('\n', ' ').replace(Regex(" +"), " ")
            assertTrue("You never do money maths or recall a number yourself" in flat, "$purpose")
        }
    }

    @Test
    fun `nothing about a rate is ever a Double`() {
        // 0.1 + 0.2 is 0.3 here, not 0.30000000000000004.
        assertEquals("0.3", call("calculate", """{"expression":"0.1 + 0.2"}""").text("result"))
        assertFalse("E" in call("convertAmount", """{"amount":"0.000001","from":"BTC","to":"KES"}""").text("result")!!)
    }

    private fun market(
        id: String,
        symbol: String,
        name: String,
        usd: BigDecimal,
    ) = CoinMarket(
        id = id,
        symbol = symbol,
        name = name,
        marketCapRank = 8,
        usdPrice = usd,
        change24hPercent = null,
        change7dPercent = null,
        marketCapUsd = null,
        volume24hUsd = null,
        high24hUsd = null,
        low24hUsd = null,
        allTimeHighUsd = null,
        allTimeHighAt = null,
        fromAllTimeHighPercent = null,
        circulatingSupply = null,
        maxSupply = null,
        categories = emptyList(),
        homepage = null,
        description = null,
        updatedAt = Instant.parse("2026-11-06T09:00:00Z"),
    )
}
