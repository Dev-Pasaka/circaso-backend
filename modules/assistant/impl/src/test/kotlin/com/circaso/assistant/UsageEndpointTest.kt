package com.circaso.assistant

import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.today
import com.circaso.assistant.presentation.controllers.voiceController
import com.circaso.assistant.presentation.mappers.toResponse
import com.circaso.assistant.presentation.services.VoiceService
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A7 `GET /v1/assistant/usage` for the app's usage menu (2026-11-05): the original flat fields, plus the UTC day as
 * a period and a `{ used, limit }` meter each for messages, voice seconds and web searches.
 */
class UsageEndpointTest {
    private val me = UserId.new()

    private fun ApplicationTestBuilder.serve(h: Harness) {
        val voice = VoiceService(h.transcribe, h.speak, h.usage, h.listVoices, h.voiceSample)
        application {
            installJson()
            installStatusPages()
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(CallPrincipalKey, CallPrincipal(me, DeviceId.new(), UUID.randomUUID(), Scope.APP))
            }
            routing { route("/v1") { voiceController(voice) } }
        }
    }

    private suspend fun ApplicationTestBuilder.usage(): JsonObject {
        val response = client.get("/v1/assistant/usage")
        assertEquals(HttpStatusCode.OK, response.status)
        return Json.parseToJsonElement(response.bodyAsText()).jsonObject["data"]!!.jsonObject
    }

    private fun JsonObject.meter(name: String) = this[name]!!.jsonObject

    @Test
    fun `the usage menu gets the plan, the day as a period and a meter for each allowance`() =
        testApplication {
            val h = Harness(owner = me)
            serve(h)
            val day = Clock.systemUTC().today()
            h.quota.countTextTurn(me, day, 20)
            h.quota.countTextTurn(me, day, 20)
            h.quota.addVoiceSeconds(me, day, 14)
            h.quota.countWebSearch(me, day, 10)

            val data = usage()

            assertEquals("FREE", data["plan"]!!.jsonPrimitive.content)
            assertEquals(2, data.meter("messages")["used"]!!.jsonPrimitive.int)
            assertEquals(20, data.meter("messages")["limit"]!!.jsonPrimitive.int)
            assertEquals(14, data.meter("voiceSeconds")["used"]!!.jsonPrimitive.int)
            assertEquals(120, data.meter("voiceSeconds")["limit"]!!.jsonPrimitive.int)
            assertEquals(1, data.meter("searches")["used"]!!.jsonPrimitive.int)
            assertEquals(10, data.meter("searches")["limit"]!!.jsonPrimitive.int)
            val start = Instant.parse(data["periodStart"]!!.jsonPrimitive.content)
            val end = Instant.parse(data["periodEnd"]!!.jsonPrimitive.content)
            assertEquals(Duration.ofDays(1), Duration.between(start, end))
            assertEquals(Instant.parse("${day}T00:00:00Z"), start)
            assertEquals(data["resetsAt"], data["periodEnd"])
            // the original answer is unchanged
            assertEquals(2, data["textTurnsUsed"]!!.jsonPrimitive.int)
            assertEquals(18, data["textTurnsLeft"]!!.jsonPrimitive.int)
            assertEquals(14, data["voiceSecondsUsed"]!!.jsonPrimitive.int)
            assertEquals(106, data["voiceSecondsLeft"]!!.jsonPrimitive.int)
        }

    @Test
    fun `Plus has Plus limits, and lifted limits are null`() =
        testApplication {
            val plus = Harness(owner = me).also { it.plans.plan = Plan.PLUS }
            val unlimited = Harness(AssistantConfig(isUnlimited = true), owner = me)
            serve(plus)

            val data = usage()

            assertEquals("PLUS", data["plan"]!!.jsonPrimitive.content)
            assertEquals(300, data.meter("messages")["limit"]!!.jsonPrimitive.int)
            assertEquals(60, data.meter("searches")["limit"]!!.jsonPrimitive.int)

            val lifted = (unlimited.usage(me) as Resource.Success).data.toResponse()
            assertEquals(null, lifted.messages.limit)
            assertEquals(null, lifted.voiceSeconds.limit)
            assertEquals(null, lifted.searches.limit)
        }
}
