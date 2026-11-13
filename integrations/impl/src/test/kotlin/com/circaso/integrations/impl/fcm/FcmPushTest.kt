package com.circaso.integrations.impl.fcm

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.PushMessage
import com.circaso.integrations.api.PushPayload
import com.circaso.integrations.api.PushPlatform
import com.circaso.integrations.api.PushTarget
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.form
import com.circaso.integrations.impl.text
import com.circaso.shared.Resource
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class FcmPushTest {
    private val keys = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val pem =
        "-----BEGIN PRIVATE KEY-----\n" +
            Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keys.private.encoded) +
            "\n-----END PRIVATE KEY-----\n"
    private val file =
        buildJsonObject {
            put("project_id", "circaso-dev")
            put("client_email", "push@circaso-dev.iam.gserviceaccount.com")
            put("private_key", pem)
        }.toString()

    private var now = Instant.parse("2026-10-25T10:00:00Z")
    private val clock =
        object : Clock() {
            override fun getZone() = ZoneOffset.UTC

            override fun withZone(zone: java.time.ZoneId?) = this

            override fun instant(): Instant = now
        }
    private var sendStatus = 200
    private var sendReply = """{"name":"projects/circaso-dev/messages/0:abc"}"""
    private var down = false

    private val clients =
        FakeHttpClients { request ->
            when {
                down -> {
                    throw IOException("down")
                }

                request.url.host == "oauth2.googleapis.com" -> {
                    json("""{"access_token":"ya29.token","expires_in":3600}""")
                }

                else -> {
                    json(sendReply, sendStatus)
                }
            }
        }
    private val push =
        FcmPush(
            ServiceAccount.parse(file),
            clients.named("fcm") { baseUrl = "https://fcm.googleapis.com" },
            clients.named("google-oauth"),
            clock,
        )
    private val target = PushTarget(PushPlatform.IOS, "device-token")
    private val message = PushMessage("Circaso", "Amina is on Circaso", PushPayload(chatId = "c1"))

    private fun sends() = clients.requests.filter { it.url.host == "fcm.googleapis.com" }

    @Test
    fun `sends the notification to FCM with a bearer token and returns the message id`() =
        runBlocking<Unit> {
            val result = push.send(target, message)

            assertEquals("0:abc", (result as Resource.Success).data.messageId)
            val request = sends().single()
            assertEquals("https://fcm.googleapis.com/v1/projects/circaso-dev/messages:send", request.url.toString())
            assertEquals("Bearer ya29.token", request.headers[HttpHeaders.Authorization])
            val body = Json.parseToJsonElement(request.text()).jsonObject["message"]!!.jsonObject
            assertEquals("device-token", body["token"]!!.jsonPrimitive.content)
            assertEquals("Amina is on Circaso", body["notification"]!!.jsonObject["body"]!!.jsonPrimitive.content)
            assertEquals("c1", body["data"]!!.jsonObject["chatId"]!!.jsonPrimitive.content)
            assertTrue("messageId" !in body["data"]!!.jsonObject, "an id that is not set is not sent")
        }

    @Test
    fun `a normal push is a notification on both phones`() =
        runBlocking<Unit> {
            push.send(target, message)

            val body = Json.parseToJsonElement(sends().single().text()).jsonObject["message"]!!.jsonObject
            val apns = body["apns"]!!.jsonObject
            assertTrue("notification" in body)
            assertEquals(null, apns["headers"]!!.jsonObject["apns-push-type"])
            // Through iOS's notification extension too, so Notification privacy and saved names apply.
            assertEquals(
                1,
                apns["payload"]!!
                    .jsonObject["aps"]!!
                    .jsonObject["mutable-content"]!!
                    .jsonPrimitive.content
                    .toInt(),
            )
        }

    @Test
    fun `a data-only push is a data message on Android and a mutable alert with the fallback text on iOS`() =
        runBlocking<Unit> {
            val wake =
                PushMessage(
                    "Circaso",
                    "New message",
                    PushPayload(
                        chatId = "c1",
                        messageId = "m1",
                        senderId = "u1",
                        sentAt = "2026-11-13T10:15:30Z",
                        ciphertext = "AQI=",
                    ),
                    dataOnly = true,
                )

            push.send(PushTarget(PushPlatform.ANDROID, "device-token"), wake)

            val body = Json.parseToJsonElement(sends().single().text()).jsonObject["message"]!!.jsonObject
            assertTrue("notification" !in body, "no notification block, so Android hands it to the app")
            assertEquals("m1", body["data"]!!.jsonObject["messageId"]!!.jsonPrimitive.content)
            assertEquals("HIGH", body["android"]!!.jsonObject["priority"]!!.jsonPrimitive.content)
            val apns = body["apns"]!!.jsonObject
            assertEquals("alert", apns["headers"]!!.jsonObject["apns-push-type"]!!.jsonPrimitive.content)
            val aps = apns["payload"]!!.jsonObject["aps"]!!.jsonObject
            assertEquals(1, aps["mutable-content"]!!.jsonPrimitive.content.toInt())
            assertEquals("New message", aps["alert"]!!.jsonObject["body"]!!.jsonPrimitive.content)
            assertEquals("c1", aps["thread-id"]!!.jsonPrimitive.content, "a chat's notifications stack together")
            val data = body["data"]!!.jsonObject
            assertEquals("u1", data["senderId"]!!.jsonPrimitive.content)
            assertEquals("AQI=", data["ciphertext"]!!.jsonPrimitive.content)
            assertEquals("2026-11-13T10:15:30Z", data["sentAt"]!!.jsonPrimitive.content)
        }

    @Test
    fun `a silent push shows nothing - data without words on Android, a background push on iOS`() =
        runBlocking<Unit> {
            val wake =
                PushMessage(
                    "Circaso",
                    "",
                    PushPayload(kind = "moment.keys_requested", userId = "u1"),
                    silent = true,
                    ttlSeconds = 3_600,
                )

            push.send(PushTarget(PushPlatform.IOS, "device-token"), wake)

            val body = Json.parseToJsonElement(sends().single().text()).jsonObject["message"]!!.jsonObject
            assertTrue("notification" !in body)
            val data = body["data"]!!.jsonObject
            assertEquals("u1", data["userId"]!!.jsonPrimitive.content)
            assertTrue("title" !in data && "body" !in data, "no words for the app to show")
            val apns = body["apns"]!!.jsonObject
            val headers = apns["headers"]!!.jsonObject
            assertEquals("background", headers["apns-push-type"]!!.jsonPrimitive.content)
            assertEquals("5", headers["apns-priority"]!!.jsonPrimitive.content)
            val aps = apns["payload"]!!.jsonObject["aps"]!!.jsonObject
            assertEquals(1, aps["content-available"]!!.jsonPrimitive.content.toInt())
            assertTrue("alert" !in aps && "sound" !in aps && "mutable-content" !in aps)
        }

    @Test
    fun `money received carries its words for Android and its own sound for iOS`() =
        runBlocking<Unit> {
            val money =
                PushMessage(
                    "Circaso",
                    "Bob sent you money",
                    PushPayload(
                        kind = "payment.received",
                        chatId = "c1",
                        transferId = "tr1",
                        transactionId = "tx1",
                        walletId = "w1",
                    ),
                    dataOnly = true,
                    sound = "money_received.wav",
                )

            push.send(PushTarget(PushPlatform.IOS, "device-token"), money)

            val body = Json.parseToJsonElement(sends().single().text()).jsonObject["message"]!!.jsonObject
            val data = body["data"]!!.jsonObject
            assertEquals("payment.received", data["kind"]!!.jsonPrimitive.content)
            assertEquals("Bob sent you money", data["body"]!!.jsonPrimitive.content, "Android shows it as it is")
            // Ids, never the amount: the phone words it in the person's currency and opens the chat at the card.
            assertEquals("tr1", data["transferId"]!!.jsonPrimitive.content)
            assertEquals("tx1", data["transactionId"]!!.jsonPrimitive.content)
            assertEquals("w1", data["walletId"]!!.jsonPrimitive.content)
            assertEquals("c1", data["chatId"]!!.jsonPrimitive.content)
            val aps = body["apns"]!!.jsonObject["payload"]!!.jsonObject["aps"]!!.jsonObject
            assertEquals("money_received.wav", aps["sound"]!!.jsonPrimitive.content)
        }

    @Test
    fun `an incoming call carries the call ids and lives only as long as the ring`() =
        runBlocking<Unit> {
            val ring =
                PushMessage(
                    "Incoming call",
                    "Wanjiru is calling",
                    PushPayload(chatId = "c1", callId = "k1", kind = "call.ringing", callerName = "Wanjiru"),
                    dataOnly = true,
                    ttlSeconds = 45,
                )

            push.send(PushTarget(PushPlatform.ANDROID, "device-token"), ring)

            val body = Json.parseToJsonElement(sends().single().text()).jsonObject["message"]!!.jsonObject
            val data = body["data"]!!.jsonObject
            assertEquals("k1", data["callId"]!!.jsonPrimitive.content)
            assertEquals("call.ringing", data["kind"]!!.jsonPrimitive.content)
            assertEquals("Wanjiru", data["callerName"]!!.jsonPrimitive.content)
            assertTrue("messageId" !in data, "a field left null is not sent")
            val android = body["android"]!!.jsonObject
            assertEquals("HIGH", android["priority"]!!.jsonPrimitive.content)
            assertEquals("45s", android["ttl"]!!.jsonPrimitive.content)
            val headers = body["apns"]!!.jsonObject["headers"]!!.jsonObject
            assertEquals((now.epochSecond + 45).toString(), headers["apns-expiration"]!!.jsonPrimitive.content)
        }

    @Test
    fun `the access token request is a signed assertion that Google can verify`() =
        runBlocking<Unit> {
            push.send(target, message)

            val form = clients.requests.first { it.url.host == "oauth2.googleapis.com" }.form()
            assertEquals("urn:ietf:params:oauth:grant-type:jwt-bearer", form["grant_type"])
            val (header, claims, signature) = form["assertion"]!!.split('.')
            val decoded = Json.parseToJsonElement(String(Base64.getUrlDecoder().decode(claims))).jsonObject
            assertEquals("push@circaso-dev.iam.gserviceaccount.com", decoded["iss"]!!.jsonPrimitive.content)
            assertEquals("https://www.googleapis.com/auth/firebase.messaging", decoded["scope"]!!.jsonPrimitive.content)
            val verified =
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(keys.public)
                    update("$header.$claims".toByteArray())
                    verify(Base64.getUrlDecoder().decode(signature))
                }
            assertTrue(verified)
        }

    @Test
    fun `the access token is reused until it is about to expire`() =
        runBlocking<Unit> {
            push.send(target, message)
            push.send(target, message)
            assertEquals(1, clients.requests.count { it.url.host == "oauth2.googleapis.com" })

            now = now.plusSeconds(3400)
            push.send(target, message)
            assertEquals(2, clients.requests.count { it.url.host == "oauth2.googleapis.com" })
        }

    @Test
    fun `a token FCM no longer knows is rejected so the caller can drop it`() =
        runBlocking<Unit> {
            sendStatus = 404
            sendReply = """{"error":{"status":"NOT_FOUND","details":[{"errorCode":"UNREGISTERED"}]}}"""

            assertIs<IntegrationError.Rejected>((push.send(target, message) as Resource.Error).error)
        }

    @Test
    fun `our own refused credentials and a busy FCM are unavailable, not the caller's fault`() =
        runBlocking<Unit> {
            for (status in listOf(401, 403, 429, 503)) {
                sendStatus = status
                sendReply = """{"error":{"status":"UNAUTHENTICATED"}}"""
                assertIs<IntegrationError.Unavailable>((push.send(target, message) as Resource.Error).error)
            }
        }

    @Test
    fun `a network failure is unavailable`() =
        runBlocking<Unit> {
            down = true
            assertIs<IntegrationError.Unavailable>((push.send(target, message) as Resource.Error).error)
        }

    @Test
    fun `the service account can be given as base64 too`() {
        val account = ServiceAccount.parse(Base64.getEncoder().encodeToString(file.toByteArray()))
        assertEquals("circaso-dev", account.projectId)
    }
}
