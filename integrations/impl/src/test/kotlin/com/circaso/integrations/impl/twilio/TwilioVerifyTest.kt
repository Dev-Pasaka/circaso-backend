package com.circaso.integrations.impl.twilio

import com.circaso.integrations.api.CodeCheck
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.form
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class TwilioVerifyTest {
    private var status = 200
    private var reply = """{"sid":"VE1","status":"pending","valid":false}"""
    private var down = false

    private val clients = FakeHttpClients { _ -> if (down) throw IOException("down") else json(reply, status) }
    private val phone = PhoneNumber.parse("+254712345678")!!

    private fun twilio(locale: String? = null) =
        TwilioVerify(
            "ACabc",
            "secret-token",
            "VAservice",
            clients.named("twilio") {
                baseUrl =
                    "https://verify.twilio.com"
            },
            locale,
        )

    @Test
    fun `starting asks Twilio to text the number, with the service and the account's credentials`() =
        runBlocking<Unit> {
            assertIs<Resource.Success<*>>(twilio("sw").start(phone, "AbC123hash"))
            val request = clients.last
            assertEquals("https://verify.twilio.com/v2/Services/VAservice/Verifications", request.url.toString())
            assertEquals(
                "Basic " + Base64.getEncoder().encodeToString("ACabc:secret-token".toByteArray()),
                request.headers[HttpHeaders.Authorization],
            )
            assertEquals(
                mapOf("To" to "+254712345678", "Channel" to "sms", "Locale" to "sw", "AppHash" to "AbC123hash"),
                request.form(),
            )
            twilio().start(phone)
            assertNull(clients.last.form()["Locale"], "no locale unless configured")
            assertEquals(listOf("twilio", "twilio"), clients.names, "through the shared client named for the vendor")
        }

    @Test
    fun `a right code is approved, a wrong one is denied, and nothing pending is expired`() =
        runBlocking<Unit> {
            reply = """{"sid":"VE1","status":"approved","valid":true}"""
            assertEquals(CodeCheck.APPROVED, (twilio().check(phone, "123456") as Resource.Success).data)
            assertEquals(
                "https://verify.twilio.com/v2/Services/VAservice/VerificationCheck",
                clients.last.url.toString(),
            )
            assertEquals("123456", clients.last.form()["Code"])

            reply = """{"sid":"VE1","status":"pending","valid":false}"""
            assertEquals(CodeCheck.DENIED, (twilio().check(phone, "000000") as Resource.Success).data)

            status = 404
            reply = """{"code":20404,"message":"not found"}"""
            assertEquals(CodeCheck.EXPIRED, (twilio().check(phone, "123456") as Resource.Success).data)
        }

    @Test
    fun `Twilio's own refusals are told apart`() =
        runBlocking<Unit> {
            status = 400
            reply = """{"code":60200,"message":"Invalid parameter"}"""
            assertIs<IntegrationError.Rejected>((twilio().start(phone) as Resource.Error).error)
            status = 429
            assertIs<SharedError.RateLimited>((twilio().start(phone) as Resource.Error).error)
            for (code in listOf(401, 403, 500, 503)) {
                status = code
                assertIs<IntegrationError.Unavailable>((twilio().start(phone) as Resource.Error).error, "status $code")
            }
            status = 200
            reply = "not json"
            assertIs<IntegrationError.Unavailable>((twilio().check(phone, "1") as Resource.Error).error)
            down = true
            assertIs<IntegrationError.Unavailable>((twilio().start(phone) as Resource.Error).error)
        }
}
