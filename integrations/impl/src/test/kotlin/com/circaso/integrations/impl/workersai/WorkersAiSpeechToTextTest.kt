package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.Transcript
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.workersai.WorkersAiKit.json
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WorkersAiSpeechToTextTest {
    private var status = 200
    private var reply =
        """
        {"result":{"text":" Nimetuma pesa kwa Mama. ","word_count":4,
                   "transcription_info":{"language":"sw","language_probability":0.97,"duration":6.2},
                   "segments":[{"start":0.0,"end":6.1,"text":"Nimetuma pesa kwa Mama."}]},
         "success":true,"errors":[],"messages":[]}
        """.trimIndent()
    private val clients = FakeHttpClients { _ -> json(reply, status) }
    private val stt = WorkersAiSpeechToText(WorkersAiKit.gateway(clients), "@cf/openai/whisper-large-v3-turbo")
    private val clip = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 9, 9)

    @Test
    fun `a clip goes to Whisper's model route as base64 with the Swahili hint, and the transcript comes back`() =
        runBlocking<Unit> {
            val result = stt.transcribe(clip, "audio/webm", "sw-KE")

            assertEquals(Transcript("Nimetuma pesa kwa Mama.", seconds = 7), (result as Resource.Success).data)
            val request = clients.last
            assertEquals(
                "https://api.cloudflare.com/client/v4/accounts/acc123/ai/run/@cf/openai/whisper-large-v3-turbo",
                "${request.url}",
            )
            assertEquals("Bearer cf-token", request.headers["Authorization"])
            assertEquals("asha", request.headers["cf-aig-gateway-id"])
            val body = request.json()
            assertEquals(Base64.getEncoder().encodeToString(clip), body["audio"]!!.jsonPrimitive.content)
            assertEquals("transcribe", body["task"]!!.jsonPrimitive.content)
            assertEquals("sw", body["language"]!!.jsonPrimitive.content, "Whisper takes the two-letter code")
        }

    @Test
    fun `the hint is the first Accept-Language range without its region`() =
        runBlocking<Unit> {
            fun hintFor(acceptLanguage: String?): String? {
                runBlocking { stt.transcribe(clip, "audio/mp4", acceptLanguage) }
                return clients.last
                    .json()["language"]
                    ?.jsonPrimitive
                    ?.content
            }

            assertEquals("sw", hintFor("sw,en;q=0.9"))
            assertEquals("en", hintFor("en-KE;q=1.0, sw-KE;q=0.9"))
            assertNull(hintFor("*"), "no language named, so Whisper detects it")
            assertNull(hintFor(null))
            assertNull(hintFor("not a language"))
        }

    @Test
    fun `the length falls back to the last segment, and is at least a second`() =
        runBlocking<Unit> {
            reply = """{"result":{"text":"hi","segments":[{"start":0,"end":2.4,"text":"hi"}]}}"""
            assertEquals(3, (stt.transcribe(clip, "audio/mp4", "en") as Resource.Success).data.seconds)
            reply = """{"result":{"text":""}}"""
            assertEquals(Transcript("", 1), (stt.transcribe(clip, "audio/mp4", "en") as Resource.Success).data)
        }

    @Test
    fun `an answer without text, a rate limit and an outage fail in our language`() =
        runBlocking<Unit> {
            reply = """{"result":{"word_count":0}}"""
            assertIs<IntegrationError.Unavailable>((stt.transcribe(clip, "audio/mp4", null) as Resource.Error).error)
            reply = """{"errors":[{"code":3040}]}"""
            for (code in listOf(401, 429, 500)) {
                status = code
                val result = stt.transcribe(clip, "audio/mp4", null)
                assertIs<IntegrationError.Unavailable>((result as Resource.Error).error, "status $code")
            }
        }
}
