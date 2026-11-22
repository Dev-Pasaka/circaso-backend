package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.Permission
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.RampOrderId
import com.circaso.shared.Network
import com.circaso.shared.StaffRole
import com.circaso.shared.UserId
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The ramp on the ops routes (decision 2026-11-22): FINANCE and ADMIN read, only ADMIN approves, suspends, changes the
 * settings or the preview list; every write needs a reason and lands in the audit log with who, why and what changed.
 */
class RampRoutesTest {
    private val harness = OpsHarness()
    private val ramp = harness.ramp

    private fun HttpRequestBuilder.signedIn(role: StaffRole) {
        header(OpsHarness.ROLE_HEADER, role.name)
        header("Idempotency-Key", "key-1")
    }

    private suspend fun ApplicationTestBuilder.read(path: String, role: StaffRole = StaffRole.FINANCE): HttpResponse =
        client.get("/v1/ops/ramp$path") { signedIn(role) }

    private suspend fun ApplicationTestBuilder.post(
        path: String,
        body: String,
        role: StaffRole = StaffRole.ADMIN,
    ): HttpResponse =
        client.post("/v1/ops/ramp$path") {
            signedIn(role)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private suspend fun ApplicationTestBuilder.putSettings(
        network: String,
        body: String,
        role: StaffRole = StaffRole.ADMIN,
    ): HttpResponse =
        client.put("/v1/ops/ramp/settings?network=$network") {
            signedIn(role)
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun settingsBody(
        state: String = "PREVIEW",
        feeBps: Int = 200,
        version: Int = 1,
        reason: String = "Opening the testnet to the pilot group",
    ) = """{"state":"$state","chains":["BASE-SEPOLIA"],"orderMinMinor":"1000","orderMaxMinor":"500000",""" +
        """"dailyLimitMinor":"2000000","promptsPerHour":5,"feeBps":$feeBps,"merchantShareBps":7000,""" +
        """"feeSettleHourUtc":21,"quoteSeconds":60,"paymentWindowSeconds":120,"kycRequired":false,""" +
        """"callbackBaseUrl":"https://sandbox.circaso.com","version":$version,"reason":"$reason"}"""

    private fun lines(action: String) = harness.audit.entries.filter { it.action == action }

    @Test
    fun `the permissions - finance and admin read the ramp, only an admin changes it`() {
        assertEquals(setOf(StaffRole.ADMIN, StaffRole.FINANCE), Permission.RAMP_READ.roles)
        assertEquals(setOf(StaffRole.ADMIN), Permission.RAMP_MANAGE.roles)
        assertEquals(
            listOf("ramp.read", "ramp.manage"),
            listOf(Permission.RAMP_READ, Permission.RAMP_MANAGE).map { it.wire },
        )
    }

    @Test
    fun `finance and admin read the merchants, settings, preview list and review queue, and support none of it`() =
        testApplication {
            application { harness.install(this) }
            ramp.add()

            for (path in listOf(
                "/merchants",
                "/settings?network=TESTNET",
                "/preview-access?network=MAINNET",
                "/orders?state=REVIEW",
            )) {
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.FINANCE).status, path)
                assertEquals(HttpStatusCode.OK, read(path, StaffRole.ADMIN).status, path)
                val support = read(path, StaffRole.SUPPORT)
                assertEquals(HttpStatusCode.Forbidden, support.status, path)
                assertTrue("ops.role_forbidden" in support.bodyAsText())
            }
        }

    @Test
    fun `only an admin approves, suspends, changes the settings or the preview list`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add()
            val user = harness.accounts.add()

            for (role in listOf(StaffRole.FINANCE, StaffRole.SUPPORT)) {
                val refused =
                    listOf(
                        post("/merchants/$merchant/approve", """{"reason":"Vetted partner"}""", role),
                        post("/merchants/$merchant/suspend", """{"reason":"Complaints"}""", role),
                        putSettings("TESTNET", settingsBody(), role),
                        post(
                            "/preview-access",
                            """{"userId":"$user","network":"TESTNET","enabled":true,"reason":"Staff"}""",
                            role,
                        ),
                    )
                refused.forEach {
                    assertEquals(HttpStatusCode.Forbidden, it.status, "$role")
                    assertTrue("ops.role_forbidden" in it.bodyAsText())
                }
            }

