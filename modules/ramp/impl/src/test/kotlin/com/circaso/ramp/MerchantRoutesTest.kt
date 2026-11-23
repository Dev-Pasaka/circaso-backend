package com.circaso.ramp

import com.circaso.integrations.api.IntegrationError
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.usecases.GetTestPayout
import com.circaso.ramp.domain.usecases.GetTopUp
import com.circaso.ramp.domain.usecases.ListTopUps
import com.circaso.ramp.domain.usecases.StartTestPayout
import com.circaso.ramp.domain.usecases.StartTopUp
import com.circaso.ramp.presentation.controllers.merchantController
import com.circaso.ramp.presentation.services.MerchantAuthService
import com.circaso.ramp.presentation.services.MerchantService
import com.circaso.shared.PhoneNumber
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The merchant portal over HTTP, as `merchants.circaso.com` calls it: the session header, the JSON the portal reads
 * (Ramp → Merchant portal), and a field named in every validation error.
 */
class MerchantRoutesTest {
    private val ramp = Harness()
    private val auth = MerchantAuthService(ramp.requestCode, ramp.verifyCode, ramp.resolveSession, ramp.signOut)
    private val merchants =
        MerchantService(
            ramp.getMerchant,
            ramp.applyAsMerchant,
            ramp.storePayments,
            ramp.rotateCallback,
            ramp.goOnline,
            ramp.goOffline,
            ramp.changePin,
            ramp.merchantOrders,
            ramp.earnings,
            ramp.statement,
            ramp.startMerchantKyc,
            ramp.performance,
            ramp.setOffers,
            ramp.setPayoutAccount,
            StartTestPayout(ramp.testPayouts),
            GetTestPayout(ramp.testPayouts),
            ramp.setWebhookSecret,
            StartTopUp(ramp.topUps),
            GetTopUp(ramp.topUps),
            ListTopUps(ramp.topUps),
        )
    private val base = "/v1/ramp/merchant"

    private fun ApplicationTestBuilder.serve() {
        application {
            installRequestId()
            installJson()
            installStatusPages()
            routing { route("/v1") { merchantController(auth, merchants) } }
        }
    }

