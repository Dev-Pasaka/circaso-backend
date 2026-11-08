package com.circaso.ops

import com.circaso.assistant.api.AshaUsageDay
import com.circaso.assistant.api.AshaUsageUser
import com.circaso.assistant.api.ModelTokenSums
import com.circaso.integrations.api.AiBill
import com.circaso.integrations.api.AiBilledDay
import com.circaso.integrations.api.AiBilledModel
import com.circaso.ops.di.aiPricing
import com.circaso.ops.domain.support.AiPricing
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `GET /v1/ops/ai-usage` (decision 2026-11-08): the assistant's totals priced exactly with the configured table, the
 * range rules, the costliest users by name, and Anthropic's own bill beside the estimate when it is on.
 */
class AiUsageTest {
    private val harness = OpsHarness()
    private val ana = harness.accounts.add()
    private val gone = UserId(Uuid7.generate())
    private val day1 = LocalDate.of(2026, 11, 2)
    private val day2 = LocalDate.of(2026, 11, 3)

    init {
        harness.profiles.name(ana, "Ana Wanjiru")
        harness.usage.days =
            listOf(
                AshaUsageDay(day1, 40, 600, 1_000, 3, listOf(haiku(1_000_000, 2_000_000, 400_000, 300_000))),
                AshaUsageDay(
                    day2,
                    2,
                    0,
                    500,
                    1,
                    listOf(haiku(7, 3, 5, 11), ModelTokenSums("mystery-model", 100, 0, 0, 50)),
                ),
            )
        harness.usage.active = 4
        harness.usage.top =
            listOf(
                AshaUsageUser(ana, 30, 600, 1_000, listOf(haiku(1_000_000, 0, 0, 0))),
                AshaUsageUser(gone, 1, 0, 0, listOf(haiku(10, 0, 0, 0))),
            )
    }

    private fun haiku(
        input: Long,
        cacheRead: Long,
        cacheWrite: Long,
        output: Long,
    ) = ModelTokenSums("claude-haiku-5-5", input, cacheRead, cacheWrite, output)

    private suspend fun ApplicationTestBuilder.report(query: String, role: String = "FINANCE"): HttpResponse =
        client.get("/v1/ops/ai-usage$query") { header(OpsHarness.ROLE_HEADER, role) }

    private suspend fun HttpResponse.data(): JsonObject =
        Json.parseToJsonElement(bodyAsText()).jsonObject["data"]!!.jsonObject

    private fun JsonObject.long(name: String) = this[name]!!.jsonPrimitive.long

    @Test
    fun `token cost is exact to the fraction of a micro-dollar, at the default 2026-11-08 list prices`() {
        val pricing = aiPricing(MapConfig(emptyMap()))

        // 7 × $0.10/M + 3 × $0.01/M + 5 × $0.125/M + 11 × $0.50/M = $0.000006855, exactly.
        val cost = pricing.tokenCost("claude-haiku-5-5", 7, 3, 5, 11)!!
        assertEquals(0, BigDecimal("0.000006855").compareTo(cost))
        assertEquals(7L, AiPricing.micros(cost)) // 6.855 micro-dollars, rounded once, half up
        assertEquals(
            0,
            BigDecimal("0.32").compareTo(pricing.tokenCost("claude-haiku-5-5", 1_000_000, 2_000_000, 400_000, 300_000)),
        )
        assertEquals(0, BigDecimal("15").compareTo(pricing.webSearchCost(1_500)))
        assertNull(pricing.tokenCost("mystery-model", 1, 1, 1, 1), "a model without a price is never free")
        assertEquals(LocalDate.of(2026, 11, 8), pricing.asOf)
    }

