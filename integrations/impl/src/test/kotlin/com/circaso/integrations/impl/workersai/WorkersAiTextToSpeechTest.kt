package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.workersai.WorkersAiKit.json
import com.circaso.shared.Resource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WorkersAiTextToSpeechTest {
    private val mp3 = byteArrayOf(0x49, 0x44, 0x33, 4, 0, 0, 1, 2, 3)
    private var jsonReply: String? = null
    private var status = 200

    private val clients =
        FakeHttpClients { _ ->
            jsonReply?.let { return@FakeHttpClients json(it, status) }
            respond(mp3, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
        }
    private val tts = WorkersAiTextToSpeech(WorkersAiKit.gateway(clients), "@cf/deepgram/aura-2-en")

    @Test
    fun `text goes to Aura's model route opted out of Deepgram's model improvement, and MP3 comes back`() =
        runBlocking<Unit> {
            val result = tts.speak("You sent KES 200 to Mum.", "en")

            val audio = (result as Resource.Success).data
            assertContentEquals(mp3, audio.bytes)
            assertEquals("audio/mpeg", audio.contentType)
            val request = clients.last
            assertEquals(
                "https://api.cloudflare.com/client/v4/accounts/acc123/ai/run/@cf/deepgram/aura-2-en",
                "${request.url}",
            )
            assertEquals("Bearer cf-token", request.headers["Authorization"])
            assertEquals("false", request.headers["cf-aig-collect-log"])
            val body = request.json()
            assertEquals("You sent KES 200 to Mum.", body["text"]!!.jsonPrimitive.content)
            assertEquals(true, body["mip_opt_out"]!!.jsonPrimitive.boolean)
        }

    @Test
    fun `a voice goes as Aura's speaker, and no voice leaves the field out so Aura uses its default`() =
        runBlocking<Unit> {
            tts.speak("Hello", "en", "zeus")
            assertEquals(
                "zeus",
                clients.last
                    .json()["speaker"]!!
                    .jsonPrimitive.content,
            )

            tts.speak("Hello", "en")
            assertNull(clients.last.json()["speaker"], "no voice, no speaker field")
        }

    @Test
    fun `the opt-out goes with every call, whatever the language`() =
        runBlocking<Unit> {
            tts.speak("Hello", null)
            assertEquals(
                true,
                clients.last
                    .json()["mip_opt_out"]!!
                    .jsonPrimitive.boolean,
            )
        }

    @Test
    fun `Aura speaks English only, so Swahili is UnsupportedLanguage without calling`() =
        runBlocking<Unit> {
            val result = tts.speak("Habari yako", "sw")

            val error = (result as Resource.Error).error
            assertIs<IntegrationError.UnsupportedLanguage>(error)
            assertEquals("integrations.unsupported_language", error.code)
            assertEquals(0, clients.requests.size)
        }

    @Test
    fun `audio wrapped as base64 in JSON is read too`() =
        runBlocking<Unit> {
            jsonReply = """{"result":{"audio":"${Base64.getEncoder().encodeToString(mp3)}"},"success":true}"""

            val audio = (tts.speak("Hello", "en-GB") as Resource.Success).data

            assertContentEquals(mp3, audio.bytes)
            assertEquals("audio/mpeg", audio.contentType)
        }

    @Test
    fun `no audio, a bad token, a rate limit and an outage fail in our language`() =
        runBlocking<Unit> {
            jsonReply = """{"result":{}}"""
            assertIs<IntegrationError.Unavailable>((tts.speak("Hello", null) as Resource.Error).error)
            jsonReply = """{"errors":[{"code":10000}]}"""
            for (code in listOf(401, 429, 503)) {
                status = code
                assertIs<IntegrationError.Unavailable>(
                    (tts.speak("Hello", null) as Resource.Error).error,
                    "status $code",
                )
            }
        }
}
