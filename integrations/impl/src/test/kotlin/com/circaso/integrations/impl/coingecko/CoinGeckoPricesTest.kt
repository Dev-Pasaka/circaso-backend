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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CoinGecko's simple price over MockEngine, with the table kept in an in-memory Redis (decision 2026-11-06). */
class CoinGeckoPricesTest {
    private var now: Instant = Instant.parse("2026-11-06T08:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }

    private var down = false
    private var status = 200
    private var body =
        """{"ethereum":{"usd":2232.83},"solana":{"usd":90.04},""" +
            """"polygon-ecosystem-token":{"usd":1.2E-1}}"""

    private val clients =
        FakeHttpClients { _ ->
            if (down) throw IOException("down")
            json(body, status)
        }
    private val redis = MemoryRedis(prefix = "px")
    private val prices =
        CoinGeckoPrices(
            clients.named("coingecko") { baseUrl = CoinGeckoPrices.BASE_URL },
            redis,
            key = "demo-key",
            clock = clock,
        )

    @Test
    fun `one call asks for every coin we hold, with the demo key, and answers under the symbols asked`() =
        runBlocking<Unit> {
            val answer = prices.usdPrices(setOf("ETH", "eth-sepolia", "POL", "MATIC", "USDC", "DOGE"))

            assertEquals(BigDecimal("2232.83"), answer["ETH"])
            assertEquals(BigDecimal("2232.83"), answer["eth-sepolia"], "a testnet coin at its mainnet price")
            assertEquals(BigDecimal("0.12"), answer["POL"]?.stripTrailingZeros(), "exact, also from an exponent")
            assertEquals(answer["POL"], answer["MATIC"], "MATIC is POL")
            assertEquals(BigDecimal.ONE, answer["USDC"])
            assertNull(answer["DOGE"], "a coin we don't map has no price")
            val request = clients.requests.single()
            assertEquals("/api/v3/simple/price", request.url.encodedPath)
            assertEquals("demo-key", request.headers["x-cg-demo-api-key"])
            assertEquals("usd", request.url.parameters["vs_currencies"])
            assertTrue(
                "ethereum" in
                    request.url.parameters["ids"]
                        .orEmpty()
                        .split(','),
            )
            assertTrue(
                "coinbase-wrapped-btc" in
                    request.url.parameters["ids"]
                        .orEmpty()
                        .split(','),
            )
        }

    @Test
    fun `dollar stablecoins never call the feed`() =
        runBlocking<Unit> {
            assertEquals(
                mapOf("USDC" to BigDecimal.ONE, "USDT" to BigDecimal.ONE),
                prices.usdPrices(setOf("USDC", "USDT")),
            )
            assertTrue(clients.requests.isEmpty())
        }

    @Test
    fun `the table is kept a minute, then asked again`() =
        runBlocking<Unit> {
            prices.usdPrice("ETH")
            now = now.plusSeconds(59)
            prices.usdPrice("SOL")
            assertEquals(1, clients.requests.size)

            now = now.plusSeconds(2)
            body = """{"ethereum":{"usd":2300}}"""
            assertEquals(BigDecimal("2300"), prices.usdPrice("ETH"))
            assertEquals(2, clients.requests.size)
            assertEquals(CoinGeckoPrices.MAX_AGE, redis.ttls.getValue("usd"))
        }

    @Test
    fun `when the feed is down the last table is used for a while, then there is no price, and nothing throws`() =
        runBlocking<Unit> {
            prices.usdPrice("ETH")
            down = true

            now = now.plus(Duration.ofMinutes(5))
            assertEquals(BigDecimal("2232.83"), prices.usdPrice("ETH"))
            assertEquals("1", redis.values["usd:failed"], "not asked again for a while")
            val calls = clients.requests.size
            prices.usdPrice("ETH")
            assertEquals(calls, clients.requests.size)

            now = now.plus(Duration.ofMinutes(11))
            redis.values.remove("usd:failed")
            assertNull(prices.usdPrice("ETH"), "too old to show")
        }

    @Test
    fun `an error status or an unreadable body is no price`() =
        runBlocking<Unit> {
            status = 429
            assertNull(prices.usdPrice("ETH"))

            redis.values.clear()
            status = 200
            body = "<html>"
            assertNull(prices.usdPrice("ETH"))

            redis.values.clear()
            body = """{"ethereum":{"usd":"lots"}}"""
            assertNull(prices.usdPrice("ETH"))
        }
}
