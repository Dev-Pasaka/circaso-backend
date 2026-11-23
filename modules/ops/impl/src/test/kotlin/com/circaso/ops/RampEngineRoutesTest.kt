package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsRoutingQuery
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import com.circaso.shared.StaffRole
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The merchant engine on the ops routes (decision 2026-11-23): FINANCE and ADMIN read the settings, the routing log
 * and a merchant's signals and run the simulation; only ADMIN changes the settings or a merchant's type and limit, with
 * a reason, audited with who, why and what changed.
 */
class RampEngineRoutesTest {
    private val harness = OpsHarness()
    private val engine = harness.rampEngine
    private val scope = "network=TESTNET&direction=ON_RAMP"

    private fun HttpRequestBuilder.signedIn(role: StaffRole) {
        header(OpsHarness.ROLE_HEADER, role.name)
        header("Idempotency-Key", "key-1")
    }

    private fun HttpRequestBuilder.json(role: StaffRole, body: String) {
        signedIn(role)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun ApplicationTestBuilder.read(path: String, role: StaffRole = StaffRole.FINANCE): HttpResponse =
        client.get("/v1/ops/ramp$path") { signedIn(role) }

    private suspend fun ApplicationTestBuilder.put(
        path: String,
        body: String,
        role: StaffRole = StaffRole.ADMIN,
    ): HttpResponse = client.put("/v1/ops/ramp$path") { json(role, body) }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        role: StaffRole = StaffRole.FINANCE,
    ): HttpResponse = client.post("/v1/ops/ramp$path") { json(role, body) }

    private fun lines(action: String) = harness.audit.entries.filter { it.action == action }

    private fun document(
        mode: String = "ACTIVE",
        alpha: String = "1.0",
        weightCompletion: String = "0.5",
        small: String = "\"200000\"",
    ) = """{"mode":"$mode","weightCompletion":$weightCompletion,"weightSpeed":0.3,"weightFailure":0.2,""" +
        """"priorStrength":10.0,"completionPrior":0.9,"failurePrior":0.1,"speedPrior":0.9,"qualityFloor":0.1,""" +
        """"alpha":$alpha,"hungerMax":1.0,"hungerWindowMinutes":120,"newcomerMax":0.5,"newcomerTrades":20,""" +
        """"smallOrderMinor":$small,"floatCover":3.0,"shareThreshold":0.5,"sharePenalty":0.25,"slaSeconds":900,""" +
        """"targetSeconds":300,"statsOrders":50,"statsDays":30,"maxAttempts":3,"individualLimitMinor":"3000000",""" +
        """"businessLimitMinor":"15000000","logRetentionDays":90}"""