    private fun HttpRequestBuilder.json(body: String, session: String? = null) {
        session?.let { header("X-Merchant-Session", it) }
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.envelope(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.data(): JsonObject = envelope().getValue("data").jsonObject

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    private fun JsonElement.amount(): Triple<String, String, String> =
        jsonObject.let { Triple(it.text("unit"), it.text("amountMinor"), it.text("amount")) }

    private suspend fun HttpResponse.field(): String =
        envelope()
            .getValue("details")
            .jsonObject
            .getValue("fields")
            .jsonArray
            .single()
            .jsonObject
            .text("field")

    private suspend fun ApplicationTestBuilder.signIn(email: String = "pay@crynce.co.ke"): String {
        client.post("$base/auth/code") { json("""{"email":"$email"}""") }
        val verified =
            client.post(
                "$base/auth/verify",
            ) { json("""{"email":"$email","code":"${ramp.email.lastCode()}"}""") }
        return verified.data().text("sessionToken")
    }

    @Test
    fun `sign-in answers the shapes the portal reads, and a new account is NEW with everything missing`() =
        testApplication {
            serve()

            val sent = client.post("$base/auth/code") { json("""{"email":" Pay@Crynce.co.ke "}""") }
            assertEquals(HttpStatusCode.OK, sent.status)
            assertEquals(
                true,
                sent
                    .data()
                    .getValue("sent")
                    .jsonPrimitive.boolean,
            )
            assertEquals(
                60,
                sent
                    .data()
                    .getValue("retryAfterSeconds")
                    .jsonPrimitive.int,
            )
            assertEquals(
                "pay@crynce.co.ke",
                ramp.email.sent
                    .single()
                    .first,
                "an address is trimmed and lower-cased",
            )

            val again = client.post("$base/auth/code") { json("""{"email":"pay@crynce.co.ke"}""") }
            assertEquals(HttpStatusCode.TooManyRequests, again.status)
            assertEquals("ramp.code_locked", again.envelope().text("code"))
            assertEquals(
                60,
                again
                    .envelope()
                    .getValue("details")
                    .jsonObject
                    .getValue("retryAfterSeconds")
                    .jsonPrimitive.int,
            )
            assertEquals("60", again.headers["Retry-After"])

            val code = ramp.email.lastCode()
            val verified = client.post("$base/auth/verify") { json("""{"email":"pay@crynce.co.ke","code":"$code"}""") }
            val data = verified.data()
            assertTrue(data.text("sessionToken").length >= 43, "32 bytes, base64url")
            assertTrue(data.text("expiresAt").endsWith("Z"))
            val merchant = data.getValue("merchant").jsonObject
            assertEquals("pay@crynce.co.ke" to "NEW", merchant.text("email") to merchant.text("state"))
            assertEquals("TESTNET", merchant.text("network"))
            assertEquals(JsonNull, merchant.getValue("displayName"))
            assertEquals(JsonNull, merchant.getValue("contactPhone"))
            listOf("wallet", "float", "payments").forEach { assertEquals(JsonNull, merchant.getValue(it), it) }
            assertEquals(
                """{"profile":false,"kyc":true,"approved":false,"wallet":false,"payments":false}""",
                merchant.getValue("steps").toString(),
                "the testnet doesn't ask for an identity check here, so that step is done",
            )
            assertEquals(
                """{"status":"NONE","required":false,"canStart":true,"reason":null,"retryAt":null,"submitted":false}""",
                merchant.getValue("kyc").toString(),
            )
            assertEquals(
                """{"online":false,"until":null,"state":"OFFLINE","openEnded":false,"keysHeld":false,""" +
                    """"since":null,"offlineReason":null,"offlineAt":null}""",
                merchant.getValue("online").toString(),
            )
            assertFalse(merchant.getValue("pinSet").jsonPrimitive.boolean)
            assertFalse(merchant.getValue("canGoOnline").jsonPrimitive.boolean)
            assertEquals("""["profile","approval","wallet","payments"]""", merchant.getValue("missing").toString())

            val wrong = client.post("$base/auth/verify") { json("""{"email":"pay@crynce.co.ke","code":"$code"}""") }
            assertEquals(
                HttpStatusCode.BadRequest to "ramp.code_invalid",
                wrong.status to wrong.envelope().text("code"),
            )
        }

    @Test
    fun `every merchant route needs the ramp's own session, and an app token is not one`() =
        testApplication {
            serve()
            val session = signIn()
            val paths = listOf("", "/orders", "/earnings", "/statement")

            for (path in paths) {
                val none = client.get("$base$path")
                assertEquals(HttpStatusCode.Unauthorized, none.status, path)
                assertEquals("ramp.session_invalid", none.envelope().text("code"))
                val bearer = client.get("$base$path") { header("Authorization", "Bearer $session") }
                assertEquals(HttpStatusCode.Unauthorized, bearer.status, "Authorization is never read: $path")
                val forged = client.get("$base$path") { header("X-Merchant-Session", "A".repeat(43)) }
                assertEquals(HttpStatusCode.Unauthorized, forged.status, path)
                assertEquals(
                    HttpStatusCode.OK,
                    client.get("$base$path") { header("X-Merchant-Session", session) }.status,
                    path,
                )
            }
            val writes =
                listOf(
                    client.put("$base/profile") { json("""{"displayName":"X","contactPhone":"+254712000111"}""") },
                    client.put(
                        "$base/payments",
                    ) { json("""{"username":"u","password":"p","channelId":"1","pin":"482913"}""") },
                    client.post("$base/payments/callback") { json("{}") },
                    client.post("$base/session") { json("""{"pin":"482913","minutes":60}""") },
                    client.delete("$base/session"),
                    client.put("$base/pin") { json("""{"currentPin":"482913","newPin":"771204"}""") },
                )
            writes.forEach { assertEquals(HttpStatusCode.Unauthorized, it.status) }

            assertEquals(
                HttpStatusCode.OK,
                client.post("$base/auth/signout") { header("X-Merchant-Session", session) }.status,
            )
            assertEquals(HttpStatusCode.Unauthorized, client.get(base) { header("X-Merchant-Session", session) }.status)
        }

    @Test
    fun `a merchant sets up, goes online and reads their account in the shapes the portal reads`() =
        testApplication {
            serve()
            val session = signIn()
            ramp.merchants.rows.remove(ramp.crynce.merchantId)

            val applied =
                client.put(
                    "$base/profile",
                ) { json("""{"displayName":"Crynce","contactPhone":"+254712000111"}""", session) }
            assertEquals("APPLIED", applied.data().text("state"))
            assertEquals(
                "Crynce" to "+254712000111",
                applied.data().let {
                    it.text("displayName") to
                        it.text("contactPhone")
                },
            )

            val id = ramp.merchants.byEmail("pay@crynce.co.ke")!!.merchantId
            ramp.decideMerchant(id, approve = true, by = "staff")
            ramp.release()
            ramp.treasury.balances[ramp.merchantWallets.find(id, TESTNET)!!.custodyWalletId] = 100_000_000

            val payments =
                client.put("$base/payments") {
                    json("""{"username":"api-user","password":"api-pass","channelId":"911","pin":"482913"}""", session)
                }
            assertEquals(HttpStatusCode.OK, payments.status)
            assertTrue(
                payments
                    .data()
                    .getValue("connected")
                    .jsonPrimitive.boolean,
            )
            val callbackUrl = payments.data().text("callbackUrl")
            assertTrue(
                Regex("https://api\\.test/v1/hooks/payhero/crynce-[0-9A-Za-z]{16}").matches(callbackUrl),
                callbackUrl,
            )
            assertFalse("api-pass" in payments.bodyAsText())

            val online = client.post("$base/session") { json("""{"pin":"482913","minutes":240}""", session) }
            assertTrue(
                online
                    .data()
                    .getValue("online")
                    .jsonPrimitive.boolean,
            )
            assertTrue(online.data().text("until").endsWith("Z"))

            val account = client.get(base) { header("X-Merchant-Session", session) }.data()
            assertEquals("APPROVED", account.text("state"))
            assertEquals(
                """{"blockchain":"BASE-SEPOLIA","address":"0xw-$id-BASE-SEPOLIA"}""",
                account.getValue("wallet").toString(),
            )
            val float = account.getValue("float").jsonObject
            assertEquals(Triple("USDC", "100000000", "100"), float.getValue("balance").amount())
            assertEquals(Triple("USDC", "0", "0"), float.getValue("reserved").amount())
            assertEquals(Triple("USDC", "100000000", "100"), float.getValue("free").amount())
            assertEquals(
                """{"provider":"payhero","connected":true,"channelId":"911","callbackUrl":"$callbackUrl"}""",
                account.getValue("payments").toString(),
            )
            assertTrue(account.getValue("pinSet").jsonPrimitive.boolean)
            assertTrue(account.getValue("canGoOnline").jsonPrimitive.boolean)
            assertEquals("[]", account.getValue("missing").toString())
            assertEquals(online.data().toString(), account.getValue("online").toString())
            assertFalse("api-pass" in account.toString() || "api-user" in account.toString())

            val rotated = client.post("$base/payments/callback") { json("{}", session) }.data().text("callbackUrl")
            assertTrue(rotated != callbackUrl && rotated.startsWith("https://api.test/v1/hooks/payhero/crynce-"))

            val changed = client.put("$base/pin") { json("""{"currentPin":"482913","newPin":"771204"}""", session) }
            assertEquals("{}", changed.data().toString())

            val offline = client.delete("$base/session") { header("X-Merchant-Session", session) }
            assertEquals(
                """{"online":false,"until":null,"state":"OFFLINE","openEnded":false,"keysHeld":false,""" +
                    """"since":null,"offlineReason":"STOPPED","offlineAt":"${ramp.clock.instant()}"}""",
                offline.data().toString(),
            )

            // on mainnet the same account has no wallet: the header picks the network
            val mainnet =
                client
                    .get(base) {
                        header("X-Merchant-Session", session)
                        header("X-Wallet-Network", "MAINNET")
                    }.data()
            assertEquals("MAINNET", mainnet.text("network"))
            assertEquals(JsonNull, mainnet.getValue("wallet"))
        }

    @Test
    fun `going online until stopped answers no end time, and RM2 says why a restart took the merchant offline`() =
        testApplication {
            serve()
            val session = signIn()
            ramp.merchants.rows.remove(ramp.crynce.merchantId)
            client.put("$base/profile") { json("""{"displayName":"Crynce","contactPhone":"+254712000111"}""", session) }
            val id = ramp.merchants.byEmail("pay@crynce.co.ke")!!.merchantId
            ramp.decideMerchant(id, approve = true, by = "staff")
            ramp.release()
            ramp.treasury.balances[ramp.merchantWallets.find(id, TESTNET)!!.custodyWalletId] = 100_000_000
            client.put("$base/payments") {
                json("""{"username":"api-user","password":"api-pass","channelId":"911","pin":"482913"}""", session)
            }
            val now = ramp.clock.instant()

            val online = client.post("$base/session") { json("""{"pin":"482913","untilStopped":true}""", session) }

            assertEquals(HttpStatusCode.OK, online.status)
            val expected =
                """{"online":true,"until":null,"state":"ONLINE","openEnded":true,"keysHeld":true,""" +
                    """"since":"$now","offlineReason":null,"offlineAt":null}"""
            assertEquals(expected, online.data().toString())
            ramp.clock.advance(Duration.ofDays(3).seconds)
            ramp.endSessions()
            val portal = signIn() // the portal's own sign-in lasts 12 hours; the trading session doesn't end with it
            val later = client.get(base) { header("X-Merchant-Session", portal) }.data()
            assertEquals(expected, later.getValue("online").toString(), "no end time: days later it is still on")

            ramp.sessions.restart()

            val restarted = client.get(base) { header("X-Merchant-Session", portal) }.data().getValue("online")
            assertEquals(
                """{"online":false,"until":null,"state":"OFFLINE","openEnded":false,"keysHeld":false,""" +
                    """"since":null,"offlineReason":"RESTART","offlineAt":null}""",
                restarted.toString(),
                "at once, before the sweep",
            )
            ramp.endSessions()
            val swept = client.get(base) { header("X-Merchant-Session", portal) }.data().getValue("online")
            assertEquals(
                """{"online":false,"until":null,"state":"OFFLINE","openEnded":false,"keysHeld":false,""" +
                    """"since":null,"offlineReason":"RESTART","offlineAt":"${ramp.clock.instant()}"}""",
                swept.toString(),
            )
        }

    @Test
    fun `the identity check answers the hosted page's link, and RM2's kyc is what the portal shows after it`() =
        testApplication {
            serve()
            ramp.requireKyc()
            val session = signIn("pay@juma.co.ke")

            val early = client.post("$base/kyc") { json("{}", session) }
            assertEquals(HttpStatusCode.Conflict, early.status, "a profile comes first")
            assertEquals("ramp.merchant_setup_incomplete", early.envelope().text("code"))

            val profile = """{"displayName":"Juma","contactPhone":"+254711000111"}"""
            val applied = client.put("$base/profile") { json(profile, session) }.data()
            assertEquals(
                """{"profile":true,"kyc":false,"approved":false,"wallet":false,"payments":false}""",
                applied.getValue("steps").toString(),
            )
            assertEquals("""["kyc","approval","wallet","payments"]""", applied.getValue("missing").toString())
            assertEquals(
                """{"status":"NONE","required":true,"canStart":true,"reason":null,"retryAt":null,"submitted":false}""",
                applied.getValue("kyc").toString(),
            )

            val started = client.post("$base/kyc") { json("{}", session) }
            assertEquals(HttpStatusCode.OK, started.status)
            val expires = ramp.clock.instant().plusSeconds(24 * 3_600)
            assertEquals(
                """{"status":"PENDING","required":true,"canStart":true,"reason":null,"retryAt":null,""" +
                    """"submitted":false,"session":{"sessionToken":"token-1",""" +
                    """"url":"https://verify.test/session/token-1",""" +
                    """"expiresAt":"$expires"}}""",
                started.data().toString(),
            )
            assertEquals(
                "https://merchants.circaso.com/kyc/done",
                ramp.didit.started
                    .single()
                    .callbackUrl,
            )
            val again = client.post("$base/kyc") { json("{}", session) }.data()
            assertEquals(started.data().getValue("session"), again.getValue("session"), "the same open check")

            // The hosted page sends the merchant back; the provider has approved; the portal reads RM2.
            val merchantId = MerchantId.parse(applied.text("merchantId"))!!
            ramp.didit.approve(ramp.sessionOf(Subject.merchant(merchantId)))
            ramp.clock.advance(6)
            val account = client.get(base) { header("X-Merchant-Session", session) }.data()
            assertEquals(
                """{"status":"VERIFIED","required":true,"canStart":false,"reason":null,"retryAt":null,"submitted":false}""",
                account.getValue("kyc").toString(),
            )
            assertEquals("""["approval","wallet","payments"]""", account.getValue("missing").toString())
            assertTrue(
                account
                    .getValue("steps")
                    .jsonObject
                    .getValue("kyc")
                    .jsonPrimitive.boolean,
            )

            val done = client.post("$base/kyc") { json("{}", session) }.data()
            assertEquals("VERIFIED", done.text("status"))
            assertEquals(JsonNull, done.getValue("session"), "nothing left to do")
        }

    @Test
    fun `a wrong PIN is refused, and the fifth locks with how long to wait`() =
        testApplication {
            serve()
            val session = signIn()
            val id = ramp.merchants.byEmail("pay@crynce.co.ke")!!.merchantId
            client.put("$base/profile") { json("""{"displayName":"Crynce","contactPhone":"+254712000111"}""", session) }
            ramp.decideMerchant(id, approve = true, by = "staff")
            ramp.release()
            ramp.treasury.balances[ramp.merchantWallets.find(id, TESTNET)!!.custodyWalletId] = 100_000_000
            client.put("$base/payments") {
                json("""{"username":"api-user","password":"api-pass","channelId":"911","pin":"482913"}""", session)
            }

            repeat(4) {
                val wrong = client.post("$base/session") { json("""{"pin":"000000","minutes":60}""", session) }
                assertEquals(
                    HttpStatusCode.BadRequest to "ramp.pin_invalid",
                    wrong.status to wrong.envelope().text("code"),
                )
            }
            val locked = client.post("$base/session") { json("""{"pin":"000000","minutes":60}""", session) }

            assertEquals(HttpStatusCode.TooManyRequests, locked.status)
            assertEquals("ramp.pin_locked", locked.envelope().text("code"))
            assertEquals(
                1_800,
                locked
                    .envelope()
                    .getValue("details")
                    .jsonObject
                    .getValue("retryAfterSeconds")
                    .jsonPrimitive.int,
            )
            assertEquals("1800", locked.headers["Retry-After"])
        }

    @Test
    fun `a validation error names the field as the request spells it`() =
        testApplication {
            serve()
            val session = signIn()

            suspend fun refused(response: HttpResponse, field: String) {
                assertEquals(HttpStatusCode.BadRequest, response.status, field)
                assertEquals("request.malformed", response.envelope().text("code"), field)
                assertEquals(field, response.field())
            }
            refused(client.post("$base/auth/code") { json("""{"email":"not-an-email"}""") }, "email")
            refused(client.post("$base/auth/verify") { json("""{"email":"nope","code":"123456"}""") }, "email")
            refused(client.post("$base/auth/verify") { json("""{"email":"a@b.co","code":"12345"}""") }, "code")
            refused(
                client.put("$base/profile") {
                    json("""{"displayName":"C","contactPhone":"+254712000111"}""", session)
                },
                "displayName",
            )
            refused(
                client.put("$base/profile") {
                    json("""{"displayName":"Crynce","contactPhone":"0712"}""", session)
                },
                "contactPhone",
            )
            val account = """"password":"p","channelId":"911","pin":"482913""""
            refused(client.put("$base/payments") { json("""{"username":" ",$account}""", session) }, "username")
            refused(
                client.put("$base/payments") {
                    json("""{"username":"u","password":"","channelId":"1","pin":"482913"}""", session)
                },
                "password",
            )
            refused(
                client.put("$base/payments") {
                    json("""{"username":"u","password":"p","channelId":"","pin":"482913"}""", session)
                },
                "channelId",
            )
            refused(
                client.put("$base/payments") {
                    json("""{"username":"u","password":"p","channelId":"1","pin":"4829"}""", session)
                },
                "pin",
            )
            refused(client.post("$base/session") { json("""{"pin":"48291x","minutes":60}""", session) }, "pin")
            refused(client.post("$base/session") { json("""{"pin":"482913","minutes":14}""", session) }, "minutes")
            refused(client.post("$base/session") { json("""{"pin":"482913","minutes":721}""", session) }, "minutes")
            refused(client.post("$base/session") { json("""{"pin":"482913"}""", session) }, "minutes")
            refused(
                client.post("$base/session") { json("""{"pin":"482913","minutes":60,"untilStopped":true}""", session) },
                "minutes",
            )
            refused(client.put("$base/pin") { json("""{"currentPin":"1","newPin":"771204"}""", session) }, "currentPin")
            refused(client.put("$base/pin") { json("""{"currentPin":"482913","newPin":"7712"}""", session) }, "newPin")
            refused(client.get("$base/earnings?from=2026-11-01") { header("X-Merchant-Session", session) }, "to")
            refused(client.get("$base/earnings?from=x&to=2026-11-30") { header("X-Merchant-Session", session) }, "from")
            refused(
                client.get("$base/statement?from=2026-11-30&to=2026-11-01") { header("X-Merchant-Session", session) },
                "to",
            )
        }

    @Test
    fun `orders, earnings and the statement are the portal's shapes, every amount with its unit`() =
        testApplication {
            serve()
            val session = signIn("crynce@example.com")
            val buyers = listOf("+254700000002", "+254700000003", "+254700000004")
            buyers.forEach {
                ramp.completed(ramp.user(it), PhoneNumber.parse(it)!!)
                ramp.clock.advance(60)
            }
            ramp.settle()
            ramp.treasury.settleAll()
            ramp.settle()
            ramp.prompted()

            suspend fun read(path: String) = client.get("$base$path") { header("X-Merchant-Session", session) }.data()

            val orders = read("/orders?limit=3")
            val first =
                orders
                    .getValue("orders")
                    .jsonArray
                    .first()
                    .jsonObject
            assertEquals(
                setOf(
                    "orderId",
                    "direction",
                    "state",
                    "pay",
                    "receive",
                    "fee",
                    "maskedPhone",
                    "createdAt",
                    "updatedAt",
                ),
                first.keys,
            )
            assertEquals("ON_RAMP" to "PAYMENT_REQUESTED", first.text("direction") to first.text("state"))
            assertEquals(Triple("KES", "130000", "1300"), first.getValue("pay").amount())
            assertEquals(Triple("USDC", "9800000", "9.8"), first.getValue("receive").amount())
            assertEquals(Triple("KES", "2600", "26"), first.getValue("fee").amount())
            assertFalse("254712345678" in orders.toString(), "the payer's number is masked")
            val next = orders.text("next")
            val rest = read("/orders?limit=3&cursor=$next")
            assertEquals(1, rest.getValue("orders").jsonArray.size)
            assertEquals(JsonNull, rest.getValue("next"))
            assertEquals(4, read("/orders").getValue("orders").jsonArray.size, "20 a page by default")

            val earnings = read("/earnings?from=2026-11-01&to=2026-11-30")
            assertEquals(
                Triple("2026-11-01", "2026-11-30", "TESTNET"),
                Triple(earnings.text("from"), earnings.text("to"), earnings.text("network")),
            )
            assertEquals(3, earnings.getValue("orders").jsonPrimitive.int)
            assertEquals(Triple("KES", "390000", "3900"), earnings.getValue("collected").amount())
            assertEquals(Triple("USDC", "29400000", "29.4"), earnings.getValue("sold").amount())
            assertEquals(Triple("KES", "7800", "78"), earnings.getValue("fees").amount())
            assertEquals(Triple("KES", "5460", "54.6"), earnings.getValue("yourShare").amount())
            assertEquals(Triple("KES", "2340", "23.4"), earnings.getValue("circasoShare").amount())
            assertEquals(Triple("USDC", "180000", "0.18"), earnings.getValue("circasoShareUsdc").amount())
            assertEquals(Triple("USDC", "180000", "0.18"), earnings.getValue("swept").amount())
            assertEquals(Triple("USDC", "0", "0"), earnings.getValue("held").amount())
            assertEquals("2" to "70", earnings.text("feeRatePercent") to earnings.text("yourSharePercent"))
            val sweep =
                earnings
                    .getValue("sweeps")
                    .jsonArray
                    .single()
                    .jsonObject
            assertEquals(setOf("settlementId", "day", "amount", "state", "orders", "txHash"), sweep.keys)
            assertEquals(
                Triple("2026-11-21", "CONFIRMED", 3),
                Triple(sweep.text("day"), sweep.text("state"), sweep.getValue("orders").jsonPrimitive.int),
            )
            assertEquals(Triple("USDC", "180000", "0.18"), sweep.getValue("amount").amount())
            assertTrue(sweep.text("txHash").startsWith("0x"))
            assertEquals(earnings.text("from").take(7), read("/earnings").text("from").take(7), "this month by default")

            val statement = read("/statement?from=2026-11-01&to=2026-11-30&limit=2")
            val line =
                statement
                    .getValue("lines")
                    .jsonArray
                    .first()
                    .jsonObject
            assertEquals(
                setOf(
                    "orderId",
                    "completedAt",
                    "maskedPhone",
                    "paid",
                    "released",
                    "rate",
                    "fee",
                    "yourShare",
                    "circasoShare",
                    "circasoShareUsdc",
                    "settlementId",
                    "txHash",
                ),
                line.keys,
            )
            assertEquals(Triple("KES", "130000", "1300"), line.getValue("paid").amount())
            assertEquals(Triple("USDC", "9800000", "9.8"), line.getValue("released").amount())
            assertEquals("130", line.text("rate"))
            assertEquals(Triple("KES", "1820", "18.2"), line.getValue("yourShare").amount())
            assertEquals(Triple("KES", "780", "7.8"), line.getValue("circasoShare").amount())
            assertEquals(Triple("USDC", "60000", "0.06"), line.getValue("circasoShareUsdc").amount())
            assertEquals(sweep.text("settlementId"), line.text("settlementId"))
            val totals = statement.getValue("totals").jsonObject
            assertEquals(
                setOf("orders", "collected", "sold", "fees", "yourShare", "circasoShare", "circasoShareUsdc"),
                totals.keys,
            )
            listOf("collected", "sold", "fees", "yourShare", "circasoShare", "circasoShareUsdc").forEach {
                assertEquals(earnings.getValue(it), totals.getValue(it), "the overview and the statement agree on $it")
            }
            val more = read("/statement?from=2026-11-01&to=2026-11-30&limit=2&cursor=${statement.text("next")}")
            assertEquals(1, more.getValue("lines").jsonArray.size)
            assertEquals(JsonNull, more.getValue("next"))
            assertEquals(totals, more.getValue("totals").jsonObject)
        }

    @Test
    fun `how you are doing and which orders you take - your own figures, in the shapes the portal reads`() =
        testApplication {
            serve()
            val session = signIn("crynce@example.com")
            // two completed (12 and 40 seconds from the confirmed payment to the USDC arriving), one the buyer let
            // lapse, one the merchant's provider refused
            listOf("+254700000002" to 12L, "+254700000003" to 40L).forEach { (number, seconds) ->
                ramp.paid(ramp.user(number), PhoneNumber.parse(number)!!)
                ramp.release()
                ramp.treasury.settleAll()
                ramp.clock.advance(seconds)
                ramp.release()
            }
            val lapsed = ramp.prompted(ramp.user("+254700000004"), PhoneNumber.parse("+254700000004")!!)
            ramp.collections.fail(lapsed)
            ramp.callback(lapsed)
            ramp.collections.refusals["Crynce-user"] = IntegrationError.Rejected("The channel is not active.")
            ramp.buy(ramp.user("+254700000005"), PhoneNumber.parse("+254700000005")!!)
            ramp.collections.refusals.clear()

            val performance = client.get("$base/performance") { header("X-Merchant-Session", session) }

            assertEquals(HttpStatusCode.OK, performance.status)
            val data = performance.data()
            assertEquals(setOf("network", "merchantType", "directions"), data.keys, "nothing about anybody else")
            assertEquals("TESTNET" to "INDIVIDUAL", data.text("network") to data.text("merchantType"))
            val directions = data.getValue("directions").jsonArray
            assertEquals(
                """{"direction":"ON_RAMP","offered":true,""" +
                    """"orderLimit":{"unit":"KES","amountMinor":"3000000","amount":"30000"},""" +
                    """"window":{"orders":50,"days":30},"finished":4,"counted":3,"completed":2,"failed":1,""" +
                    """"notCounted":1,"completionRatePercent":"66.7","failureRatePercent":"33.3",""" +
                    """"medianSeconds":12,"p90Seconds":40,"targetSeconds":300,"slaSeconds":900,""" +
                    """"speed":"ON_TARGET","trades":2,"newcomer":{"applies":true,"until":20,"tradesLeft":18}}""",
                directions[0].toString(),
                "the buyer who let the prompt lapse is in notCounted and moved neither rate",
            )
            assertEquals(
                """{"direction":"OFF_RAMP","offered":false,""" +
                    """"orderLimit":{"unit":"KES","amountMinor":"3000000","amount":"30000"},""" +
                    """"window":{"orders":50,"days":30},"finished":0,"counted":0,"completed":0,"failed":0,""" +
                    """"notCounted":0,"completionRatePercent":null,"failureRatePercent":null,""" +
                    """"medianSeconds":null,"p90Seconds":null,"targetSeconds":300,"slaSeconds":900,""" +
                    """"speed":"NO_DATA","trades":2,"newcomer":{"applies":true,"until":20,"tradesLeft":18}}""",
                directions[1].toString(),
            )

            // RM2 says what the merchant is and offers; RM17 changes the switches, one at a time or both
            val account = client.get(base) { header("X-Merchant-Session", session) }.data()
            assertEquals("INDIVIDUAL", account.text("merchantType"))
            assertEquals("""{"onRamp":true,"offRamp":false}""", account.getValue("offers").toString())

            val selling = client.put("$base/offers") { json("""{"offRamp":true}""", session) }
            assertEquals(HttpStatusCode.OK, selling.status, selling.bodyAsText())
            assertEquals("""{"onRamp":true,"offRamp":true}""", selling.data().getValue("offers").toString())
            val paused = client.put("$base/offers") { json("""{"onRamp":false,"offRamp":false}""", session) }
            assertEquals("""{"onRamp":false,"offRamp":false}""", paused.data().getValue("offers").toString())
            assertFalse(ramp.merchants.find(ramp.crynce.merchantId)!!.offersOnramp)
            val after = client.get("$base/performance") { header("X-Merchant-Session", session) }.data()
            assertFalse(
                after
                    .getValue("directions")
                    .jsonArray[0]
                    .jsonObject
                    .getValue("offered")
                    .jsonPrimitive.boolean,
            )

            val nothing = client.put("$base/offers") { json("{}", session) }
            assertEquals(HttpStatusCode.BadRequest, nothing.status)
            assertEquals("onRamp", nothing.field())
            assertEquals(HttpStatusCode.Unauthorized, client.get("$base/performance").status)
            assertEquals(HttpStatusCode.Unauthorized, client.put("$base/offers") { json("""{"onRamp":true}""") }.status)
        }

    // ---- selling (2026-11-23) ----

    @Test
    fun `RM2's off-ramp card reads the balances live while online and flags a low payout balance`() =
        testApplication {
            serve()
            ramp.openSelling(payoutMinor = 50_000)
            ramp.settingsRows.set(TESTNET) { it.copy(payoutBalanceLowMinor = 100_000) }
            ramp.book.invalidate()
            val session = signIn("crynce@example.com")

            val card =
                client
                    .get(base) { header("X-Merchant-Session", session) }
                    .data()
                    .getValue("offRamp")
                    .jsonObject

            assertEquals("4242", card.text("accountId"))
            assertEquals(
                Triple("KES", "50000", "500"),
                card
                    .getValue("payoutBalance")
                    .jsonObject
                    .getValue("amount")
                    .amount(),
            )
            assertEquals("payment_wallet", card.getValue("payoutBalance").jsonObject.text("source"))
            assertEquals("service_wallet", card.getValue("feeBalance").jsonObject.text("source"))
            assertEquals(true, card.getValue("balancesLive").jsonPrimitive.boolean)
            assertEquals(
                listOf("payout_balance_low"),
                card.getValue("flags").jsonArray.map { it.jsonPrimitive.content },
            )
            assertEquals(Triple("KES", "0", "0"), card.getValue("held").amount())
        }

    @Test
    fun `a new merchant's balances are unknown, never zero, and the payout account is digits`() =
        testApplication {
            serve()
            val session = signIn()

            val card =
                client
                    .get(base) { header("X-Merchant-Session", session) }
                    .data()
                    .getValue("offRamp")
                    .jsonObject
            assertEquals(JsonNull, card.getValue("payoutBalance"))
            assertEquals(JsonNull, card.getValue("feeBalance"))
            assertTrue("payout_account_missing" in card.getValue("flags").jsonArray.map { it.jsonPrimitive.content })
            client.put("$base/profile") { json("""{"displayName":"Bidii","contactPhone":"+254712000222"}""", session) }

            val wrong = client.put("$base/payments/account") { json("""{"accountId":"12ab"}""", session) }
            assertEquals(HttpStatusCode.BadRequest, wrong.status)
            assertEquals("ramp.payout_account_invalid", wrong.envelope().text("code"))
            val saved = client.put("$base/payments/account") { json("""{"accountId":" 777 "}""", session) }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            assertEquals(
                "777",
                saved
                    .data()
                    .getValue("offRamp")
                    .jsonObject
                    .text("accountId"),
            )
        }

    @Test
    fun `RM18 sends a test payout to the merchant's own phone and reads its outcome`() =
        testApplication {
            serve()
            ramp.openSelling()
            val session = signIn("crynce@example.com")

            val malformed = client.post("$base/test-payout") { json("""{"amountMinor":"ten"}""", session) }
            assertEquals(HttpStatusCode.BadRequest, malformed.status)
            val tooBig = client.post("$base/test-payout") { json("""{"amountMinor":"20000"}""", session) }
            assertEquals("ramp.amount_invalid", tooBig.envelope().text("code"))

            val sent = client.post("$base/test-payout") { json("""{"amountMinor":"1000"}""", session) }
            assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
            val test = sent.data()
            assertEquals("ACCEPTED", test.text("state"))
            assertEquals("+254 7•• ••• 000", test.text("maskedPhone"))
            assertEquals(Triple("KES", "1000", "10"), test.getValue("amount").amount())
            assertEquals(
                PhoneNumber.parse("+254700000000"),
                ramp.collections.payouts
                    .single()
                    .payee,
            )
            val again = client.post("$base/test-payout") { json("""{"amountMinor":"1000"}""", session) }
            assertEquals(HttpStatusCode.Conflict, again.status)
            assertEquals("ramp.test_payout_in_progress", again.envelope().text("code"))

            ramp.collections.payoutPaid(test.text("testId"), receipt = "QPROUTE")
            ramp.clock.advance(4)
            val read = client.get("$base/test-payout") { header("X-Merchant-Session", session) }.data()
            assertEquals("PAID" to "QPROUTE", read.text("state") to read.text("receipt"))
            val unknown = client.get("$base/test-payout?testId=nope") { header("X-Merchant-Session", session) }
            assertEquals(HttpStatusCode.BadRequest, unknown.status)
        }

    @Test
    fun `RM19 tops up a wallet with a prompt to the merchant's own phone and reads it until it is confirmed`() =
        testApplication {
            serve()
            ramp.collections.serviceBalances["Crynce-user"] = 5_000
            ramp.collections.payoutBalances["Crynce-user"] = 0
            val session = signIn("crynce@example.com")

            val wrongWallet =
                client.post(
                    "$base/topups",
                ) { json("""{"wallet":"FLOAT","amountMinor":"50000"}""", session) }
            assertEquals(HttpStatusCode.BadRequest, wrongWallet.status)
            assertEquals("wallet", wrongWallet.field())
            val shortPin =
                client.post(
                    "$base/topups",
                ) { json("""{"wallet":"SERVICE","amountMinor":"50000","pin":"12"}""", session) }
            assertEquals("pin", shortPin.field())
            val tooSmall = client.post("$base/topups") { json("""{"wallet":"SERVICE","amountMinor":"500"}""", session) }
            assertEquals("ramp.amount_invalid" to "amountMinor", tooSmall.envelope().text("code") to tooSmall.field())
            val noAccount =
                client.post(
                    "$base/topups",
                ) { json("""{"wallet":"PAYMENT","amountMinor":"50000"}""", session) }
            assertEquals(
                "ramp.merchant_setup_incomplete" to "accountId",
                noAccount.envelope().text("code") to noAccount.field(),
            )

            val sent = client.post("$base/topups") { json("""{"wallet":"SERVICE","amountMinor":"50000"}""", session) }
            assertEquals(HttpStatusCode.OK, sent.status, sent.bodyAsText())
            val topUp = sent.data()
            assertEquals("REQUESTED" to "SERVICE", topUp.text("state") to topUp.text("wallet"))
            assertEquals("+254 7•• ••• 000", topUp.text("maskedPhone"))
            assertEquals(Triple("KES", "50000", "500"), topUp.getValue("amount").amount())
            assertEquals(Triple("KES", "5000", "50"), topUp.getValue("balanceBefore").amount())
            assertEquals(JsonNull, topUp["failure"])
            val again = client.post("$base/topups") { json("""{"wallet":"SERVICE","amountMinor":"50000"}""", session) }
            assertEquals(
                HttpStatusCode.Conflict to "ramp.topup_in_progress",
                again.status to again.envelope().text("code"),
            )

            val id = topUp.text("topupId")
            ramp.collections.topUpPaid(id, "Crynce-user")
            ramp.clock.advance(4)
            val read = client.get("$base/topups/$id") { header("X-Merchant-Session", session) }.data()
            assertEquals("CONFIRMED" to "STATUS", read.text("state") to read.text("confirmedBy"))
            assertEquals(Triple("KES", "55000", "550"), read.getValue("balanceAfter").amount())
            assertEquals(Triple("KES", "50000", "500"), read.getValue("added").amount())

            val list = client.get("$base/topups") { header("X-Merchant-Session", session) }.data()
            assertEquals(
                id,
                list
                    .getValue("topups")
                    .jsonArray
                    .single()
                    .jsonObject
                    .text("topupId"),
            )
            assertEquals(
                Triple("KES", "1000", "10"),
                list
                    .getValue("limits")
                    .jsonObject
                    .getValue("min")
                    .amount(),
            )
            assertEquals(
                Triple("KES", "15000000", "150000"),
                list
                    .getValue("limits")
                    .jsonObject
                    .getValue("max")
                    .amount(),
            )
            val bad = client.get("$base/topups/nope") { header("X-Merchant-Session", session) }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
            val missing =
                client.get(
                    "$base/topups/${java.util.UUID.randomUUID()}",
                ) { header("X-Merchant-Session", session) }
            assertEquals(
                HttpStatusCode.NotFound to "ramp.topup_not_found",
                missing.status to missing.envelope().text("code"),
            )
        }

    @Test
    fun `RM19 offline asks for the PIN on field pin`() =
        testApplication {
            serve()
            ramp.sessions.restart()
            val session = signIn("crynce@example.com")

            val offline =
                client.post(
                    "$base/topups",
                ) { json("""{"wallet":"SERVICE","amountMinor":"50000"}""", session) }

            assertEquals(HttpStatusCode.Conflict, offline.status)
            assertEquals("ramp.merchant_offline" to "pin", offline.envelope().text("code") to offline.field())
        }

    @Test
    fun `RM5c sets, replaces and removes the webhook signing secret and never sends it back`() =
        testApplication {
            serve()
            val secret = "whsec_Zr8Lq2Vx7Tn4Kp9W"
            val session = signIn("crynce@example.com")
            val path = "$base/payments/webhook-secret"

            listOf("" to "required", "short" to "8 to 512", "has a space" to "8 to 512").forEach { (value, _) ->
                val refused = client.put(path) { json("""{"secret":"$value"}""", session) }
                assertEquals(HttpStatusCode.BadRequest, refused.status, value)
                assertEquals("secret", refused.field())
            }
            assertEquals(
                HttpStatusCode.Unauthorized,
                client.put(path) { json("""{"secret":"$secret"}""") }.status,
            )

            val saved = client.put(path) { json("""{"secret":" $secret "}""", session) }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            val text = saved.bodyAsText()
            assertFalse(secret in text, "the secret is never returned")
            assertTrue(
                saved
                    .data()
                    .getValue("webhookSecretSet")
                    .jsonPrimitive.boolean,
            )
            assertEquals(secret, ramp.callbackSigningSecret(ramp.crynce.callbackKey!!))

            val read = client.get(base) { header("X-Merchant-Session", session) }
            assertFalse(secret in read.bodyAsText())
            assertEquals(ramp.clock.instant().toString(), read.data().text("webhookSecretUpdatedAt"))

            val removed = client.delete(path) { header("X-Merchant-Session", session) }
            assertEquals(HttpStatusCode.OK, removed.status)
            assertFalse(
                removed
                    .data()
                    .getValue("webhookSecretSet")
                    .jsonPrimitive.boolean,
            )
            assertEquals(JsonNull, removed.data().getValue("webhookSecretUpdatedAt"))
        }

    @Test
    fun `RM5c needs the callback address first`() =
        testApplication {
            serve()
            val session = signIn()
            client.put("$base/profile") { json("""{"displayName":"Bidii","contactPhone":"+254712000222"}""", session) }

            val refused =
                client.put("$base/payments/webhook-secret") { json("""{"secret":"whsec_Zr8Lq2Vx7Tn4Kp9W"}""", session) }
            assertEquals("ramp.merchant_setup_incomplete", refused.envelope().text("code"))
        }
}
