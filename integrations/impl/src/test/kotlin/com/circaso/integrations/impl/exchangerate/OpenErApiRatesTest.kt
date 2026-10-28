package com.circaso.integrations.impl.exchangerate

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

/** ExchangeRate-API's open endpoint over MockEngine, with the table kept in an in-memory Redis. */
class OpenErApiRatesTest {
    private var now: Instant = Instant.parse("2026-10-28T08:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: ZoneId?) = this

            override fun instant(): Instant = now
        }

    /** The source published at 00:00 UTC on the 28th and announces its next table for 00:00 on the 29th. */
    private val published = Instant.parse("2026-10-28T00:00:00Z")
    private var down = false
    private var body = table(published, kes = "129.25")

    private val clients =
        FakeHttpClients { _ ->
            if (down) throw IOException("down")
            json(body)
        }
    private val redis = MemoryRedis()
    private val rates =
        OpenErApiRates(clients.named("exchangerate") { baseUrl = OpenErApiRates.BASE_URL }, redis, clock)

    private fun table(at: Instant, kes: String): String =
        """{"result":"success","provider":"https://www.exchangerate-api.com",""" +
            """"time_last_update_unix":${at.epochSecond},"time_last_update_utc":"x",""" +
            """"time_next_update_unix":${at.plus(Duration.ofDays(1)).epochSecond},""" +
            """"base_code":"USD","rates":{"USD":1,"KES":$kes,"UGX":3700.5}}"""

    @Test
    fun `the rate comes from GET v6 latest USD with the time the source published it`() =
        runBlocking<Unit> {
            val rate = assertNotNull(rates.usdRate("KES"))

            assertEquals(BigDecimal("129.25"), rate.rate)
            assertEquals(published, rate.asOf)
            assertEquals("KES", rate.currency)
            val request = clients.requests.single()
            assertEquals("https://open.er-api.com/v6/latest/USD", request.url.toString())
            assertEquals(listOf("exchangerate"), clients.names)
        }

    @Test
    fun `the table is kept until the source's next update, so reads don't call it again`() =
        runBlocking<Unit> {
            rates.usdRate("KES")
            now = now.plus(Duration.ofHours(10))
            rates.usdRate("UGX")
            assertEquals(1, clients.requests.size)
            assertTrue(redis.ttls.getValue("usd") >= Duration.ofHours(30), "kept 48 hours from publication")

            now = Instant.parse("2026-10-29T00:05:00Z")
            body = table(Instant.parse("2026-10-29T00:00:00Z"), kes = "130")
            assertEquals(BigDecimal("130"), rates.usdRate("KES")?.rate)
            assertEquals(2, clients.requests.size)
        }

    @Test
    fun `when the source is down the last table is used while under 48 hours old, then there is no rate`() =
        runBlocking<Unit> {
            rates.usdRate("KES")
            down = true

            now = published.plus(Duration.ofHours(30))
            assertEquals(BigDecimal("129.25"), rates.usdRate("KES")?.rate)

            now = published.plus(Duration.ofHours(49))
            redis.values.remove("usd:failed")
            assertNull(rates.usdRate("KES"))
        }

    @Test
    fun `after a failed fetch the source isn't asked again for a while`() =
        runBlocking<Unit> {
            down = true
            assertNull(rates.usdRate("KES"))
            assertNull(rates.usdRate("KES"))
            assertEquals(1, clients.requests.size)
            assertEquals(OpenErApiRates.RETRY_AFTER, redis.ttls["usd:failed"])

            down = false
            redis.values.remove("usd:failed")
            assertEquals(BigDecimal("129.25"), rates.usdRate("KES")?.rate)
        }

    @Test
    fun `an unknown currency, an error answer and an unreadable body are no rate, never an exception`() =
        runBlocking<Unit> {
            assertNull(rates.usdRate("XYZ"))

            redis.values.clear()
            body = """{"result":"error","error-type":"unknown"}"""
            assertNull(rates.usdRate("KES"))

            redis.values.clear()
            body = "<html>"
            assertNull(rates.usdRate("KES"))
        }
}
