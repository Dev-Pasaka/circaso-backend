package com.circaso.integrations.impl.workersai

import com.circaso.integrations.api.ImageInput
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpRequestRetry
import io.ktor.client.plugins.defaultRequest
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * A 503 is retried by the shared client (Architecture → Infrastructure, the retry table). The client here installs
 * that same rule, so these tests show which Workers AI calls opt out of it: a model turn can already take 60 s, and
 * a user mustn't wait for three of them.
 */
class WorkersAiRetriesTest {
    private val attempts = AtomicInteger()
    private val http =
        HttpClient(
            MockEngine { _ ->
                attempts.incrementAndGet()
                respond("""{"errors":[{"code":3040}]}""", HttpStatusCode.ServiceUnavailable)
            },
        ) {
            expectSuccess = false
            install(HttpRequestRetry) {
                maxRetries = 2
                retryIf { _, response -> response.status.value == 503 }
                delayMillis { 0L }
            }
            defaultRequest { url(WorkersAiGateway.baseUrl("acc123")) }
        }
    private val gateway = WorkersAiGateway(http, "cf-token", "asha")

    @Test
    fun `a 503 on chat gives exactly one attempt`() =
        runBlocking<Unit> {
            val model = WorkersAiLanguageModel(gateway, "@cf/moonshotai/kimi-k2.6", maxOutputTokens = 4096)

            val result = model.complete(ModelRequest("s", listOf(ModelMessage.User("u"))))

            assertIs<IntegrationError.Unavailable>((result as Resource.Error).error)
            assertEquals(1, attempts.get())
        }

    @Test
    fun `a 503 on a streamed reply gives exactly one attempt`() =
        runBlocking<Unit> {
            val model = WorkersAiLanguageModel(gateway, "@cf/moonshotai/kimi-k2.6", maxOutputTokens = 4096)

            val result = model.stream(ModelRequest("s", listOf(ModelMessage.User("u"))))

            assertIs<IntegrationError.Unavailable>((result as Resource.Error).error)
            assertEquals(1, attempts.get())
        }

    @Test
    fun `a 503 on reading a photo gives exactly one attempt`() =
        runBlocking<Unit> {
            val vision = WorkersAiVisionModel(gateway, "@cf/meta/llama-4-scout-17b-16e-instruct")

            vision.describe(ImageInput(byteArrayOf(1, 2), "image/png"), "Describe it")

            assertEquals(1, attempts.get())
        }

    @Test
    fun `clips keep the shared retry rules`() =
        runBlocking<Unit> {
            WorkersAiSpeechToText(gateway, "@cf/openai/whisper-large-v3-turbo").transcribe(byteArrayOf(1), "audio/mp4")
            assertEquals(3, attempts.getAndSet(0))

            WorkersAiTextToSpeech(gateway, "@cf/deepgram/aura-2-en").speak("Hello")
            assertEquals(3, attempts.get())
        }
}
