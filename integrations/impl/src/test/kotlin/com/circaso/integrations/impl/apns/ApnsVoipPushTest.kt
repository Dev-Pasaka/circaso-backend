package com.circaso.integrations.impl.apns

import com.circaso.integrations.api.VoipPushResult
import com.circaso.integrations.api.VoipRing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The VoIP ring to APNs: its path, headers and payload, and how each answer maps to an outcome. */
class ApnsVoipPushTest {
    private val token = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val ring = VoipRing("call-1", "chat-1", "VIDEO", "Wanjiru")

    private class Sent(val path: String, val headers: Map<String, String>, val body: String)

    /** A fake APNs: answers [status] with [body], or throws [failure]. */
    private class FakeApns(var status: Int = 200, var body: String = "", var failure: Exception? = null) :
        ApnsTransport {
        val sent = mutableListOf<Sent>()

        override suspend fun post(
            path: String,
            headers: Map<String, String>,
            body: String,
        ): ApnsResponse {
            sent += Sent(path, headers, body)
            failure?.let { throw it }
            return ApnsResponse(status, this.body, "apns-id-1")
        }
    }

    private val providerToken =
        ApnsProviderToken(
            "ABC123DEFG",
            "TEAM123456",
            KeyPairGenerator
                .getInstance("EC")
                .apply { initialize(ECGenParameterSpec("secp256r1")) }
                .generateKeyPair()
                .private,
        )

    private fun push(apns: FakeApns) = ApnsVoipPush(providerToken, apns, "com.circaso.app")

    @Test
    fun `a ring posts the VoIP push APNs and PushKit expect`() =
        runTest {
            val apns = FakeApns()

            assertEquals(VoipPushResult.SENT, push(apns).ring(token, ring))

            val sent = apns.sent.single()
            assertEquals("/3/device/$token", sent.path)
            assertEquals("voip", sent.headers["apns-push-type"])
            assertEquals("com.circaso.app.voip", sent.headers["apns-topic"])
            assertEquals("10", sent.headers["apns-priority"])
            assertEquals("0", sent.headers["apns-expiration"])
            assertTrue(sent.headers["authorization"]!!.startsWith("bearer ey"))
            assertEquals(
                3,
                sent.headers["authorization"]!!
                    .removePrefix("bearer ")
                    .split('.')
                    .size,
            )
            val payload = Json.parseToJsonElement(sent.body).jsonObject
            assertEquals(
                mapOf(
                    "kind" to "call.ringing",
                    "callId" to "call-1",
                    "chatId" to "chat-1",
                    "callKind" to "VIDEO",
                    "callerName" to "Wanjiru",
                ),
                payload.mapValues { it.value.jsonPrimitive.content },
            )
        }

    @Test
    fun `Unregistered and BadDeviceToken mean the token is gone`() =
        runTest {
            assertEquals(
                VoipPushResult.TOKEN_INVALID,
                push(FakeApns(410, """{"reason":"Unregistered","timestamp":1762600000000}""")).ring(token, ring),
            )
            assertEquals(
                VoipPushResult.TOKEN_INVALID,
                push(FakeApns(400, """{"reason":"BadDeviceToken"}""")).ring(token, ring),
            )
        }

    @Test
    fun `a token that isn't hex is never sent`() =
        runTest {
            val apns = FakeApns()
            assertEquals(VoipPushResult.TOKEN_INVALID, push(apns).ring("../../3/device/x", ring))
            assertTrue(apns.sent.isEmpty())
        }

    @Test
    fun `anything else fails this ring and keeps the token`() =
        runTest {
            for ((status, body) in listOf(
                400 to """{"reason":"BadTopic"}""",
                403 to """{"reason":"ExpiredProviderToken"}""",
                429 to """{"reason":"TooManyRequests"}""",
                500 to "",
                503 to """{"reason":"ServiceUnavailable"}""",
            )) {
                assertEquals(VoipPushResult.FAILED, push(FakeApns(status, body)).ring(token, ring), "$status $body")
            }
            assertEquals(VoipPushResult.FAILED, push(FakeApns(failure = IOException("reset"))).ring(token, ring))
        }

    @Test
    fun `the provider token is reused across rings`() =
        runTest {
            val apns = FakeApns()
            val push = push(apns)
            push.ring(token, ring)
            push.ring(token, ring)
            assertEquals(apns.sent[0].headers["authorization"], apns.sent[1].headers["authorization"])
            assertTrue(Base64.getUrlDecoder().decode(apns.sent[0].headers["authorization"]!!.split('.')[2]).size == 64)
        }

    @Test
    fun `a stopped ring posts an ended VoIP push for the same call`() =
        runTest {
            val apns = FakeApns()

            assertEquals(
                VoipPushResult.SENT,
                push(apns).ended(
                    token,
                    com.circaso.integrations.api
                        .VoipRingEnded("call-1", "chat-1", "CANCELLED"),
                ),
            )

            val sent = apns.sent.single()
            assertEquals("/3/device/$token", sent.path)
            assertEquals("voip", sent.headers["apns-push-type"])
            assertEquals(
                mapOf("kind" to "call.ended", "callId" to "call-1", "chatId" to "chat-1", "reason" to "CANCELLED"),
                Json.parseToJsonElement(sent.body).jsonObject.mapValues { it.value.jsonPrimitive.content },
            )
        }
}
