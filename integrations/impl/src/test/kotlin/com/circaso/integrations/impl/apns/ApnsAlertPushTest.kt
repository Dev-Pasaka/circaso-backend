package com.circaso.integrations.impl.apns

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PushGateway
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPayload
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushReceipt
import com.circaso.integrations.api.PushTarget
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** iPhone pushes straight to APNs: the alert, the mutable chat wake, the silent push, and each answer's outcome. */
class ApnsAlertPushTest {
    private val token = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"
    private val iphone = PushTarget(PushPlatform.IOS, token)

    private class Sent(val path: String, val headers: Map<String, String>, val body: String)

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

    private val clock = Clock.fixed(Instant.parse("2026-11-09T10:00:00Z"), ZoneOffset.UTC)

    private fun push(apns: FakeApns) = ApnsAlertPush(providerToken, apns, "com.circaso.app", clock)

    private val chatWake =
        PushMessage(
            "Circaso",
            "New message",
            PushPayload(
                chatId = "c1",
                messageId = "m1",
                senderId = "u1",
                sentAt = "2026-11-09T10:15:30Z",
                messageKind = "MESSAGE",
                ciphertext = "c2VhbGVk",
            ),
            dataOnly = true,
        )

    private fun Sent.json() = Json.parseToJsonElement(body).jsonObject

    private fun JsonObject.aps() = this["aps"]!!.jsonObject

    @Test
    fun `a chat message is a mutable alert in the chat's thread, with the ids at the top level and no words`() =
        runTest {
            val apns = FakeApns()

            val result = push(apns).send(iphone, chatWake)

            assertEquals("apns-id-1", assertIs<Resource.Success<PushReceipt>>(result).data.messageId)
            val sent = apns.sent.single()
            assertEquals("/3/device/$token", sent.path)
            assertEquals("alert", sent.headers["apns-push-type"])
            assertEquals("com.circaso.app", sent.headers["apns-topic"], "the app itself, not .voip")
            assertEquals("10", sent.headers["apns-priority"])
            assertTrue(sent.headers["authorization"]!!.startsWith("bearer ey"))
            val json = sent.json()
            val aps = json.aps()
            assertEquals("1", aps["mutable-content"]!!.jsonPrimitive.content)
            assertEquals("c1", aps["thread-id"]!!.jsonPrimitive.content)
            assertEquals("message", aps["category"]!!.jsonPrimitive.content)
            assertEquals("New message", aps["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content)
            assertEquals(
                mapOf(
                    "chatId" to "c1",
                    "messageId" to "m1",
                    "senderId" to "u1",
                    "sentAt" to "2026-11-09T10:15:30Z",
                    "messageKind" to "MESSAGE",
                    "ciphertext" to "c2VhbGVk",
                ),
                json.filterKeys { it != "aps" }.mapValues { it.value.jsonPrimitive.content },
            )
        }

    @Test
    fun `a normal push is a plain alert the phone shows as it is`() =
        runTest {
            val apns = FakeApns()
            val joined =
                PushMessage("Circaso", "Amina is on Circaso", PushPayload(kind = "contact.joined", userId = "u1"))

            push(apns).send(iphone, joined)

            val aps =
                apns.sent
                    .single()
                    .json()
                    .aps()
            assertEquals(null, aps["mutable-content"])
            assertEquals(null, aps["category"], "not a chat message")
            assertEquals("default", aps["sound"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a silent push is a background push with no alert, priority 5, and expires with its ttl`() =
        runTest {
            val apns = FakeApns()
            val silent =
                PushMessage(
                    "Circaso",
                    "",
                    PushPayload(kind = "moment.keys_requested", userId = "u1"),
                    silent = true,
                    ttlSeconds = 3600,
                )

            push(apns).send(iphone, silent)

            val sent = apns.sent.single()
            assertEquals("background", sent.headers["apns-push-type"])
            assertEquals("5", sent.headers["apns-priority"])
            assertEquals((clock.instant().epochSecond + 3600).toString(), sent.headers["apns-expiration"])
            val aps = sent.json().aps()
            assertEquals(mapOf("content-available" to "1"), aps.mapValues { it.value.jsonPrimitive.content })
        }

    @Test
    fun `money keeps its own sound`() =
        runTest {
            val apns = FakeApns()
            val money =
                PushMessage(
                    "Circaso",
                    "You received money",
                    PushPayload(kind = "payment.received"),
                    dataOnly = true,
                    sound = "money_received.wav",
                )

            push(apns).send(iphone, money)

            assertEquals(
                "money_received.wav",
                apns.sent
                    .single()
                    .json()
                    .aps()["sound"]!!
                    .jsonPrimitive.content,
            )
        }

    @Test
    fun `a token APNs no longer accepts is rejected so the caller can drop it`() =
        runTest {
            for ((status, body) in listOf(
                410 to """{"reason":"Unregistered"}""",
                400 to """{"reason":"BadDeviceToken"}""",
            )) {
                val result = push(FakeApns(status, body)).send(iphone, chatWake)
                assertIs<IntegrationError.Rejected>(assertIs<Resource.Error>(result).error)
            }
            val notHex = push(FakeApns()).send(PushTarget(PushPlatform.IOS, "fcm:token"), chatWake)
            assertIs<IntegrationError.Rejected>(assertIs<Resource.Error>(notHex).error)
        }

    @Test
    fun `a busy APNs, a stale provider token or a network failure is unavailable`() =
        runTest {
            val busy = push(FakeApns(503)).send(iphone, chatWake)
            val stale = push(FakeApns(403, """{"reason":"ExpiredProviderToken"}""")).send(iphone, chatWake)
            val down = push(FakeApns(failure = IOException("reset"))).send(iphone, chatWake)
            listOf(busy, stale, down).forEach {
                assertIs<IntegrationError.Unavailable>(assertIs<Resource.Error>(it).error)
            }
        }

    @Test
    fun `iPhones go to APNs when it is configured, everything else to FCM`() =
        runTest {
            val fcm = Recording()
            val apns = Recording()
            val gateway = PlatformPushGateway(fcm, apns)

            gateway.send(iphone, chatWake)
            gateway.send(PushTarget(PushPlatform.ANDROID, "fcm-token"), chatWake)
            PlatformPushGateway(fcm, null).send(iphone, chatWake)

            assertEquals(listOf(PushPlatform.IOS), apns.targets.map { it.platform })
            assertEquals(listOf(PushPlatform.ANDROID, PushPlatform.IOS), fcm.targets.map { it.platform })
        }

    private class Recording : PushGateway {
        val targets = mutableListOf<PushTarget>()

        override suspend fun send(target: PushTarget, message: PushMessage): Resource<PushReceipt> {
            targets += target
            return Resource.success(PushReceipt("id", "fake"))
        }
    }
}
