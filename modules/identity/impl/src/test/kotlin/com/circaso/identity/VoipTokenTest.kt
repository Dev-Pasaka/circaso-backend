package com.circaso.identity

import com.circaso.identity.api.Principal
import com.circaso.identity.api.PushRegistration
import com.circaso.identity.presentation.controllers.deviceController
import com.circaso.shared.Resource
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** I9b `PUT /v1/devices/current/voip-token` (decision 2026-11-08) and the PushKit token's life on the device row. */
class VoipTokenTest {
    private val number = "+254712345678"
    private val token = "a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90"

    private suspend fun Harness.appPrincipal(phone: Phone): Principal =
        principalOf(signUp("amina", phone, number).session.accessToken)

    private fun ApplicationTestBuilder.serve(h: Harness, principal: Principal) {
        application {
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(
                    CallPrincipalKey,
                    CallPrincipal(principal.userId, principal.deviceId, principal.sessionId.value, principal.scope),
                )
            }
            routing { route("/v1") { deviceController(h.deviceService) } }
        }
    }

    private suspend fun ApplicationTestBuilder.putVoip(body: String): HttpResponse =
        client.put("/v1/devices/current/voip-token") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }

    private fun code(text: String) =
        Json
            .parseToJsonElement(text)
            .jsonObject["code"]
            ?.jsonPrimitive
            ?.content

    @Test
    fun `the route stores the PushKit token, and push targets carry it beside the FCM token`() =
        testApplication {
            val h = Harness()
            val phone = Phone()
            val me = h.appPrincipal(phone)
            serve(h, me)

            val response = putVoip("""{"voipToken":"$token"}""")

            assertEquals(HttpStatusCode.OK, response.status)
            val target = (h.pushTarget(me.userId) as Resource.Success).data
            assertEquals(PushRegistration("android", "fcm:Pixel", token), target)
        }

    @Test
    fun `a blank or missing token is request malformed`() =
        testApplication {
            val h = Harness()
            val me = h.appPrincipal(Phone())
            serve(h, me)

            for (body in listOf("""{"voipToken":""}""", """{"voipToken":"   "}""", "{}")) {
                val response = putVoip(body)
                assertEquals(HttpStatusCode.BadRequest, response.status, body)
                assertEquals("request.malformed", code(response.bodyAsText()), body)
            }
            assertNull((h.pushTarget(me.userId) as Resource.Success).data?.voipToken)
        }

    @Test
    fun `sign-out and removing the phone forget the PushKit token`() =
        runTest {
            val h = Harness()
            val phone = Phone()
            val me = h.appPrincipal(phone)
            h.updateVoipToken(me, token)

            h.signOut(me)

            val device = h.store.devices[me.userId to me.deviceId]!!
            assertNull(device.voipToken)
            assertEquals("fcm:Pixel", device.pushToken, "the FCM token stays: the phone is still registered")

            val again = Harness()
            val other = again.appPrincipal(Phone())
            again.updateVoipToken(other, token)
            again.removeDevice(other)
            assertNull(again.store.devices[other.userId to other.deviceId]!!.voipToken)
        }

    @Test
    fun `APNs refusing a token forgets only that token, never a newer one`() =
        runTest {
            val h = Harness()
            val me = h.appPrincipal(Phone())
            h.updateVoipToken(me, token)

            h.clearVoipToken(me.userId, "0000ffff0000ffff")
            assertEquals(token, h.store.devices[me.userId to me.deviceId]!!.voipToken)

            h.clearVoipToken(me.userId, token)
            assertNull(h.store.devices[me.userId to me.deviceId]!!.voipToken)
            assertEquals(
                PushRegistration("android", "fcm:Pixel", null),
                (h.pushTarget(me.userId) as Resource.Success).data,
            )
        }
}
