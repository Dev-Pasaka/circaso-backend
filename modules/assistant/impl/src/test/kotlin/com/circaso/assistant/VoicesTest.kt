package com.circaso.assistant

import com.circaso.assistant.di.assistantConfig
import com.circaso.assistant.domain.models.VoiceGender
import com.circaso.assistant.domain.support.AshaVoices
import com.circaso.assistant.domain.support.today
import com.circaso.assistant.presentation.controllers.voiceController
import com.circaso.assistant.presentation.services.VoiceService
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Clock
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Asha's voices (decision 2026-10-31): the list (A12), the choice on A4 and A11, and the preview (A13). */
class VoicesTest {
    private val me = UserId.new()
    private val h = Harness(owner = me)

    private fun ApplicationTestBuilder.serve() {
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

    // ---- A12 ---------------------------------------------------------------------------------------------------

    @Test
    fun `A12 lists six voices, three female and three male, with Luna, the voice before, as the one default`() =
        testApplication {
            serve()

            val response = client.get("/v1/assistant/voices")

            assertEquals(HttpStatusCode.OK, response.status)
            val voices =
                Json
                    .parseToJsonElement(response.bodyAsText())
                    .jsonObject["data"]!!
                    .jsonObject["voices"]!!
                    .jsonArray
                    .map { it.jsonObject }
            assertEquals(
                listOf("luna", "harmonia", "thalia", "arcas", "orion", "zeus"),
                voices.map { it["id"]!!.jsonPrimitive.content },
            )
            assertEquals(
                listOf("luna"),
                voices.filter { it["default"]!!.jsonPrimitive.boolean }.map { it["id"]!!.jsonPrimitive.content },
            )
            val luna = voices.first()
            assertEquals("Luna", luna["name"]!!.jsonPrimitive.content)
            assertEquals("Friendly", luna["character"]!!.jsonPrimitive.content)
            assertEquals("female", luna["gender"]!!.jsonPrimitive.content)
            assertEquals(
                mapOf("female" to 3, "male" to 3),
                voices.groupingBy { it["gender"]!!.jsonPrimitive.content }.eachCount(),
            )
        }

    // ---- the choice: A4 ----------------------------------------------------------------------------------------

    @Test
    fun `A4 speaks in the voice asked for`() =
        testApplication {
            serve()

            val response =
                client.post("/v1/assistant/speech") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"You sent Mum 2,000 shillings.","voice":"zeus"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf<String?>("zeus"), h.speech.voices)
            assertTrue(h.fallbacks.seen.isEmpty())
        }

    @Test
    fun `an unknown or missing voice on A4 is the default, silently, and reported for the debug log`() =
        runTest {
            assertEquals("audio/mpeg", h.speak(me, "Hello", "en", "robot-9000").data().contentType)
            assertEquals("audio/mpeg", h.speak(me, "Hello", "en", null).data().contentType)
            assertEquals("audio/mpeg", h.speak(me, "Hello", "en", " Harmonia ").data().contentType)

            assertEquals(listOf<String?>("luna", "luna", "harmonia"), h.speech.voices, "case and spaces aside")
            assertEquals(listOf<Pair<String?, String>>("robot-9000" to "luna", null to "luna"), h.fallbacks.seen)
        }

    @Test
    fun `an old app's A4 body without a voice still works`() =
        testApplication {
            serve()

            val response =
                client.post("/v1/assistant/speech") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"text":"Hello"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(listOf<String?>("luna"), h.speech.voices)
        }

    // ---- A13 ---------------------------------------------------------------------------------------------------

    @Test
    fun `A13 is the fixed line in that voice, made once and then kept, and costs no allowance`() =
        testApplication {
            serve()

            val first = client.get("/v1/assistant/voices/thalia/sample")
            val second = client.get("/v1/assistant/voices/thalia/sample")

            assertEquals(HttpStatusCode.OK, first.status)
            assertEquals("audio/mpeg", first.headers[HttpHeaders.ContentType])
            assertEquals(AshaVoices.SAMPLE_TEXT, String(first.readRawBytes()))
            assertEquals(AshaVoices.SAMPLE_TEXT, String(second.readRawBytes()))
            assertTrue(first.headers[HttpHeaders.CacheControl]!!.contains("max-age=86400"))
            assertEquals(listOf<String?>("thalia"), h.speech.voices, "the second came from the cache")
            assertEquals(0, h.quota.voiceSeconds(me, Clock.systemUTC().today()))
        }

    @Test
    fun `A13 for a voice not on the list is voice_not_found, and a provider failure is model_unavailable`() =
        testApplication {
            serve()

            val unknown = client.get("/v1/assistant/voices/robot/sample")
            assertEquals(HttpStatusCode.NotFound, unknown.status)
            assertTrue("assistant.voice_not_found" in unknown.bodyAsText())
            assertTrue(h.speech.voices.isEmpty(), "no provider call")

            h.speech.isUp = false
            val down = client.get("/v1/assistant/voices/arcas/sample")
            assertEquals(HttpStatusCode.BadGateway, down.status)
            assertTrue("assistant.model_unavailable" in down.bodyAsText())
            assertTrue(h.samples.kept.isEmpty(), "a failure isn't kept")
        }

    // ---- config ------------------------------------------------------------------------------------------------

    private class MapConfig(private val values: Map<String, String>, private val prefix: String = "") : Config {
        override val environment = Environment.LOCAL

        override fun string(key: String): String? = values[prefix + key]

        override fun section(prefix: String): Config = MapConfig(values, "${this.prefix}$prefix.")
    }

    private fun configOf(vararg values: Pair<String, String>) =
        assistantConfig(MapConfig(values.toMap()).section("assistant"))

    @Test
    fun `the list and the default can be set from the environment`() {
        assertEquals(AshaVoices.DEFAULT.all, configOf().voices.all)
        assertEquals("luna", configOf().voices.default.id)
        assertEquals("zeus", configOf("assistant.default-voice" to "zeus").voices.default.id)

        val set =
            configOf("assistant.voices" to "asteria:Asteria:Clear:female, Apollo:Apollo:Easy:MALE").voices
        assertEquals(listOf("asteria", "apollo"), set.all.map { it.id })
        assertEquals(VoiceGender.MALE, set.all.last().gender)
        assertEquals("asteria", set.default.id, "a set list without a default starts with its first")

        assertFailsWith<IllegalStateException> { configOf("assistant.voices" to "luna:Luna") }
        assertFailsWith<IllegalArgumentException> { configOf("assistant.default-voice" to "nobody") }
    }

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data
}
