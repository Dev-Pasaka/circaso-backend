package com.circaso.ops

import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.Permission
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsRampOrder
import com.circaso.ramp.api.OpsRampOrderCursor
import com.circaso.ramp.api.OpsRampPaymentsIssue
import com.circaso.ramp.api.OpsRampPeriod
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
import java.time.LocalDate
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
        provider: String = "payhero",
        reason: String = "Opening the testnet to the pilot group",
    ) = """{"state":"$state","chains":["BASE-SEPOLIA"],"orderMinMinor":"1000","orderMaxMinor":"500000",""" +
        """"dailyLimitMinor":"2000000","promptsPerHour":5,"feeBps":$feeBps,"merchantShareBps":7000,""" +
        """"feeSettleHourUtc":21,"quoteSeconds":60,"paymentWindowSeconds":120,"kycRequired":false,""" +
        """"callbackBaseUrl":"https://sandbox.circaso.com","collectionsProvider":"$provider",""" +
        """"version":$version,"reason":"$reason"}"""

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
    fun `finance and admin read the merchants, their activity, settings and queues, and support none of it`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add()

            for (path in listOf(
                "/merchants",
                "/settings?network=TESTNET",
                "/preview-access?network=MAINNET",
                "/orders?state=REVIEW",
                "/overview?network=TESTNET",
                "/merchants/$merchant/summary?network=TESTNET",
                "/merchants/$merchant/orders?network=TESTNET",
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
    fun `the collections provider is a setting staff switch, among what the server offers, and it is audited`() =
        testApplication {
            application { harness.install(this) }

            val before = read("/settings?network=TESTNET").bodyAsText()
            assertTrue("\"collectionsProvider\":\"payhero\"" in before, before)
            assertTrue("\"collectionsProviders\":[\"payhero\",\"daraja\"]" in before, before)

            val switched = putSettings("TESTNET", settingsBody(state = "OFF", feeBps = 150, provider = "daraja"))
            assertEquals(HttpStatusCode.OK, switched.status, switched.bodyAsText())
            assertTrue("\"collectionsProvider\":\"daraja\"" in switched.bodyAsText())
            assertEquals("daraja", ramp.saved.single().collectionsProvider)
            val line = lines("ramp_settings.update").single()
            assertTrue("collectionsProvider payhero→daraja" in line.detail.orEmpty(), line.detail)

            // `log` reports every prompt as paid: a server doesn't offer it, so it can't be chosen
            val refused = putSettings("TESTNET", settingsBody(version = 2, provider = "log"))
            assertEquals(HttpStatusCode.BadRequest, refused.status)
            assertTrue("ramp.settings_invalid" in refused.bodyAsText())
            assertEquals(1, ramp.saved.size, "nothing was saved")
            val failed = lines("ramp_settings.update").last()
            assertEquals(AuditOutcome.FAILED to "ramp.settings_invalid", failed.outcome to failed.errorCode)

            val missing = putSettings("TESTNET", settingsBody().replace("\"collectionsProvider\":\"payhero\",", ""))
            assertEquals(HttpStatusCode.BadRequest, missing.status, "the provider is part of the settings")
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

    @Test
    fun `an admin resolves an order in review with a reason, audited on the order, and nobody else can`() =
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
                    fiatAmountMinor = 100_000,
                    assetAmountMinor = 7_800_000,
                    feeMinor = 1_520,
                    failureReason = "payout_unconfirmed",
                    custodyTxId = null,
                    createdAt = FakeRampAdmin.AT,
                    updatedAt = FakeRampAdmin.AT,
                    direction = "OFF_RAMP",
                    providerFeeMinor = 500,
                    fundedMinor = 7_800_000,
                    payoutAttempts = 1,
                    payoutOutcome = "UNKNOWN",
                )
            ramp.review += order
            val path = "/orders/${order.orderId}/resolve"

            val listed = read("/orders?state=REVIEW").bodyAsText()
            assertTrue("\"direction\":\"OFF_RAMP\"" in listed && "\"payoutOutcome\":\"UNKNOWN\"" in listed, listed)
            assertTrue("\"fundedMinor\":\"7800000\"" in listed && "\"providerFeeMinor\":\"500\"" in listed)

            val finance =
                post(path, """{"resolution":"REFUND","reason":"Statement shows nothing"}""", StaffRole.FINANCE)
            assertEquals(HttpStatusCode.Forbidden, finance.status)
            val noReason = post(path, """{"resolution":"REFUND","reason":" "}""")
            assertTrue("ops.reason_required" in noReason.bodyAsText(), noReason.bodyAsText())
            assertTrue(ramp.resolutions.isEmpty() && harness.audit.entries.isEmpty())

            val paid = post(path, """{"resolution":"MARK_PAID","receipt":"QP12AB","reason":"Seen on the statement"}""")
            assertEquals(HttpStatusCode.OK, paid.status, paid.bodyAsText())
            assertTrue("\"state\":\"COMPLETED\"" in paid.bodyAsText(), paid.bodyAsText())
            assertEquals(Triple(order.orderId, "MARK_PAID", "QP12AB"), ramp.resolutions.single())
            val line = lines("ramp_order.resolve").single()
            assertEquals(order.orderId.toString() to "ramp_order", line.targetId to line.targetType)
            assertEquals("Seen on the statement", line.reason)
            assertFalse("QP12AB" in line.detail.orEmpty(), "the receipt never reaches the audit line")

            val recheck = post(path, """{"resolution":"RECHECK","reason":"Ask again"}""")
            assertTrue("\"providerSaid\":\"queued\"" in recheck.bodyAsText(), recheck.bodyAsText())
            assertEquals(HttpStatusCode.BadRequest, post("/orders/nope/resolve", """{"reason":"x y z"}""").status)
        }

    // What each merchant did (2026-11-22)

    @Test
    fun `the provider fee floor is a setting - shown, changed with the rest, and left alone by an older form`() =
        testApplication {
            application { harness.install(this) }

            assertTrue("\"providerFeeFloorMinor\":\"5000\"" in read("/settings?network=TESTNET").bodyAsText())

            val older = putSettings("TESTNET", settingsBody())
            assertEquals(HttpStatusCode.OK, older.status, older.bodyAsText())
            assertNull(ramp.saved.last().providerFeeFloorMinor, "a form that doesn't know the field changes nothing")
            assertTrue("\"providerFeeFloorMinor\":\"5000\"" in older.bodyAsText())

            val floor = "\"providerFeeFloorMinor\":\"10000\","
            val body = settingsBody(version = 2).replace("\"version\"", "$floor\"version\"")
            val changed = putSettings("TESTNET", body)
            assertEquals(HttpStatusCode.OK, changed.status, changed.bodyAsText())
            assertEquals(10_000L, ramp.saved.last().providerFeeFloorMinor)
            assertTrue("\"providerFeeFloorMinor\":\"10000\"" in changed.bodyAsText())
            assertTrue("providerFeeFloorMinor 5000→10000" in lines("ramp_settings.update").last().detail.orEmpty())

            val bad = putSettings("TESTNET", body.replace("\"10000\"", "\"fifty\""))
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            assertTrue("providerFeeFloorMinor" in bad.bodyAsText())
        }

    @Test
    fun `the overview is one network's, for the period asked or this month, in the agreed shape`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add("APPROVED")

            assertEquals(HttpStatusCode.BadRequest, read("/overview").status, "the network is required")
            val response = read("/overview?network=TESTNET")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()

            assertTrue("\"network\":\"TESTNET\",\"from\":\"2026-11-01\",\"to\":\"2026-11-30\"" in body, body)
            assertTrue(
                "\"totals\":{\"orders\":42,\"completed\":40,\"failed\":1,\"inReview\":1," +
                    "\"collected\":{\"unit\":\"KES\",\"amountMinor\":\"5460000\",\"amount\":\"54600\"}," +
                    "\"sold\":{\"unit\":\"USDC\",\"amountMinor\":\"415800000\",\"amount\":\"415.8\"}" in body,
                body,
            )
            for (field in listOf("fees", "merchantShare", "circasoShare", "circasoShareUsdc", "swept", "held")) {
                assertTrue("\"$field\":{\"unit\":" in body, field)
            }
            assertTrue(
                "\"merchants\":[{\"merchantId\":\"$merchant\",\"displayName\":\"Crynce\",\"state\":\"APPROVED\"," +
                    "\"online\":true,\"orders\":42,\"completed\":40,\"successRatePercent\":\"95.2\"," in body,
                body,
            )
            assertTrue("\"lastOrderAt\":\"2026-11-22T12:00:00Z\"" in body)
            assertEquals(FakeRampAdmin.Asked(null, Network.TESTNET, null), ramp.asked.single())

            read("/overview?network=MAINNET&from=2026-10-01&to=2026-10-31")
            val october = OpsRampPeriod(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"))
            assertEquals(FakeRampAdmin.Asked(null, Network.MAINNET, october), ramp.asked.last())

            for (query in listOf(
                "from=2026-10-01",
                "to=2026-10-31",
                "from=2026-10-31&to=2026-10-01",
                "from=1st&to=2026-10-31",
                "from=2025-01-01&to=2026-10-31",
            )) {
                val refused = read("/overview?network=TESTNET&$query")
                assertEquals(HttpStatusCode.BadRequest, refused.status, query)
                assertTrue("request.malformed" in refused.bodyAsText())
            }
            assertEquals(2, ramp.asked.size, "a malformed period asks the ramp nothing")
        }

    @Test
    fun `a merchant's summary shows the account, float, orders, money, days and sweeps in the agreed shape`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add("APPROVED")

            val response = read("/merchants/$merchant/summary?network=TESTNET&from=2026-11-01&to=2026-11-30")
            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()

            assertTrue(
                "\"merchant\":{\"merchantId\":\"$merchant\",\"displayName\":\"Crynce\"," +
                    "\"email\":\"pay@crynce.co.ke\",\"contactPhone\":\"+254712000111\",\"state\":\"APPROVED\"," +
                    "\"createdAt\":\"2026-11-22T12:00:00Z\",\"approvedAt\":\"2026-11-22T12:00:00Z\"," +
                    "\"online\":true,\"onlineUntil\":\"2026-11-22T13:00:00Z\"," +
                    "\"paymentsConnected\":true,\"collectionsProvider\":\"payhero\",\"paymentsIssue\":null," +
                    "\"kyc\":null,\"offersOffRamp\":false,\"payoutAccountId\":null,\"payoutIssue\":null," +
                    "\"onlineOpenEnded\":false,\"offlineReason\":null}" in body,
                body,
            )
            assertTrue("\"wallet\":{\"blockchain\":\"BASE-SEPOLIA\",\"address\":\"0xfloat\"}" in body, body)
            assertTrue(
                "\"float\":{\"balance\":{\"unit\":\"USDC\",\"amountMinor\":\"100000000\",\"amount\":\"100\"}," +
                    "\"reserved\":{\"unit\":\"USDC\",\"amountMinor\":\"9800000\",\"amount\":\"9.8\"}," +
                    "\"owedToCircaso\":{\"unit\":\"USDC\",\"amountMinor\":\"120000\",\"amount\":\"0.12\"}," +
                    "\"free\":{" in body,
                body,
            )
            assertTrue(
                "\"orders\":{\"total\":42,\"completed\":40,\"failed\":1,\"expired\":0,\"cancelled\":0," +
                    "\"inReview\":1,\"open\":0,\"refunded\":0},\"successRatePercent\":\"95.2\",\"collected\":{" in body,
                body,
            )
            val money = listOf("sold", "fees", "merchantShare", "circasoShare", "circasoShareUsdc", "swept", "held")
            for (field in money) {
                assertTrue("\"$field\":{\"unit\":" in body, field)
            }
            assertTrue("\"daily\":[{\"day\":\"2026-11-21\",\"orders\":9,\"completed\":9,\"collected\":{" in body, body)
            assertTrue(
                "\"sweeps\":[{\"settlementId\":\"${FakeRampAdmin.SWEEP}\"," +
                    "\"day\":\"2026-11-21\",\"amount\":{" in body &&
                    "\"state\":\"CONFIRMED\",\"orders\":9,\"txHash\":\"0xsweep\"}" in body,
                body,
            )
            assertFalse("password" in body || "username" in body || "channel" in body.lowercase())
            assertEquals(FakeRampAdmin.Asked(merchant, Network.TESTNET, FakeRampAdmin.MONTH), ramp.asked.single())

            // a custodian that can't be read is a null float, never a zero; no wallet on the network is a null wallet
            ramp.floatReadable = false
            val down = read("/merchants/$merchant/summary?network=TESTNET").bodyAsText()
            assertTrue("\"float\":null" in down && "\"wallet\":{" in down, down)
            ramp.floatReadable = null
            val none = read("/merchants/$merchant/summary?network=MAINNET").bodyAsText()
            assertTrue("\"wallet\":null,\"float\":null" in none, none)

            // a merchant whose provider refused them for their service balance says so
            ramp.issue = OpsRampPaymentsIssue("SERVICE_BALANCE", FakeRampAdmin.AT)
            val marked = read("/merchants/$merchant/summary?network=TESTNET").bodyAsText()
            assertTrue(
                "\"paymentsIssue\":{\"code\":\"SERVICE_BALANCE\",\"since\":\"2026-11-22T12:00:00Z\"}" in marked,
                marked,
            )

            assertEquals(HttpStatusCode.BadRequest, read("/merchants/$merchant/summary").status)
            assertEquals(HttpStatusCode.BadRequest, read("/merchants/nope/summary?network=TESTNET").status)
            val missing = read("/merchants/${MerchantId.new()}/summary?network=TESTNET")
            assertEquals(HttpStatusCode.NotFound, missing.status)
            assertTrue("ramp.merchant_not_found" in missing.bodyAsText())
        }

    @Test
    fun `a merchant's orders page newest first with the payer masked, narrowed by period and state`() =
        testApplication {
            application { harness.install(this) }
            val merchant = ramp.add("APPROVED")
            val done = ramp.order("COMPLETED")
            val lost = ramp.order("FAILED", FakeRampAdmin.AT.minusSeconds(600))

            val body = read("/merchants/$merchant/orders?network=TESTNET").bodyAsText()

            assertTrue(
                "\"orders\":[{\"orderId\":\"${done.orderId}\",\"createdAt\":\"2026-11-22T12:00:00Z\"," +
                    "\"completedAt\":\"2026-11-22T12:04:10Z\",\"state\":\"COMPLETED\",\"direction\":\"ON_RAMP\"," +
                    "\"blockchain\":\"BASE-SEPOLIA\",\"maskedPhone\":\"+254 7•• ••• 324\"," +
                    "\"paid\":{\"unit\":\"KES\",\"amountMinor\":\"130000\",\"amount\":\"1300\"}," +
                    "\"released\":{\"unit\":\"USDC\",\"amountMinor\":\"9800000\",\"amount\":\"9.8\"}," +
                    "\"rate\":\"129.96\",\"fee\":{" in body,
                body,
            )
            assertTrue(
                "\"failureReason\":null,\"settlementId\":\"${FakeRampAdmin.SWEEP}\",\"txHash\":\"0xrelease\"," +
                    "\"collectionsProvider\":\"payhero\"}" in body,
                body,
            )
            assertTrue(
                "\"completedAt\":null,\"state\":\"FAILED\"" in body &&
                    "\"merchantShare\":null,\"circasoShare\":null,\"circasoShareUsdc\":null," +
                    "\"failureReason\":\"payment_failed\",\"settlementId\":null,\"txHash\":null" in body,
                body,
            )
            assertTrue("\"nextCursor\":null" in body)
            assertFalse("payerPhone" in body || "+2547" in body, "the payer's number is only ever masked")
            assertEquals(
                FakeRampAdmin.Asked(merchant, Network.TESTNET, null, null, null, 50),
                ramp.asked.single(),
                "every day, every state, the first page of 50",
            )

            // a full page hands out a cursor, and the cursor comes back as the ramp's own
            val first = read("/merchants/$merchant/orders?network=TESTNET&limit=1").bodyAsText()
            val cursor = "2026-11-22T12:00:00Z~${done.orderId}"
            assertTrue("\"nextCursor\":\"$cursor\"" in first, first)
            val narrowed = "limit=1&cursor=$cursor&state=failed&from=2026-11-01&to=2026-11-30"
            read("/merchants/$merchant/orders?network=TESTNET&$narrowed")
            assertEquals(
                FakeRampAdmin.Asked(
                    merchant,
                    Network.TESTNET,
                    FakeRampAdmin.MONTH,
                    "FAILED",
                    OpsRampOrderCursor(FakeRampAdmin.AT, done.orderId),
                    1,
                ),
                ramp.asked.last(),
            )
            val failed = read("/merchants/$merchant/orders?network=TESTNET&state=FAILED").bodyAsText()
            assertTrue("\"orderId\":\"${lost.orderId}\"" in failed && "\"orderId\":\"${done.orderId}\"" !in failed)

            val asked = ramp.asked.size
            for (query in listOf(
                "",
                "network=TESTNET&state=DONE",
                "network=TESTNET&cursor=nope",
                "network=TESTNET&cursor=2026-11-22T12:00:00Z~nope",
                "network=TESTNET&from=2026-11-01",
                "network=TESTNET&limit=0",
                "network=SOLANA",
            )) {
                val refused = read("/merchants/$merchant/orders?$query")
                assertEquals(HttpStatusCode.BadRequest, refused.status, query)
            }
            assertEquals(asked, ramp.asked.size, "a malformed request asks the ramp nothing")
            val missing = read("/merchants/${MerchantId.new()}/orders?network=TESTNET")
            assertEquals(HttpStatusCode.NotFound, missing.status)
        }
}