            assertTrue(ramp.decisions.isEmpty() && ramp.saved.isEmpty() && ramp.preview.isEmpty())
            assertTrue(harness.audit.entries.isEmpty(), "a refused role leaves no audit line")
        }

    @Test
    fun `the merchant list shows states and wallets, and never a credential`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add("APPROVED")

            val body = read("/merchants").bodyAsText()

            assertTrue("\"merchantId\":\"$merchant\"" in body && "\"state\":\"APPROVED\"" in body, body)
            assertTrue("\"paymentsConnected\":true" in body && "\"address\":\"0xfloat\"" in body)
            assertTrue("\"online\":false" in body && "\"nextCursor\":null" in body)
            assertFalse("password" in body || "username" in body || "pin" in body.lowercase().replace("pinned", ""))
            assertEquals(HttpStatusCode.BadRequest, read("/merchants?cursor=nope").status)
        }

    @Test
    fun `approving and suspending need a reason and are audited with who, why and the new state`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add()

            val noReason = post("/merchants/$merchant/approve", """{"reason":" "}""")
            assertEquals(HttpStatusCode.BadRequest, noReason.status)
            assertTrue("ops.reason_required" in noReason.bodyAsText())
            assertTrue(harness.audit.entries.isEmpty() && ramp.decisions.isEmpty())

            val approved = post("/merchants/$merchant/approve", """{"reason":"Vetted partner, contract signed"}""")
            assertEquals(HttpStatusCode.OK, approved.status)
            assertTrue("\"state\":\"APPROVED\"" in approved.bodyAsText())
            assertEquals(Triple(merchant, true, harness.ids.getValue(StaffRole.ADMIN)), ramp.decisions.single())
            val line = lines("ramp_merchant.approve").single()
            assertEquals(merchant.toString() to "ramp_merchant", line.targetId to line.targetType)
            assertEquals("Vetted partner, contract signed" to "state APPROVED", line.reason to line.detail)
            assertEquals(AuditOutcome.SUCCEEDED to "admin@circaso.com", line.outcome to line.actorEmail)

            val suspended = post("/merchants/$merchant/suspend", """{"reason":"Three reversal complaints"}""")
            assertTrue("\"state\":\"SUSPENDED\"" in suspended.bodyAsText())
            assertEquals("state SUSPENDED", lines("ramp_merchant.suspend").single().detail)

            val missing = post("/merchants/${MerchantId.new()}/approve", """{"reason":"Vetted partner"}""")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            val failed = lines("ramp_merchant.approve").last()
            assertEquals(AuditOutcome.FAILED to "ramp.merchant_not_found", failed.outcome to failed.errorCode)
            assertEquals(HttpStatusCode.BadRequest, post("/merchants/nope/approve", """{"reason":"Vetted"}""").status)
        }

    @Test
    fun `the settings are one network's, read and changed with the network named`() =
        testApplication {
            application { harness.install(this) }

            assertEquals(HttpStatusCode.BadRequest, read("/settings").status, "the network is required")
            val testnet = read("/settings?network=TESTNET").bodyAsText()
            assertTrue("\"network\":\"TESTNET\"" in testnet && "\"state\":\"OFF\"" in testnet, testnet)
            assertTrue(
                "\"orderMinMinor\":\"1000\"" in testnet && "\"feeBps\":150" in testnet && "\"version\":1" in testnet,
            )
            assertTrue("\"kycRequired\":true" in read("/settings?network=MAINNET").bodyAsText())

            assertEquals(HttpStatusCode.BadRequest, putSettings("TESTNET", settingsBody(reason = "")).status)
            assertTrue(ramp.saved.isEmpty())

            val changed = putSettings("TESTNET", settingsBody())
            assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            assertTrue("\"state\":\"PREVIEW\"" in changed.bodyAsText() && "\"version\":2" in changed.bodyAsText())
            val saved = ramp.saved.single()
            assertEquals(Network.TESTNET, saved.network)
            assertEquals(Triple("PREVIEW", 200, 1L), Triple(saved.state, saved.feeBps, saved.version))
            assertEquals(1_000L to "https://sandbox.circaso.com", saved.orderMinMinor to saved.callbackBaseUrl)
            assertNull(saved.walletSetId, "read-only fields are never taken from a request")

            val line = lines("ramp_settings.update").single()
            assertEquals("TESTNET" to "ramp_settings", line.targetId to line.targetType)
            assertEquals("Opening the testnet to the pilot group", line.reason)
            assertEquals(
                "v1→v2; state OFF→PREVIEW; feeBps 150→200; " +
                    "callbackBaseUrl https://api.circaso.com→https://sandbox.circaso.com",
                line.detail,
            )

            ramp.refusal = "ramp.settings_changed"
            val stale = putSettings("TESTNET", settingsBody(version = 1))
            assertEquals(HttpStatusCode.Conflict, stale.status)
            assertEquals("ramp.settings_changed", lines("ramp_settings.update").last().errorCode)
            ramp.refusal = null

            val notANumber =
                putSettings(
                    "TESTNET",
                    settingsBody().replace("\"orderMinMinor\":\"1000\"", "\"orderMinMinor\":\"ten\""),
                )
            assertEquals(HttpStatusCode.BadRequest, notANumber.status)
            assertTrue("orderMinMinor" in notANumber.bodyAsText())
        }

    @Test
    fun `the preview list is changed for a real person, with a reason, and audited on that person`() =
        testApplication {
            application { harness.install(this) }
            val user = harness.accounts.add()

            fun body(
                who: Any = user,
                enabled: Boolean = true,
                reason: String = "Staff testing the on-ramp",
            ) = """{"userId":"$who","network":"TESTNET","enabled":$enabled,"reason":"$reason"}"""

            assertEquals(HttpStatusCode.BadRequest, post("/preview-access", body(reason = "")).status)
            assertEquals(HttpStatusCode.NotFound, post("/preview-access", body(who = UserId.new())).status)
            assertTrue(ramp.preview.isEmpty() && harness.audit.entries.isEmpty())

            val granted = post("/preview-access", body())
            assertEquals(HttpStatusCode.OK, granted.status)
            assertTrue(
                "\"userId\":\"$user\"" in granted.bodyAsText() && "\"network\":\"TESTNET\"" in granted.bodyAsText(),
            )
            assertTrue("\"userId\":\"$user\"" in read("/preview-access?network=TESTNET").bodyAsText())
            assertFalse("\"userId\"" in read("/preview-access?network=MAINNET").bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, read("/preview-access").status)

            post("/preview-access", body(enabled = false, reason = "Testing is over"))
            assertTrue(ramp.preview.isEmpty())
            val audited = lines("user.ramp_preview_access")
            assertEquals(listOf("grant TESTNET", "revoke TESTNET"), audited.map { it.detail })
            assertTrue(audited.all { it.targetId == user.toString() && it.targetType == "user" })
        }

    @Test
    fun `the review queue lists orders waiting for a person, on both networks or the one named`() =
        testApplication {
            application { harness.install(this) }
            val order =
                OpsRampOrder(
                    orderId = RampOrderId.new(),
                    network = Network.TESTNET,
                    userId = UserId.new(),
                    merchantId = MerchantId.new(),
                    merchantName = "Crynce",
                    state = "REVIEW",
                    blockchain = "BASE-SEPOLIA",
                    fiatCurrency = "KES",
                    fiatAmountMinor = 130_000,
                    assetAmountMinor = 9_800_000,
                    feeMinor = 2_600,
                    failureReason = "payment_mismatch",
                    custodyTxId = null,
                    createdAt = FakeRampAdmin.AT,
                    updatedAt = FakeRampAdmin.AT,
                )
            ramp.review += order

            val all = read("/orders?state=REVIEW").bodyAsText()
            assertTrue(
                "\"orderId\":\"${order.orderId}\"" in all && "\"failureReason\":\"payment_mismatch\"" in all,
                all,
            )
            assertTrue("\"fiatAmountMinor\":\"130000\"" in all && "\"assetAmountMinor\":\"9800000\"" in all)
            assertFalse("phone" in all.lowercase(), "no payer number is ever shown to staff here")
            assertFalse("\"orderId\"" in read("/orders?network=MAINNET").bodyAsText())
            assertEquals(listOf(null, Network.MAINNET), ramp.reviewAsked.toList())
            assertEquals(HttpStatusCode.BadRequest, read("/orders?state=PAID").status)
            assertEquals(HttpStatusCode.BadRequest, read("/orders?cursor=nope").status)
        }
}
