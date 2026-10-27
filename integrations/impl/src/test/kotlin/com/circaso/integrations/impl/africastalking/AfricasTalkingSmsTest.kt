package com.circaso.integrations.impl.africastalking

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SmsPurpose
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.form
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class AfricasTalkingSmsTest {
    private var status = 200
    private var reply = recipient(101, "ATXid_1")
    private var down = false

    private fun recipient(code: Int, id: String = "ATXid") =
        """{"SMSMessageData":{"Message":"Sent to 1/1","Recipients":[{"statusCode":$code,"number":"+254712345678","status":"x","messageId":"$id","cost":"KES 0.80"}]}}"""

    private val clients = FakeHttpClients { _ -> if (down) throw IOException("down") else json(reply, status) }
    private val phone = PhoneNumber.parse("+254712345678")!!

    private fun send(senderId: String? = null) =
        runBlocking {
            AfricasTalkingSms(
                "circaso",
                "key-1",
                clients.named("africastalking") {
                    baseUrl =
                        africasTalkingBaseUrl("circaso")
                },
                senderId,
            ).send(phone, "Your Circaso code is 123456. Don't share it.", SmsPurpose.VERIFICATION)
        }

    @Test
    fun `sends the text to the number with the key in the header, and returns the message id`() {
        val result = send()
        assertEquals("ATXid_1", (result as Resource.Success).data.messageId)
        val request = clients.last
        assertEquals("https://api.africastalking.com/version1/messaging", request.url.toString())
        assertEquals("key-1", request.headers["apiKey"])
        assertEquals("circaso", request.form()["username"])
        assertEquals("+254712345678", request.form()["to"])
        assertEquals("Your Circaso code is 123456. Don't share it.", request.form()["message"])
        assertNull(request.form()["from"], "no sender id configured, so Africa's Talking's shared short code is used")
        assertNull(request.headers["Idempotency-Key"], "no key, so the shared client never retries a send")
    }

    @Test
    fun `an approved sender id is sent as from`() {
        send(senderId = "CIRCASO")
        assertEquals("CIRCASO", clients.last.form()["from"])
    }

    @Test
    fun `the sandbox username goes to the sandbox host, anything else to the live one`() {
        assertEquals("https://api.sandbox.africastalking.com", africasTalkingBaseUrl("sandbox"))
        assertEquals("https://api.africastalking.com", africasTalkingBaseUrl("circaso"))
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
    fun `a reply with no recipients or no status code is Unavailable`() {
        reply = """{"SMSMessageData":{"Message":"Sent to 0/1","Recipients":[]}}"""
        assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error)
        reply = """{"SMSMessageData":{"Recipients":[{"number":"+254712345678"}]}}"""
        assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error)
        reply = """{"other":1}"""
        assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error)
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
        down = true
        assertIs<IntegrationError.Unavailable>((send() as Resource.Error).error)
    }
}