    @Test
    fun `prices come from configuration, and a price that isn't one stops startup`() {
        val pricing =
            aiPricing(
                MapConfig(
                    mapOf(
                        "OPS_AI_PRICES" to "claude-haiku-5-5=0.10/0.50, claude-sonnet-5-5=3/15",
                        "OPS_AI_CACHE_WRITE_MULTIPLIER" to "2",
                        "OPS_AI_WEB_SEARCH_PER_1000_USD" to "5",
                    ),
                ),
            )
        assertEquals(listOf("claude-haiku-5-5", "claude-sonnet-5-5"), pricing.models.map { it.model })
        assertEquals(0, BigDecimal("0.000006").compareTo(pricing.tokenCost("claude-sonnet-5-5", 0, 0, 1, 0)!!))
        assertEquals(0, BigDecimal("0.005").compareTo(pricing.webSearchCost(1)))

        for (bad in listOf("claude-haiku-5-5=0.10", "=1/2", "a=1/x", "a=1/2,a=3/4", "a=-1/2")) {
            assertFailsWith<IllegalStateException>(bad) { aiPricing(MapConfig(mapOf("OPS_AI_PRICES" to bad))) }
        }
        assertFailsWith<IllegalStateException> { aiPricing(MapConfig(mapOf("OPS_AI_CACHE_READ_MULTIPLIER" to "lots"))) }
    }

