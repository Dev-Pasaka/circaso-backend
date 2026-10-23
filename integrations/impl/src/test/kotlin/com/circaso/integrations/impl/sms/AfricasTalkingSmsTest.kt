package com.circaso.integrations.impl.sms

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SmsPurpose
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.net.URLDecoder
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AfricasTalkingSmsTest {
    private var status = 200
    private var reply = recipient(101, "ATXid_1")
    private var apiKey: String? = null
    private var form: Map<String, String> = emptyMap()

    private fun recipient(code: Int, id: String = "ATXid") =
        """{"SMSMessageData":{"Message":"Sent to 1/1","Recipients":[{"statusCode":$code,"number":"+254712345678","status":"x","messageId":"$id","cost":"KES 0.80"}]}}"""

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/version1/messaging") { ex ->
            apiKey = ex.requestHeaders.getFirst("apiKey")
            form = ex.requestBody.readBytes().decodeToString().split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            val bytes = reply.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        start()
    }

    private val base = "http://127.0.0.1:${server.address.port}"
    private val phone = PhoneNumber.parse("+254712345678")!!

    @AfterTest
    fun tearDown() = server.stop(0)

    private fun send(sms: AfricasTalkingSms = AfricasTalkingSms("circaso", "key-1", baseUrl = base)) =
        runBlocking { sms.send(phone, "Your Circaso code is 123456. Don't share it.", SmsPurpose.VERIFICATION) }

    @Test
    fun `sends the text to the number with the key in the header, and returns the message id`() {
        val result = send()
        assertEquals("ATXid_1", (result as Resource.Success).data.messageId)
        assertEquals("key-1", apiKey)
        assertEquals("circaso", form["username"])
        assertEquals("+254712345678", form["to"])
        assertEquals("Your Circaso code is 123456. Don't share it.", form["message"])
        assertNull(form["from"], "no sender id configured, so Africa's Talking's shared short code is used")
    }

    @Test
    fun `an approved sender id is sent as from`() {
        send(AfricasTalkingSms("circaso", "key-1", senderId = "CIRCASO", baseUrl = base))
        assertEquals("CIRCASO", form["from"])
    }

    @Test
    fun `the sandbox username goes to the sandbox host, anything else to the live one`() {
        val sandbox = AfricasTalkingSms("sandbox", "k")
        val live = AfricasTalkingSms("circaso", "k")
        // the default base url is chosen from the username; reading it back through a failing send would need the network, so check the choice directly
        assertTrue(sandbox.toString().isNotEmpty() && live.toString().isNotEmpty())
        val field = AfricasTalkingSms::class.java.getDeclaredField("baseUrl").apply { isAccessible = true }
        assertEquals("https://api.sandbox.africastalking.com", field.get(sandbox))
        assertEquals("https://api.africastalking.com", field.get(live))
    }

    @Test
    fun `a number that cannot be reached is Rejected, a problem on our side is Unavailable`() {
        for (code in listOf(403, 404, 406, 407)) {
            reply = recipient(code)
            assertIs<IntegrationError.Rejected>((send() as Resource.Error).error, "code $code")
        }
        for (code in listOf(401, 402, 405, 500, 501, 502)) {
            reply = recipient(code)
            assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error, "code $code")
        }
        reply = recipient(102)
        assertIs<Resource.Success<*>>(send())
        reply = recipient(100)
        assertIs<Resource.Success<*>>(send())
    }

    @Test
    fun `bad credentials, an outage and an unreadable answer are all Unavailable`() {
        status = 401
        assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error)
        status = 503
        assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error)
        status = 200
        reply = "not json"
        assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error)
        val port = server.address.port
        server.stop(0)
        assertIs<IntegrationError.Unavailable>((send(AfricasTalkingSms("circaso", "k", baseUrl = "http://127.0.0.1:$port")) as Resource.Error).error)
    }
}
