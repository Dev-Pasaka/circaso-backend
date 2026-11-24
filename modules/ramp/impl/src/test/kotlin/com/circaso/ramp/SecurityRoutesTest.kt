package com.circaso.ramp

import com.circaso.integrations.api.Email
import com.circaso.ramp.domain.models.AlertKind
import com.circaso.ramp.domain.usecases.AddWithdrawalDestination
import com.circaso.ramp.domain.usecases.GetWithdrawal
import com.circaso.ramp.domain.usecases.ListWithdrawalDestinations
import com.circaso.ramp.domain.usecases.ListWithdrawals
import com.circaso.ramp.domain.usecases.RemoveWithdrawalDestination
import com.circaso.ramp.domain.usecases.WithdrawFloat
import com.circaso.ramp.presentation.controllers.merchantController
import com.circaso.ramp.presentation.services.MerchantAuthService
import com.circaso.ramp.presentation.services.MerchantService
import com.circaso.ramp.presentation.services.WithdrawalService
import com.circaso.web.installJson
import com.circaso.web.installRequestId
import com.circaso.web.installStatusPages
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
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The portal's security routes over HTTP (2026-11-24): adding a passkey, signing in with it, the step-up on a
 * withdrawal address, email choices, and the lock link, with the JSON shapes the portal reads.
 */
