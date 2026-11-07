package com.circaso.integrations.impl.llm

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Claude only (decision 2026-11-07): per-purpose routing, one quick retry before the first word, and no fallback. */
class RetryingLanguageModelTest {
    private val waits = mutableListOf<Long>()
    private val voice = ModelRequest("s", listOf(ModelMessage.User("hi")), purpose = ModelPurpose.VOICE)
    private val chat = voice.copy(purpose = ModelPurpose.CHAT)

    private fun retrying(model: LanguageModel) = RetryingLanguageModel(model, "anthropic", sleep = { waits += it })

    private suspend fun LanguageModel.events(request: ModelRequest) = (stream(request) as Resource.Success).data.drain()

    @Test
    fun `a typed turn goes to the chat model and a spoken one to the voice model`() =
        runBlocking<Unit> {
            val typed = ScriptedModel("chat", answer("typed"))
            val spoken = ScriptedModel("voice", answer("spoken"))
            val routed = RoutedLanguageModel(ModelRoute(typed, "chat"), ModelRoute(spoken, "voice"))

            assertEquals(ModelStreamEvent.Text("spoken"), routed.events(voice).first())
            assertEquals(ModelStreamEvent.Text("typed"), routed.events(chat).first())
            assertEquals(1, typed.requests.size)
            assertEquals(1, spoken.requests.size)
        }

    @Test
    fun `a model that can't be reached is asked once more, with the very same request, after the purpose's wait`() =
        runBlocking<Unit> {
            for ((request, wait) in listOf(voice to 300L, chat to 1_000L)) {
                waits.clear()
                val claude =
                    ScriptedModel("claude", Resource.error(IntegrationError.Unavailable("Anthropic")), answer("Hi"))

                val events = retrying(claude).events(request)

                assertEquals(ModelStreamEvent.Text("Hi"), events.first())
                assertEquals(listOf(request, request), claude.requests)
                assertEquals(listOf(wait), waits, "${request.purpose}")
            }
        }

    @Test
    fun `one retry, then the second failure is the turn's, and no other model is ever asked`() =
        runBlocking<Unit> {
            val claude =
                ScriptedModel(
                    "claude",
                    Resource.error(IntegrationError.Unavailable("Anthropic")),
                    Resource.error(IntegrationError.Unavailable("Anthropic")),
                )

            val opened = retrying(claude).stream(voice)

            assertIs<IntegrationError.Unavailable>((opened as Resource.Error).error)
            assertEquals(2, claude.requests.size)
            assertEquals(1, waits.size)
        }

    @Test
    fun `a failure mid-stream before the first word retries once, and the retry's answer is the turn's`() =
        runBlocking<Unit> {
            val claude = ScriptedModel("claude", failing(), answer("Back"))

            val events = retrying(claude).events(chat)

            assertEquals(listOf(ModelStreamEvent.Text("Back"), done("Back")), events)
            assertEquals(1, claude.closed, "the failed answer is let go")
            assertEquals(2, claude.requests.size)
        }

    @Test
    fun `a failure after the first words is the turn's own, with no retry`() =
        runBlocking<Unit> {
            val broke =
                Resource.success(
                    listOf(
                        ModelStreamEvent.Text("Half"),
                        ModelStreamEvent.Failed(IntegrationError.Unavailable("Anthropic")),
                    ),
                )
            val claude = ScriptedModel("claude", broke)

            val events = retrying(claude).events(voice)

            assertIs<ModelStreamEvent.Failed>(events.last())
            assertEquals(1, claude.requests.size)
            assertTrue(waits.isEmpty())
        }

    @Test
    fun `a refusal, a key problem or an open circuit is not retried`() =
        runBlocking<Unit> {
            for (error in listOf(
                IntegrationError.Rejected("no"),
                IntegrationError.Unavailable("Anthropic", isTransient = false),
            )) {
                val claude = ScriptedModel("claude", Resource.error(error))
                assertIs<Resource.Error>(retrying(claude).stream(chat))
                assertEquals(1, claude.requests.size, error.code)
            }
            assertTrue(waits.isEmpty())
        }

    @Test
    fun `a short Retry-After is waited for, a long one is not`() =
        runBlocking<Unit> {
            val short =
                ScriptedModel(
                    "claude",
                    Resource.error(IntegrationError.Unavailable("Anthropic", retryAfterSeconds = 2)),
                    answer("ok"),
                )
            assertIs<Resource.Success<*>>(retrying(short).complete(chat))
            assertEquals(listOf(2_000L), waits)

            val long =
                ScriptedModel(
                    "claude",
                    Resource.error(IntegrationError.Unavailable("Anthropic", retryAfterSeconds = 2)),
                )
            assertIs<Resource.Error>(retrying(long).complete(voice), "a spoken turn can't wait two seconds")
            assertEquals(1, long.requests.size)
        }

    @Test
    fun `an answer that ends without a word is retried once`() =
        runBlocking<Unit> {
            val claude = ScriptedModel("claude", Resource.success(emptyList()), answer("Hi"))

            assertEquals(ModelStreamEvent.Text("Hi"), retrying(claude).events(voice).first())
        }

    @Test
    fun `a probe is never retried, and routing probes each distinct model once`() =
        runBlocking<Unit> {
            val claude = ScriptedModel("claude")
            assertIs<Resource.Success<Unit>>(retrying(claude).probe())
            assertEquals(1, claude.probes)

            val other = ScriptedModel("other")
            RoutedLanguageModel(ModelRoute(claude, "a"), ModelRoute(other, "b")).probe()
            RoutedLanguageModel(ModelRoute(claude, "a"), ModelRoute(claude, "a")).probe()
            assertEquals(3, claude.probes)
            assertEquals(1, other.probes)
        }

    @Test
    fun `each retry is logged with its reason and never the conversation`() =
        runBlocking<Unit> {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(RetryingLanguageModel::class.java) as Logger
            logger.addAppender(appender)
            try {
                val claude =
                    ScriptedModel("claude", Resource.error(IntegrationError.Unavailable("Anthropic")), answer("Hi"))
                retrying(claude).complete(voice.copy(messages = listOf(ModelMessage.User("my secret question"))))
            } finally {
                logger.detachAppender(appender)
            }
            val lines = appender.list.map { it.formattedMessage }
            assertEquals(
                listOf("asha model retry: purpose=VOICE model=anthropic reason=integrations.unavailable waitMs=300"),
                lines,
            )
        }
}
