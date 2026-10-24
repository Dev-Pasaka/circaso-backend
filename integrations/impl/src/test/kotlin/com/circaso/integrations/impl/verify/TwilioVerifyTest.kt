package com.circaso.integrations.impl.verify

import com.circaso.integrations.api.CodeCheck
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.util.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TwilioVerifyTest {
    private var status = 200
    private var reply = """{"sid":"VE1","status":"pending","valid":false}"""
    private var path = ""
    private var authorization: String? = null
    private var form: Map<String, String> = emptyMap()

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/") { ex ->
            path = ex.requestURI.path
            authorization = ex.requestHeaders.getFirst("Authorization")
            form = ex.requestBody.readBytes().decodeToString().split('&').filter { it.isNotEmpty() }.associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val phone = PhoneNumber.parse("+254712345678")!!
    private fun twilio(locale: String? = null) = TwilioVerify("ACabc", "secret-token", "VAservice", locale, "http://127.0.0.1:${server.address.port}")

    @AfterTest
    fun tearDown() = server.stop(0)

    @Test
    fun `starting asks Twilio to text the number, with the service and the account's credentials`() = runBlocking {
        assertIs<Resource.Success<*>>(twilio("sw").start(phone, "AbC123hash"))
        assertEquals("/v2/Services/VAservice/Verifications", path)
        assertEquals("Basic " + Base64.getEncoder().encodeToString("ACabc:secret-token".toByteArray()), authorization)
        assertEquals("+254712345678", form["To"])
        assertEquals("sms", form["Channel"])
        assertEquals("sw", form["Locale"])
        assertEquals("AbC123hash", form["AppHash"])
        assertNull(twilio().also { it.start(phone) }.let { form["Locale"] }, "no locale unless configured")
    }

    @Test
    fun `a right code is approved, a wrong one is denied, and nothing pending is expired`() = runBlocking {
        reply = """{"sid":"VE1","status":"approved","valid":true}"""
        assertEquals(CodeCheck.APPROVED, (twilio().check(phone, "123456") as Resource.Success).data)
        assertEquals("/v2/Services/VAservice/VerificationCheck", path)
        assertEquals("123456", form["Code"])

        reply = """{"sid":"VE1","status":"pending","valid":false}"""
        assertEquals(CodeCheck.DENIED, (twilio().check(phone, "000000") as Resource.Success).data)

        status = 404
        reply = """{"code":20404,"message":"not found"}"""
        assertEquals(CodeCheck.EXPIRED, (twilio().check(phone, "123456") as Resource.Success).data)
    }

    @Test
    fun `Twilio's own refusals are told apart`() = runBlocking {
        status = 400
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
        val port = server.address.port
        server.stop(0)
        assertTrue((TwilioVerify("a", "b", "c", null, "http://127.0.0.1:$port").start(phone) as Resource.Error).error is IntegrationError.Unavailable)
    }
}
