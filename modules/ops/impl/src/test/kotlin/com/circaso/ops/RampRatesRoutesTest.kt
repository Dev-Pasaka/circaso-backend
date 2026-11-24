package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.shared.Network
import com.circaso.shared.StaffRole
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The ramp's rates on the ops routes (decision 2026-11-24): FINANCE and ADMIN read the pricing settings and the rates
 * in force; only ADMIN changes the settings, with a reason, audited with who, why and what changed.
 */
class RampRatesRoutesTest {
    private val harness = OpsHarness()
    private val rates = harness.rampRates

    private fun HttpRequestBuilder.signedIn(role: StaffRole) {
        header(OpsHarness.ROLE_HEADER, role.name)
        header("Idempotency-Key", "key-1")
    }

    private suspend fun ApplicationTestBuilder.read(path: String, role: StaffRole = StaffRole.FINANCE): HttpResponse =
        client.get("/v1/ops/ramp$path") { signedIn(role) }

    private suspend fun ApplicationTestBuilder.put(
        body: String,
        role: StaffRole = StaffRole.ADMIN,
        network: String = "TESTNET",
    ): HttpResponse =
        client.put("/v1/ops/ramp/pricing?network=$network") {
            signedIn(role)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    @Test
    fun `finance and admin read the rates, support doesn't, and only an admin changes them`() =
        testApplication {
            application { harness.install(this) }
            for (path in listOf("/pricing?network=TESTNET", "/rates?network=MAINNET")) {
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.FINANCE).status, path)
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.ADMIN).status, path)
                assertEquals(HttpStatusCode.Forbidden, read(path, StaffRole.SUPPORT).status, path)
            }
            val noNetwork = read("/rates")
            assertEquals(HttpStatusCode.BadRequest, noNetwork.status)
            assertTrue("\"field\":\"network\"" in noNetwork.bodyAsText())

            val body = """{"mode":"REFERENCE_SPREAD","buySpreadBps":150,"version":1,"reason":"P2P premium"}"""
            for (role in listOf(StaffRole.FINANCE, StaffRole.SUPPORT)) {
                assertEquals(HttpStatusCode.Forbidden, put(body, role).status)
            }
            assertTrue(rates.updates.isEmpty() && harness.audit.entries.isEmpty())
        }

    @Test
    fun `the rates come in the agreed shape`() =
        testApplication {
            application { harness.install(this) }
            val pricing = read("/pricing?network=TESTNET").bodyAsText()
            assertTrue(
                """"data":{"network":"TESTNET","mode":"REFERENCE_SPREAD","buySpreadBps":0,"sellSpreadBps":0,""" +
                    """"manualBuyRate":null,"manualSellRate":null,"manualExpiresAt":null,"maxDeviationBps":500,""" +
                    """"maxReferenceAgeHours":36,"version":1,"updatedBy":null,"reason":null,""" +
                    """"updatedAt":"2026-11-24T09:00:00Z"}""" in pricing,
                pricing,
            )
            val now = read("/rates?network=TESTNET").bodyAsText()
            assertTrue(
                """"referenceRate":"129.25","referenceAsOf":"2026-11-24T08:00:00Z","referenceAgeSeconds":3600,""" +
                    """"referenceStale":false""" in now,
                now,
            )
            assertTrue(
                """"buy":{"rate":"131.18875","source":"REFERENCE_SPREAD","spreadBps":150,"paused":null}""" in now,
            )
            assertTrue(
                """"sell":{"rate":null,"source":"REFERENCE_SPREAD","spreadBps":null,"paused":"REFERENCE_STALE"}""" in
                    now,
            )
        }

    @Test
    fun `a change needs a reason, is audited with what changed, and the ramp's refusals come through`() =
        testApplication {
            application { harness.install(this) }
            val noReason = put("""{"mode":"REFERENCE_SPREAD","buySpreadBps":150,"version":1,"reason":" "}""")
            assertEquals(HttpStatusCode.BadRequest, noReason.status)
            assertTrue("ops.reason_required" in noReason.bodyAsText())

            val reason = "Matching the P2P market"
            val manual =
                """{"mode":"manual","manualBuyRate":"131.5","manualSellRate":"127.25",""" +
                    """"manualExpiresAt":"2026-11-25T09:00:00Z","version":1,"reason":"$reason"}"""
            val changed = put(manual)
            assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            val body = changed.bodyAsText()
            assertTrue(
                "\"version\":2" in body && "\"mode\":\"MANUAL\"" in body && "\"manualBuyRate\":\"131.5\"" in body,
            )
            val (row, why, by) = rates.updates.single()
            assertEquals(Instant.parse("2026-11-25T09:00:00Z"), row.manualExpiresAt)
            assertEquals(Network.TESTNET, row.network)
            assertEquals(reason to harness.ids.getValue(StaffRole.ADMIN), why to by)
            val line = harness.audit.entries.single { it.action == "ramp_pricing.update" }
            assertEquals("TESTNET" to "ramp_pricing", line.targetId to line.targetType)
            assertEquals(reason, line.reason)
            assertEquals(
                "v1→v2; mode REFERENCE_SPREAD→MANUAL; manualBuyRate null→131.5; manualSellRate null→127.25; " +
                    "manualExpiresAt null→2026-11-25T09:00:00Z",
                line.detail,
            )
            assertEquals(AuditOutcome.SUCCEEDED, line.outcome)

            val stale = put(manual)
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertTrue("ramp.settings_changed" in stale.bodyAsText())

            val refused = put("""{"mode":"REFERENCE_SPREAD","buySpreadBps":2500,"reason":"$reason"}""")
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue("ramp.settings_invalid" in refused.bodyAsText() && "buySpreadBps" in refused.bodyAsText())

            val badMode = put("""{"mode":"FLOATING","reason":"$reason"}""")
            assertEquals(HttpStatusCode.BadRequest, badMode.status)
            assertTrue("\"field\":\"mode\"" in badMode.bodyAsText())
            val badTime = put("""{"mode":"MANUAL","manualExpiresAt":"tomorrow","reason":"$reason"}""")
            assertTrue("\"field\":\"manualExpiresAt\"" in badTime.bodyAsText())
            assertEquals(1, rates.updates.size, "nothing else was saved")
        }
}
