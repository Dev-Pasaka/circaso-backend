package com.circaso.integrations.impl.coingecko

import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.MemoryRedis
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asha's coin data from CoinGecko over MockEngine (decision 2026-11-06). */
class CoinGeckoMarketsTest {
    private var now: Instant = Instant.parse("2026-11-06T08:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }

    private var down = false

    private val search =
        """{"coins":[""" +
            """{"id":"dogwifcoin","name":"dogwifhat","symbol":"WIF","market_cap_rank":80},""" +
            """{"id":"fake-doge","name":"Doge Copy","symbol":"DOGE","market_cap_rank":null},""" +
            """{"id":"dogecoin","name":"Dogecoin","symbol":"DOGE","market_cap_rank":9}]}"""

    private fun coin(id: String, price: String) =
        """{"id":"$id","symbol":"doge","name":"Dogecoin","market_cap_rank":9,"categories":["Meme",null],""" +
            """"links":{"homepage":["https://dogecoin.com/","",null]},""" +
            """"description":{"en":"Dogecoin is a <a href=\"https://x\">cryptocurrency</a>.""" +
            """\r\n\r\nIt began as a joke."},""" +
            """"market_data":{"current_price":{"usd":$price,"kes":20},"price_change_percentage_24h":-2.5,""" +
            """"price_change_percentage_7d":3.1,"market_cap":{"usd":23000000000},"total_volume":{"usd":900000000},""" +
            """"high_24h":{"usd":0.16},"low_24h":{"usd":0.15},"ath":{"usd":0.731578},""" +
            """"ath_date":{"usd":"2021-05-08T05:08:23.458Z"},"ath_change_percentage":{"usd":-78.6},""" +
            """"circulating_supply":146000000000.0,"max_supply":null,"last_updated":"2026-11-06T07:59:00.000Z"}}"""

    private var price = "0.1567"

    private val clients =
        FakeHttpClients { request ->
            if (down) throw IOException("down")
            when (request.url.encodedPath) {
                "/api/v3/search" -> {
                    json(if (request.url.parameters["query"] == "zzzz") """{"coins":[]}""" else search)
                }

                "/api/v3/coins/dogecoin" -> {
                    json(coin("dogecoin", price))
                }

                "/api/v3/coins/ethereum" -> {
                    json(coin("ethereum", "2232.83"))
                }

                "/api/v3/search/trending" -> {
                    json(
                        """{"coins":[{"item":{"id":"bonk","coin_id":1,"name":"Bonk","symbol":"BONK",""" +
                            """"market_cap_rank":60,"data":{"price":0.00002,""" +
                            """"price_change_percentage_24h":{"usd":12.3}}}}]}""",
                    )
                }

                else -> {
                    json("{}", 404)
                }
            }
        }
    private val redis = MemoryRedis(prefix = "px")
    private val markets =
        CoinGeckoMarkets(
            clients.named("coingecko") { baseUrl = CoinGeckoPrices.BASE_URL },
            redis,
            key = "demo-key",
            clock = clock,
        )

    @Test
    fun `a symbol is searched, the best ranked coin with it wins over a copycat, and its market data is read`() =
        runBlocking<Unit> {
            val doge = assertNotNull(markets.coin("doge"))

            assertEquals("dogecoin", doge.id)
            assertEquals("DOGE", doge.symbol)
            assertEquals(BigDecimal("0.1567"), doge.usdPrice)
            assertEquals(BigDecimal("-2.5"), doge.change24hPercent)
            assertEquals(BigDecimal("23000000000"), doge.marketCapUsd)
            assertEquals(BigDecimal("0.731578"), doge.allTimeHighUsd)
            assertEquals(Instant.parse("2021-05-08T05:08:23.458Z"), doge.allTimeHighAt)
            assertNull(doge.maxSupply)
            assertEquals(listOf("Meme"), doge.categories)
            assertEquals("https://dogecoin.com/", doge.homepage)
            assertEquals("Dogecoin is a cryptocurrency. It began as a joke.", doge.description, "plain text")
            val (searched, read) = clients.requests
            assertEquals("doge", searched.url.parameters["query"])
            assertEquals("/api/v3/coins/dogecoin", read.url.encodedPath)
            assertEquals("false", read.url.parameters["tickers"])
            assertTrue(clients.requests.all { it.headers["x-cg-demo-api-key"] == "demo-key" })
        }

    @Test
    fun `our own coins need no search`() =
        runBlocking<Unit> {
            assertEquals(BigDecimal("2232.83"), markets.coin("ETH")?.usdPrice)
            assertEquals(listOf("/api/v3/coins/ethereum"), clients.requests.map { it.url.encodedPath })
        }

    @Test
    fun `the search is kept a day and the coin a minute`() =
        runBlocking<Unit> {
            markets.coin("Doge")
            markets.coin("doge")
            assertEquals(2, clients.requests.size)

            now = now.plusSeconds(61)
            price = "0.2"
            assertEquals(BigDecimal("0.2"), markets.coin("doge")?.usdPrice)
            assertEquals(
                listOf(
                    "/api/v3/search",
                    "/api/v3/coins/dogecoin",
                    "/api/v3/coins/dogecoin",
                ),
                clients.requests.map {
                    it.url.encodedPath
                },
            )
        }

    @Test
    fun `when the feed is down the last data is used for a while, then nothing, and nothing throws`() =
        runBlocking<Unit> {
            markets.coin("doge")
            down = true
            now = now.plus(Duration.ofMinutes(5))
            assertEquals(BigDecimal("0.1567"), markets.coin("doge")?.usdPrice)

            now = now.plus(Duration.ofMinutes(11))
            redis.values.keys
                .filter { it.endsWith(":failed") }
                .forEach { redis.values.remove(it) }
            assertNull(markets.coin("doge"))
            assertTrue(markets.trending().isEmpty())
        }

    @Test
    fun `a name that matches nothing is no coin, and isn't searched again`() =
        runBlocking<Unit> {
            assertNull(markets.coin("zzzz"))
            assertNull(markets.coin("zzzz"))
            assertEquals(1, clients.requests.size)
            assertNull(markets.coin("   "))
        }

    @Test
    fun `trending coins come with their price and change`() =
        runBlocking<Unit> {
            val bonk = markets.trending().single()
            assertEquals("BONK", bonk.symbol)
            assertEquals(BigDecimal("0.00002"), bonk.usdPrice)
            assertEquals(BigDecimal("12.3"), bonk.change24hPercent)
            markets.trending()
            assertEquals(1, clients.requests.size, "kept five minutes")
        }
}