    @Test
    fun `the report prices every period, model and user, and divides by the active users`() =
        testApplication {
            application { harness.install(this) }

            val response = report("?from=2026-11-02&to=2026-11-03&bucket=day&limit=5")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            val data = response.data()
            assertEquals("2026-11-02T00:00:00Z", data["from"]!!.jsonPrimitive.content)
            assertEquals("2026-11-04T00:00:00Z", data["to"]!!.jsonPrimitive.content)
            assertEquals(LocalDate.of(2026, 11, 2) to LocalDate.of(2026, 11, 4), harness.usage.asked.single())
            assertEquals(5, harness.usage.limits.single())

            val totals = data["totals"]!!.jsonObject
            // Tokens $0.32 + $0.000006855 (mystery-model unpriced); searches 1,500 × $0.01 = $15.
            assertEquals(320_007L, totals.long("tokenCostUsdMicros"))
            assertEquals(15_000_000L, totals.long("webSearchCostUsdMicros"))
            assertEquals(15_320_007L, totals.long("costUsdMicros"))
            assertEquals(3_830_002L, totals.long("costPerActiveUserUsdMicros")) // $15.320006855 / 4, half up
            assertEquals(42L, totals.long("textTurns"))
            assertEquals(600L, totals.long("voiceSeconds"))
            assertEquals(4L, totals.long("activeUsers"))
            assertEquals(150L, totals.long("unpricedTokens"))
            assertEquals(1_000_107L, totals["tokens"]!!.jsonObject.long("input"))

            val series = data["series"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("2026-11-02", "2026-11-03"), series.map { it["period"]!!.jsonPrimitive.content })
            assertEquals(10_320_000L, series[0].long("costUsdMicros"))
            assertEquals(5_000_007L, series[1].long("costUsdMicros"))

            val models = data["byModel"]!!.jsonArray.map { it.jsonObject }
            assertEquals("claude-haiku-5-5", models[0]["model"]!!.jsonPrimitive.content)
            assertEquals("false", models[1]["priced"]!!.jsonPrimitive.content)
            assertTrue(models[1]["costUsdMicros"] == null || models[1]["costUsdMicros"].toString() == "null")

            val users = data["topUsers"]!!.jsonArray.map { it.jsonObject }
            assertEquals("Ana Wanjiru", users[0]["displayName"]!!.jsonPrimitive.content)
            assertEquals(10_100_000L, users[0].long("costUsdMicros")) // $0.10 of tokens and $10 of searches
            assertTrue(users[1]["displayName"] == null || users[1]["displayName"].toString() == "null")

            val prices = data["prices"]!!.jsonObject
            assertEquals("2026-11-08", prices["asOf"]!!.jsonPrimitive.content)
            assertEquals(
                "0.125",
                prices["models"]!!
                    .jsonArray[0]
                    .jsonObject["cacheWritePerMTokUsd"]!!
                    .jsonPrimitive.content,
            )
            assertEquals("off", data["anthropic"]!!.jsonObject["status"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a week bucket starts on Monday and every period of the range is there, empty ones too`() =
        testApplication {
            application { harness.install(this) }

            // Tuesday 20 October to Sunday 8 November: the weeks of Monday 19 and 26 October and 2 November.
            val series =
                report(
                    "?from=2026-10-20&to=2026-11-08&bucket=week",
                ).data()["series"]!!.jsonArray.map { it.jsonObject }
            assertEquals(
                listOf("2026-10-19", "2026-10-26", "2026-11-02"),
                series.map { it["period"]!!.jsonPrimitive.content },
            )
            assertEquals(0L, series[0].long("costUsdMicros"))
            assertEquals(15_320_007L, series[2].long("costUsdMicros")) // both days of usage fall in the last week
        }

    @Test
    fun `the range is 1 to 366 whole days and the bucket and limit must be ones we know`() =
        testApplication {
            application { harness.install(this) }

            assertEquals(HttpStatusCode.OK, report("?from=2025-11-08&to=2026-11-08").status) // 366 days
            for (bad in listOf(
                "?from=2025-11-07&to=2026-11-08",
                "?from=2026-11-08&to=2026-11-01",
                "?from=yesterday",
                "?bucket=hour",
                "?limit=0",
            )) {
                val response = report(bad)
                assertEquals(HttpStatusCode.BadRequest, response.status, bad)
                assertTrue(response.bodyAsText().contains("request.malformed"), bad)
            }
            assertEquals(HttpStatusCode.OK, report("?limit=500").status)
            assertEquals(50, harness.usage.limits.last(), "the costliest users are capped at 50")
        }

    @Test
    fun `SUPPORT can't see what Asha costs`() =
        testApplication {
            application { harness.install(this) }

            val response = report("", role = "SUPPORT")
            assertEquals(HttpStatusCode.Forbidden, response.status)
            assertTrue(response.bodyAsText().contains("ops.role_forbidden"))
            assertEquals(HttpStatusCode.OK, report("", role = "ADMIN").status)
        }

    @Test
    fun `with Anthropic on, its bill sits beside the estimate with the difference`() =
        testApplication {
            application { harness.install(this) }
            harness.billing.bill =
                AiBill(
                    provider = "anthropic",
                    scope = "organisation",
                    costUsd = BigDecimal("0.4123456789"),
                    days = listOf(AiBilledDay(day1, BigDecimal("0.4123456789"))),
                    models =
                        listOf(
                            AiBilledModel(
                                "claude-haiku-5-5",
                                BigDecimal("0.4123456789"),
                                1_000_000,
                                2_000_000,
                                400_000,
                                300_000,
                                0,
                            ),
                        ),
                    webSearches = 0,
                    fetchedAt = Instant.parse("2026-11-08T09:00:00Z"),
                )

            val anthropic = report("?from=2026-11-02&to=2026-11-03").data()["anthropic"]!!.jsonObject
            assertEquals("ok", anthropic["status"]!!.jsonPrimitive.content)
            assertEquals(412_346L, anthropic.long("billedUsdMicros"))
            assertEquals(320_007L, anthropic.long("estimatedUsdMicros"))
            // $0.4123456789 − $0.320006855 = $0.0923388239, rounded once.
            assertEquals(92_339L, anthropic.long("differenceUsdMicros"))
            assertEquals("organisation", anthropic["scope"]!!.jsonPrimitive.content)
            assertEquals(1, harness.billing.asked)
        }

    @Test
    fun `Anthropic failing never fails the report, which says unavailable`() =
        testApplication {
            application { harness.install(this) }
            harness.billing.failing = true

            val response = report("?from=2026-11-02&to=2026-11-03")
            assertEquals(HttpStatusCode.OK, response.status)
            val anthropic = response.data()["anthropic"]!!.jsonObject
            assertEquals("unavailable", anthropic["status"]!!.jsonPrimitive.content)
            assertEquals(15_320_007L, response.data()["totals"]!!.jsonObject.long("costUsdMicros"))
        }
}