    @Test
    fun `finance and admin read the engine and run the simulation, support none of it, and only an admin changes it`() =
        testApplication {
            application { harness.install(this) }
            val merchant = harness.ramp.add()
            val decision = engine.routed()
            val reads =
                listOf(
                    "/engine-settings?$scope",
                    "/routing?network=TESTNET",
                    "/routing/${decision.decisionId}",
                    "/merchants/$merchant/signals?network=TESTNET",
                )

            for (path in reads) {
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.FINANCE).status, path)
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.ADMIN).status, path)
                val refused = read(path, StaffRole.SUPPORT)
                assertEquals(HttpStatusCode.Forbidden, refused.status, path)
                assertTrue("ops.role_forbidden" in refused.bodyAsText())
                assertEquals(HttpStatusCode.Unauthorized, client.get("/v1/ops/ramp$path").status, "nobody signed in")
            }
            val simulate = "/engine-settings/simulate?$scope"
            assertEquals(HttpStatusCode.OK, post(simulate, "{}", StaffRole.FINANCE).status)
            assertEquals(HttpStatusCode.Forbidden, post(simulate, "{}", StaffRole.SUPPORT).status)

            val update = """{"settings":${document()},"version":1,"reason":"Trying a sharper ranking"}"""
            val tier = """{"merchantType":"BUSINESS","reason":"A registered company"}"""
            for (role in listOf(StaffRole.FINANCE, StaffRole.SUPPORT)) {
                assertEquals(HttpStatusCode.Forbidden, put("/engine-settings?$scope", update, role).status)
                assertEquals(HttpStatusCode.Forbidden, put("/merchants/$merchant/tier", tier, role).status)
            }
            assertTrue(engine.updates.isEmpty() && engine.tiers.isEmpty() && harness.audit.entries.isEmpty())
        }

    @Test
    fun `the engine's settings are one network and direction's, in the agreed shape`() =
        testApplication {
            application { harness.install(this) }

            val body = read("/engine-settings?$scope").bodyAsText()

            assertTrue(
                """"data":{"network":"TESTNET","direction":"ON_RAMP","version":1,"settings":${document()},""" +
                    """"updatedBy":null,"reason":null,"updatedAt":"2026-11-23T09:00:00Z"}""" in body,
                body,
            )
            assertEquals(HttpStatusCode.OK, read("/engine-settings?network=MAINNET&direction=off_ramp").status)

            val noNetwork = read("/engine-settings?direction=ON_RAMP")
            assertEquals(HttpStatusCode.BadRequest, noNetwork.status)
            assertTrue("\"field\":\"network\"" in noNetwork.bodyAsText())
            val noDirection = read("/engine-settings?network=TESTNET")
            assertEquals(HttpStatusCode.BadRequest, noDirection.status)
            assertTrue("\"field\":\"direction\"" in noDirection.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, read("/engine-settings?network=TESTNET&direction=SIDEWAYS").status)
        }

    @Test
    fun `a change of the engine's settings needs a reason and is audited with who, why and what changed`() =
        testApplication {
            application { harness.install(this) }
            val wanted = document(mode = "SHADOW", alpha = "2.0")

            val noReason = put("/engine-settings?$scope", """{"settings":$wanted,"version":1,"reason":" "}""")
            assertEquals(HttpStatusCode.BadRequest, noReason.status)
            assertTrue("ops.reason_required" in noReason.bodyAsText())
            assertTrue(engine.updates.isEmpty() && harness.audit.entries.isEmpty())

            val reason = "Watching the engine before it picks"
            val changed = put("/engine-settings?$scope", """{"settings":$wanted,"version":1,"reason":"$reason"}""")

            assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            val body = changed.bodyAsText()
            assertTrue("\"version\":2" in body && "\"mode\":\"SHADOW\"" in body && "\"alpha\":2.0" in body, body)
            assertTrue("\"reason\":\"$reason\"" in body, "the reason is kept on the version: $body")
            val (row, why, by) = engine.updates.single()
            assertEquals(Triple("SHADOW", 2.0, 2L), Triple(row.settings.mode, row.settings.alpha, row.version))
            assertEquals(reason to harness.ids.getValue(StaffRole.ADMIN), why to by)
            val line = lines("ramp_engine_settings.update").single()
            assertEquals("TESTNET:ON_RAMP" to "ramp_engine_settings", line.targetId to line.targetType)
            assertEquals(reason, line.reason)
            assertEquals("v1→v2; mode ACTIVE→SHADOW; alpha 1.0→2.0", line.detail, "only what changed, by name")
            assertEquals(AuditOutcome.SUCCEEDED to "admin@circaso.com", line.outcome to line.actorEmail)

            // someone else changed it meanwhile; the ramp's own refusals come through with their codes and are audited
            val stale = put("/engine-settings?$scope", """{"settings":$wanted,"version":1,"reason":"$reason"}""")
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue("ramp.settings_changed" in stale.bodyAsText())
            val invalid = document(weightCompletion = "1.5")
            val refused = put("/engine-settings?$scope", """{"settings":$invalid,"reason":"$reason"}""")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue("ramp.settings_invalid" in refused.bodyAsText() && "weightCompletion" in refused.bodyAsText())
            val failed = lines("ramp_engine_settings.update").last()
            assertEquals(AuditOutcome.FAILED to "ramp.settings_invalid", failed.outcome to failed.errorCode)

            val notCents = document(small = "\"2,000\"")
            val malformed = put("/engine-settings?$scope", """{"settings":$notCents,"reason":"$reason"}""")
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            assertTrue("\"field\":\"settings.smallOrderMinor\"" in malformed.bodyAsText(), malformed.bodyAsText())
            assertEquals(1, engine.updates.size, "nothing else was saved")
        }

    @Test
    fun `the simulation takes a draft and the orders to replay, answers shares in percent, and is not audited`() =
        testApplication {
            application { harness.install(this) }
            val path = "/engine-settings/simulate?$scope"

            val replayed = post(path, """{"settings":${document(mode = "ROUND_ROBIN")},"lastOrders":20}""")

            assertEquals(HttpStatusCode.OK, replayed.status, replayed.bodyAsText())
            val body = replayed.bodyAsText()
            assertTrue(
                """"data":{"network":"TESTNET","direction":"ON_RAMP","mode":"ROUND_ROBIN","currentMode":"ACTIVE",""" +
                    """"orders":20,"unroutable":0,"shares":[{"merchantId":"${FakeRampEngineAdmin.MERCHANT_A}",""" +
                    """"displayName":"Crynce","sharePercent":"62.5","currentSharePercent":"50","eligibleOrders":4,""" +
                    """"exclusions":{},"quality":0.91},{"merchantId":"${FakeRampEngineAdmin.MERCHANT_B}",""" +
                    """"displayName":"Bidii","sharePercent":"37.5","currentSharePercent":"50","eligibleOrders":3,""" +
                    """"exclusions":{"FLOAT_SHORT":1},"quality":0.6}]}""" in body,
                body,
            )
            val asked = engine.simulated.single()
            assertEquals(
                Triple(Network.TESTNET, "ON_RAMP", 20),
                Triple(asked.network, asked.direction, asked.lastOrders),
            )
            assertEquals("ROUND_ROBIN", asked.draft!!.mode)
            assertNull(asked.fiatMinor)

            post(path, """{"amountMinor":"150000"}""")
            val amount = engine.simulated.last()
            assertEquals(150_000L, amount.fiatMinor)
            assertNull(amount.draft, "no draft: what is in force")
            assertNull(amount.lastOrders)

            for ((bad, field) in listOf(
                """{"lastOrders":20,"amountMinor":"150000"}""" to "amountMinor",
                """{"lastOrders":0}""" to "lastOrders",
                """{"lastOrders":501}""" to "lastOrders",
                """{"amountMinor":"0"}""" to "amountMinor",
                """{"amountMinor":"1,500"}""" to "amountMinor",
            )) {
                val refused = post(path, bad)
                assertEquals(HttpStatusCode.BadRequest, refused.status, bad)
                assertTrue("\"field\":\"$field\"" in refused.bodyAsText(), refused.bodyAsText())
            }
            assertEquals(HttpStatusCode.BadRequest, post("/engine-settings/simulate?network=TESTNET", "{}").status)
            assertEquals(2, engine.simulated.size)
            assertTrue(harness.audit.entries.isEmpty(), "nothing changed, so nothing is audited")
        }

    @Test
    fun `routing decisions list in summary, newest first by keyset, narrowed by merchant, order and time`() =
        testApplication {
            application { harness.install(this) }
            val at = FakeRampEngineAdmin.AT
            val older = engine.routed(at.minusSeconds(600))
            val newer = engine.routed(at)
            engine.routed(at, Network.MAINNET)

            val body = read("/routing?network=TESTNET&limit=1").bodyAsText()

            assertTrue(
                """"decisions":[{"decisionId":"${newer.decisionId}","createdAt":"2026-11-23T09:00:00Z",""" +
                    """"quoteId":"${FakeRampEngineAdmin.QUOTE}","orderId":"${newer.orderId}",""" +
                    """"merchantId":"${FakeRampEngineAdmin.MERCHANT_B}","direction":"ON_RAMP",""" +
                    """"fiatAmountMinor":"130000","mode":"ACTIVE","outcome":"ROUTED","settingsVersion":3,""" +
                    """"candidates":3,"eligible":2,"attempts":2}],""" +
                    """"nextCursor":"2026-11-23T09:00:00Z~${newer.decisionId}"""" in body,
                body,
            )
            val next = read("/routing?network=TESTNET&limit=1&cursor=2026-11-23T09:00:00Z~${newer.decisionId}")
            assertTrue("\"decisionId\":\"${older.decisionId}\"" in next.bodyAsText(), next.bodyAsText())
            assertTrue("\"nextCursor\":null" in read("/routing?network=TESTNET").bodyAsText())

            val merchant = MerchantId.new()
            val order = RampOrderId.new()
            val from = "2026-11-23T08:00:00Z"
            val to = "2026-11-23T10:00:00Z"
            read("/routing?network=MAINNET&merchantId=$merchant&orderId=$order&from=$from&to=$to")
            assertEquals(
                OpsRoutingQuery(Network.MAINNET, merchant, order, Instant.parse(from), Instant.parse(to)),
                engine.asked.last(),
            )

            for ((bad, field) in listOf(
                "/routing" to "network",
                "/routing?network=TESTNET&merchantId=nope" to "merchantId",
                "/routing?network=TESTNET&orderId=nope" to "orderId",
                "/routing?network=TESTNET&from=yesterday" to "from",
                "/routing?network=TESTNET&to=2026-11-23" to "to",
                "/routing?network=TESTNET&cursor=nope" to "cursor",
            )) {
                val refused = read(bad)
                assertEquals(HttpStatusCode.BadRequest, refused.status, bad)
                assertTrue("\"field\":\"$field\"" in refused.bodyAsText(), refused.bodyAsText())
            }
        }

    @Test
    fun `one routing decision says why this merchant - every candidate, the order tried, each step`() =
        testApplication {
            application { harness.install(this) }
            val decision = engine.routed()
            val a = FakeRampEngineAdmin.MERCHANT_A
            val b = FakeRampEngineAdmin.MERCHANT_B
            val c = FakeRampEngineAdmin.MERCHANT_C

            val body = read("/routing/${decision.decisionId}").bodyAsText()

            assertTrue(
                """"data":{"decisionId":"${decision.decisionId}","createdAt":"2026-11-23T09:00:00Z",""" +
                    """"network":"TESTNET","direction":"ON_RAMP","quoteId":"${FakeRampEngineAdmin.QUOTE}",""" +
                    """"orderId":"${decision.orderId}","merchantId":"$b","fiatAmountMinor":"130000",""" +
                    """"needMinor":"9800000","mode":"ACTIVE","outcome":"ROUTED","settingsVersion":3,""" in body,
                body,
            )
            val stats =
                """{"finished":6,"completed":4,"merchantFailures":1,"merchantExpiries":0,"reversals":0,""" +
                    """"counted":5,"notCounted":1,"medianHandlingSeconds":12,"p90HandlingSeconds":40}"""
            assertTrue(
                """{"merchantId":"$a","excluded":null,"approved":true,"kycCleared":true,"online":true,""" +
                    """"hasKeys":true,"paymentsIssue":false,"offersDirection":true,"limitMinor":"3000000",""" +
                    """"floatMinor":"100000000","lastAssignedAt":null,"completedTrades":4,"stats":$stats,""" +
                    """"score":{"completion":0.9,"speed":1.0,"failure":0.1,"quality":0.93,"hunger":1.0,""" +
                    """"newcomer":0.4,"sizeFit":1.0,"weight":2.604}}""" in body,
                body,
            )
            assertTrue(
                """{"merchantId":"$c","excluded":"FLOAT_SHORT","approved":true,""" in body &&
                    """"floatMinor":"1000000","lastAssignedAt":null,"completedTrades":4,"stats":null,"score":null}""" in
                    body,
                "the one left out, with the reason and the float that fell short: $body",
            )
            assertTrue(""""ranked":["$a","$b"],"engineRanked":["$a","$b"],""" in body, body)
            assertTrue(
                """"steps":[{"merchantId":"$a","orderId":"${FakeRampEngineAdmin.FIRST_ORDER}",""" +
                    """"result":"prompt_refused","at":"2026-11-23T09:00:00Z"},{"merchantId":"$b",""" +
                    """"orderId":"${decision.orderId}","result":"prompted","at":"2026-11-23T09:00:01Z"}]""" in body,
                body,
            )

            val missing = read("/routing/${UUID.randomUUID()}")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue("ramp.routing_not_found" in missing.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, read("/routing/nope").status)
        }

    @Test
    fun `a merchant's type and limit are set by an admin with a reason, and audited before and after`() =
        testApplication {
            application { harness.install(this) }
            val merchant = harness.ramp.add("APPROVED")
            val path = "/merchants/$merchant/tier"

            val noReason = put(path, """{"merchantType":"BUSINESS","reason":""}""")
            assertEquals(HttpStatusCode.BadRequest, noReason.status)
            assertTrue("ops.reason_required" in noReason.bodyAsText())
            assertTrue(engine.tiers.isEmpty())

            val reason = "A registered company, KYB on file"
            val set = put(path, """{"merchantType":"business","orderLimitMinor":"5000000","reason":"$reason"}""")

            assertEquals(HttpStatusCode.OK, set.status, set.bodyAsText())
            val body = set.bodyAsText()
            assertTrue("\"merchantType\":\"BUSINESS\",\"orderLimitMinor\":\"5000000\"" in body, body)
            assertTrue("\"offersOnRamp\":true,\"offersOffRamp\":false" in body, body)
            assertEquals(Triple(merchant, "BUSINESS", 5_000_000L), engine.tiers.single())
            val line = lines("ramp_merchant.tier").single()
            assertEquals(merchant.toString() to "ramp_merchant", line.targetId to line.targetType)
            assertEquals(reason to "type BUSINESS, limit 5000000", line.reason to line.detail)
            assertEquals(AuditOutcome.SUCCEEDED, line.outcome)

            val cleared = put(path, """{"merchantType":"INDIVIDUAL","orderLimitMinor":null,"reason":"$reason"}""")
            assertTrue("\"merchantType\":\"INDIVIDUAL\",\"orderLimitMinor\":null" in cleared.bodyAsText())
            assertEquals("type INDIVIDUAL, limit default", lines("ramp_merchant.tier").last().detail)
            assertTrue("\"merchantType\":\"INDIVIDUAL\"" in read("/merchants").bodyAsText(), "the list shows it")

            val unknown =
                put("/merchants/${MerchantId.new()}/tier", """{"merchantType":"BUSINESS","reason":"$reason"}""")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            val failed = lines("ramp_merchant.tier").last()
            assertEquals(AuditOutcome.FAILED to "ramp.merchant_not_found", failed.outcome to failed.errorCode)
            val notCents = put(path, """{"merchantType":"BUSINESS","orderLimitMinor":"50,000","reason":"$reason"}""")
            assertEquals(HttpStatusCode.BadRequest, notCents.status)
            assertTrue("\"field\":\"orderLimitMinor\"" in notCents.bodyAsText())
        }

    @Test
    fun `a merchant's signals are what the engine scores them on, per direction`() =
        testApplication {
            application { harness.install(this) }
            val merchant = harness.ramp.add("APPROVED")

            val body = read("/merchants/$merchant/signals?network=TESTNET").bodyAsText()

            assertTrue(
                """"data":{"merchantId":"$merchant","network":"TESTNET","merchantType":"INDIVIDUAL",""" +
                    """"directions":[{"direction":"ON_RAMP","offered":true,"orderLimitMinor":"3000000",""" +
                    """"stats":{"finished":6,"completed":4,"merchantFailures":1,"merchantExpiries":0,"reversals":0,""" +
                    """"counted":5,"notCounted":1,"medianHandlingSeconds":12,"p90HandlingSeconds":40},"trades":4,""" +
                    """"newcomerTrades":20,"newcomerTradesLeft":16,"targetSeconds":300,"slaSeconds":900,""" +
                    """"statsOrders":50,"statsDays":30},{"direction":"OFF_RAMP","offered":false,""" in body,
                body,
            )
            assertEquals(
                HttpStatusCode.BadRequest,
                read("/merchants/$merchant/signals").status,
                "the network is required",
            )
            assertEquals(HttpStatusCode.NotFound, read("/merchants/${MerchantId.new()}/signals?network=TESTNET").status)
        }
}