class SecurityRoutesTest {
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
            com.circaso.ramp.domain.usecases
                .StartTestPayout(ramp.testPayouts),
            com.circaso.ramp.domain.usecases
                .GetTestPayout(ramp.testPayouts),
            ramp.setWebhookSecret,
            com.circaso.ramp.domain.usecases
                .StartTopUp(ramp.topUps),
            com.circaso.ramp.domain.usecases
                .GetTopUp(ramp.topUps),
            com.circaso.ramp.domain.usecases
                .ListTopUps(ramp.topUps),
        )
    private val withdrawals =
        WithdrawalService(
            ListWithdrawalDestinations(ramp.floatWithdrawals),
            AddWithdrawalDestination(ramp.floatWithdrawals),
            RemoveWithdrawalDestination(ramp.floatWithdrawals),
            WithdrawFloat(ramp.floatWithdrawals),
            ListWithdrawals(ramp.floatWithdrawals),
            GetWithdrawal(ramp.floatWithdrawals),
        )
    private val base = "/v1/ramp/merchant"
    private val key = TestAuthenticator()
    private val address = "0x5aAeb6053F3E94C9b9A09f33669435E7Ef1BeAed"

    private fun ApplicationTestBuilder.serve() {
        application {
            installRequestId()
            installJson()
            installStatusPages()
            routing {
                route("/v1") { merchantController(auth, merchants, withdrawals, ramp.securityService()) }
            }
        }
    }

    private fun HttpRequestBuilder.json(body: String, session: String? = null) {
        session?.let { header("X-Merchant-Session", it) }
        header("X-Wallet-Network", "TESTNET")
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.envelope(): JsonObject = Json.parseToJsonElement(bodyAsText()).jsonObject

    private suspend fun HttpResponse.data(): JsonObject = envelope().getValue("data").jsonObject

    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content

    /** Crynce signs in, sets a PIN (RM5) and adds a passkey over HTTP. */
    private suspend fun ApplicationTestBuilder.setUp(): String {
        val email = ramp.crynce.email
        client.post("$base/auth/code") { json("""{"email":"$email"}""") }
        val verify = """{"email":"$email","code":"${ramp.email.lastCode()}"}"""
        val session = client.post("$base/auth/verify") { json(verify) }.data().text("sessionToken")
        val payments = """{"username":"Crynce-user","password":"Crynce-pass","channelId":"911","pin":"482913"}"""
        assertEquals(HttpStatusCode.OK, client.put("$base/payments") { json(payments, session) }.status)

        ramp.clock.advance(61)
        val asked = client.post("$base/security/code") { json("""{"purpose":"ADD_PASSKEY"}""", session) }
        assertEquals(HttpStatusCode.OK, asked.status)
        val code =
            (
                ramp.email.sent
                    .last()
                    .second as Email.MerchantSecurityCode
            ).code
        val start = """{"label":"MacBook","code":"$code","pin":"482913"}"""
        val options = client.post("$base/security/passkeys/options") { json(start, session) }
        assertEquals(HttpStatusCode.OK, options.status)
        val publicKey = options.data().getValue("publicKey").jsonObject
        assertEquals("merchants.circaso.com", publicKey.getValue("rp").jsonObject.text("id"))
        assertEquals("required", publicKey.getValue("authenticatorSelection").jsonObject.text("userVerification"))
        val credential = key.create(publicKey.text("challenge"), publicKey.getValue("user").jsonObject.text("id"))
        val added =
            client.post("$base/security/passkeys") {
                val id = options.data().text("registrationId")
                json("""{"registrationId":"$id","credential":$credential}""", session)
            }
        assertEquals(HttpStatusCode.OK, added.status, added.bodyAsText())
        assertEquals("MacBook", added.data().text("label"))
        return session
    }

    @Test
    fun `a passkey is added, listed, signs in, and shows on RM2`() =
        testApplication {
            serve()
            val session = setUp()

            val list = client.get("$base/security/passkeys") { header("X-Merchant-Session", session) }.data()
            assertEquals(1, list.getValue("passkeys").jsonArray.size)
            assertEquals(false, list.getValue("required").jsonPrimitive.boolean)
            val rm2 = client.get(base) { header("X-Merchant-Session", session) }.data()
            val security = rm2.getValue("security").jsonObject
            assertEquals(1, security.getValue("passkeys").jsonPrimitive.int)
            assertEquals(false, security.getValue("withdrawalsLocked").jsonPrimitive.boolean)

            val options = client.post("$base/auth/passkey/options") { json("{}") }.data()
            val challenge = options.getValue("publicKey").jsonObject.text("challenge")
            val signedIn =
                client.post("$base/auth/passkey/verify") {
                    header("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) Chrome/131.0 Safari/537.36")
                    header("X-Client-Country", "KE")
                    header("X-Client-City", "Nairobi")
                    json("""{"challengeId":"${options.text("challengeId")}","credential":${key.get(challenge)}}""")
                }
            assertEquals(HttpStatusCode.OK, signedIn.status, signedIn.bodyAsText())
            assertTrue(signedIn.data().text("sessionToken").length >= 43)
            val alert =
                ramp.alertRows
                    .of(AlertKind.NEW_SIGN_IN)
                    .last()
                    .facts
            assertEquals("Chrome on macOS" to "Nairobi, Kenya (approximate)", alert.device to alert.location)
        }

    @Test
    fun `saving an address takes the step-up bound to it`() =
        testApplication {
            serve()
            val session = setUp()
            val body = """{"address":"$address","label":"Ledger","pin":"482913"}"""
            val refused = client.post("$base/withdrawal-addresses") { json(body, session) }
            assertEquals(HttpStatusCode.Forbidden, refused.status)
            assertEquals("ramp.step_up_required", refused.envelope().text("code"))

            val stepUp =
                client
                    .post("$base/security/step-up") {
                        json("""{"action":"ADD_ADDRESS","address":"$address","label":"Ledger"}""", session)
                    }.data()
            val challenge = stepUp.getValue("publicKey").jsonObject
            assertEquals(1, challenge.getValue("allowCredentials").jsonArray.size)
            val signed = key.get(challenge.text("challenge"))
            val proof = """{"challengeId":"${stepUp.text("challengeId")}","credential":$signed}"""
            val elsewhere = "0xab5801a7d398351b8be11c439e05c5b3259aec9b"
            val other = """{"address":"$elsewhere","label":"Ledger","pin":"482913","passkey":$proof}"""
            val swapped = client.post("$base/withdrawal-addresses") { json(other, session) }
            assertEquals("ramp.step_up_invalid", swapped.envelope().text("code"), "made for another address")

            val again =
                client
                    .post("$base/security/step-up") {
                        json("""{"action":"ADD_ADDRESS","address":"$address","label":"Ledger"}""", session)
                    }.data()
            val signed2 = key.get(again.getValue("publicKey").jsonObject.text("challenge"))
            val proof2 = """{"challengeId":"${again.text("challengeId")}","credential":$signed2}"""
            val body2 = """{"address":"$address","label":"Ledger","pin":"482913","passkey":$proof2}"""
            val saved = client.post("$base/withdrawal-addresses") { json(body2, session) }
            assertEquals(HttpStatusCode.OK, saved.status, saved.bodyAsText())
            assertEquals(
                false,
                saved
                    .data()
                    .getValue("frozen")
                    .jsonPrimitive.boolean,
            )
        }

    @Test
    fun `email choices change one at a time, and the lock link works without a session`() =
        testApplication {
            serve()
            val session = setUp()
            val choices = client.put("$base/notifications") { json("""{"dailySummary":true}""", session) }.data()

            fun flag(name: String) = choices.getValue(name).jsonPrimitive.boolean
            assertEquals(true to true, flag("orderEmails") to flag("dailySummary"))
            assertEquals(true, choices.getValue("securityAlways").jsonPrimitive.boolean)

            val token =
                ramp.lockLinks.token(
                    ramp.alertRows
                        .of(AlertKind.PASSKEY_ADDED)
                        .single()
                        .alertId,
                )!!
            val locked = client.post("$base/lock") { json("""{"token":"$token"}""") }.data()
            val wasUsed = locked.getValue("alreadyUsed").jsonPrimitive.boolean
            assertEquals(true to false, locked.getValue("locked").jsonPrimitive.boolean to wasUsed)
            val again = client.post("$base/lock") { json("""{"token":"$token"}""") }.data()
            assertEquals(true, again.getValue("alreadyUsed").jsonPrimitive.boolean)
            val bad = client.post("$base/lock") { json("""{"token":"nope"}""") }
            assertEquals("ramp.lock_link_invalid", bad.envelope().text("code"))
            val after = client.get(base) { header("X-Merchant-Session", session) }
            assertEquals(HttpStatusCode.Unauthorized, after.status, "signed out")
        }
}
