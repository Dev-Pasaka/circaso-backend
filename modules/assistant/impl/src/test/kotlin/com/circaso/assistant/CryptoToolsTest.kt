package com.circaso.assistant

import com.circaso.assistant.data.tools.CryptoTools
import com.circaso.assistant.domain.ports.ToolContext
import com.circaso.integrations.api.CoinMarket
import com.circaso.integrations.api.CryptoMarkets
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.TrendingCoin
import com.circaso.integrations.api.UsdRate
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.math.BigDecimal
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Market data for any coin, by symbol or name, from the price feed; set by the test. */
class FakeMarkets : CryptoMarkets {
    val coins = mutableMapOf<String, CoinMarket>()
    val trendingCoins = mutableListOf<TrendingCoin>()
    val asked = mutableListOf<String>()

    override suspend fun coin(query: String): CoinMarket? {
        asked += query
        return coins[query.lowercase()]
    }

    override suspend fun trending(): List<TrendingCoin> = trendingCoins
}

/** One US dollar in each currency, set by the test. */
class FakeUsdRates : ExchangeRates {
    val rates = mutableMapOf<String, BigDecimal>()

    override suspend fun usdRate(currency: String): UsdRate? =
        rates[currency]?.let { UsdRate(currency, it, Instant.parse("2026-11-06T00:00:00Z")) }
}

/** Asha's crypto tools (decision 2026-11-06): every coin question is answered from the feed, never from memory. */
class CryptoToolsTest {
    private val markets = FakeMarkets()
    private val rates = FakeUsdRates().apply { rates["KES"] = BigDecimal("129.25") }
    private val tools = CryptoTools(markets, rates)
    private val context = ToolContext(UserId.new(), agentToken = null, currency = "KES")

    private val ethereum =
        CoinMarket(
            id = "ethereum",
            symbol = "ETH",
            name = "Ethereum",
            marketCapRank = 2,
            usdPrice = BigDecimal("2232.83"),
            change24hPercent = BigDecimal("-1.23456"),
            change7dPercent = BigDecimal("4.5"),
            marketCapUsd = BigDecimal("269000000000.4"),
            volume24hUsd = BigDecimal("15000000000"),
            high24hUsd = BigDecimal("2300"),
            low24hUsd = BigDecimal("2200"),
            allTimeHighUsd = BigDecimal("4878.26"),
            allTimeHighAt = Instant.parse("2021-11-10T14:24:19Z"),
            fromAllTimeHighPercent = BigDecimal("-54.2"),
            circulatingSupply = BigDecimal("120500000.7"),
            maxSupply = null,
            categories = listOf("Smart Contract Platform"),
            homepage = "https://www.ethereum.org/",
            description = "Ethereum is a global, open-source platform for decentralized applications.",
            updatedAt = Instant.parse("2026-11-06T09:00:00Z"),
        )

    private fun call(
        name: String,
        arguments: String,
        on: ToolContext = context,
    ): JsonObject {
        val text = (runBlocking { tools.call(name, arguments, on) } as Resource.Success).data
        return Json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.text(field: String) = get(field)?.jsonPrimitive?.content

    @Test
    fun `both tools are offered without an agent token, and say what they are doing`() {
        assertEquals(
            listOf("getCryptoMarket", "getTrendingCrypto"),
            tools.specs(hasAgentToken = false).map { it.name },
        )
        assertEquals("Checking “ETH” on the market…", tools.label("getCryptoMarket", """{"coin":"ETH"}"""))
        assertEquals("Checking what's trending in crypto…", tools.label("getTrendingCrypto", "{}"))
    }

    @Test
    fun `a coin's market data comes from the feed in dollars and in the display currency at the wallet's rate`() {
        markets.coins["eth"] = ethereum

        val answer = call("getCryptoMarket", """{"coin":"ETH"}""")

        assertEquals("CoinGecko", answer.text("source"))
        assertEquals("Ethereum", answer.text("name"))
        assertEquals("2232.83", answer.text("priceUsd"))
        assertEquals("-1.23", answer.text("change24hPercent"))
        assertEquals("KES", answer.text("currency"))
        assertEquals("288593.28", answer.text("priceInCurrency"), "2232.83 x 129.25 = 288593.2775")
        assertEquals("630515.11", answer.text("allTimeHighInCurrency"), "4878.26 x 129.25 = 630515.105")
        assertEquals("2021-11-10", answer.text("allTimeHighDate"))
        assertEquals("120500001", answer.text("circulatingSupply"))
        assertEquals("129.25", answer["rate"]?.jsonObject?.text("rate"))
        assertTrue(answer.text("description").orEmpty().startsWith("Ethereum is"))
        assertEquals(listOf("ETH"), markets.asked)
    }

    @Test
    fun `a currency asked overrides the display one, and an unknown one gives dollars only`() {
        markets.coins["solana"] = ethereum.copy(symbol = "SOL", name = "Solana", usdPrice = BigDecimal("90.04"))
        rates.rates["UGX"] = BigDecimal("3700")

        assertEquals(
            "333148",
            call("getCryptoMarket", """{"coin":"solana","currency":"ugx"}""").text("priceInCurrency"),
        )

        val dollars = call("getCryptoMarket", """{"coin":"solana","currency":"XAF"}""")
        assertEquals("90.04", dollars.text("priceUsd"))
        assertEquals(JsonNull, dollars["priceInCurrency"])
    }

    @Test
    fun `a coin the feed can't answer for tells Asha to say so, never to guess`() {
        val answer = call("getCryptoMarket", """{"coin":"notacoin"}""")
        val message = answer.text("message").orEmpty()
        assertTrue("couldn't get it right now" in message)
        assertTrue("Don't guess" in message)

        assertTrue("which coin" in call("getCryptoMarket", "{}").text("message").orEmpty())
    }

    @Test
    fun `a tiny price keeps its significant digits instead of rounding to zero`() {
        markets.coins["pepe"] =
            ethereum.copy(symbol = "PEPE", name = "Pepe", usdPrice = BigDecimal("0.0000123456"), allTimeHighUsd = null)

        val answer = call("getCryptoMarket", """{"coin":"PEPE"}""")

        assertEquals("0.00001235", answer.text("priceUsd"))
        assertEquals("0.001596", answer.text("priceInCurrency"))
    }

    @Test
    fun `lookups are capped per answer`() {
        markets.coins["eth"] = ethereum
        val busy = context.copy(calledBefore = List(5) { "getCryptoMarket" })

        assertTrue(
            "No more coin lookups" in call("getCryptoMarket", """{"coin":"ETH"}""", busy).text("message").orEmpty(),
        )
        assertTrue(markets.asked.isEmpty())
    }

    @Test
    fun `trending coins come with their price in both, and nothing trending says so`() {
        assertTrue("couldn't be read" in call("getTrendingCrypto", "{}").text("message").orEmpty())

        markets.trendingCoins +=
            TrendingCoin("bonk", "BONK", "Bonk", 60, BigDecimal("0.00002"), BigDecimal("12.345"))
        val answer = call("getTrendingCrypto", "{}")

        val bonk = answer["coins"]!!.jsonArray.single().jsonObject
        assertEquals("BONK", bonk.text("symbol"))
        assertEquals("0.00002", bonk.text("priceUsd"))
        assertEquals("0.002585", bonk.text("priceInCurrency"))
        assertEquals("12.35", bonk.text("change24hPercent"))
        assertEquals("KES", answer.text("currency"))
    }
}
