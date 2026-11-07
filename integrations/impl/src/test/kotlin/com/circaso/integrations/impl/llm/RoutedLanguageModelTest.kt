package com.circaso.integrations.impl.llm

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LanguageModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Per-purpose routing, and the one fallback before the first word. */
class RoutedLanguageModelTest {
    /** A model that answers each call with the next scripted outcome: events, or a refusal to open. */
    private class Scripted(val name: String, vararg outcomes: Resource<List<ModelStreamEvent>>) : LanguageModel {
        private val script = ArrayDeque(outcomes.toList())
        val requests = mutableListOf<ModelRequest>()
        var closed = 0

        override suspend fun complete(request: ModelRequest): Resource<ModelResponse> =
            when (val opened = stream(request)) {
                is Resource.Error -> {
                    opened
                }

                is Resource.Success -> {
                    when (val last = drain(opened.data).last()) {
                        is ModelStreamEvent.Done -> Resource.success(last.response)
                        is ModelStreamEvent.Failed -> Resource.error(last.error)
                        else -> Resource.error(IntegrationError.Unavailable(name))
                    }
                }
            }

        override suspend fun stream(request: ModelRequest): Resource<ModelStream> {
            requests += request
            return when (val next = script.removeFirst()) {
                is Resource.Error -> {
                    next
                }

                is Resource.Success -> {
                    val events = ArrayDeque(next.data)
                    Resource.success(
                        object : ModelStream {
                            override suspend fun next() = events.removeFirstOrNull()

                            override suspend fun close() {
                                closed++
                            }
                        },
                    )
                }
            }
        }

        private suspend fun drain(stream: ModelStream): List<ModelStreamEvent> {
            val all = mutableListOf<ModelStreamEvent>()
            while (true) all += stream.next() ?: return all
        }
    }

    private fun done(text: String, model: String? = null) =
        ModelStreamEvent.Done(ModelResponse(text, emptyList(), ModelUsage(10, 2), model))

    private fun answer(vararg pieces: String, model: String? = null): Resource<List<ModelStreamEvent>> =
        Resource.success(pieces.map { ModelStreamEvent.Text(it) } + done(pieces.joinToString(""), model))

    private fun failing(error: IntegrationError = IntegrationError.Unavailable("Anthropic")) =
        Resource.success(listOf<ModelStreamEvent>(ModelStreamEvent.Failed(error)))

    private val request = ModelRequest("s", listOf(ModelMessage.User("hi")), purpose = ModelPurpose.VOICE)

    private suspend fun ModelStream.drain(): List<ModelStreamEvent> {
        val all = mutableListOf<ModelStreamEvent>()
        while (true) all += next() ?: return all
    }

    private suspend fun RoutedLanguageModel.events(request: ModelRequest) =
        (stream(request) as Resource.Success).data.drain()

    @Test
    fun `a typed turn goes to the chat model and a spoken one to the voice model`() =
        runBlocking<Unit> {
            val chat = Scripted("chat", answer("typed"))
            val voice = Scripted("voice", answer("spoken"))
            val routed = RoutedLanguageModel(ModelRoute(chat, "chat"), ModelRoute(voice, "voice"))

            assertEquals(ModelStreamEvent.Text("spoken"), routed.events(request).first())
            assertEquals(
                ModelStreamEvent.Text("typed"),
                routed.events(request.copy(purpose = ModelPurpose.CHAT)).first(),
            )
            assertEquals(1, chat.requests.size)
            assertEquals(1, voice.requests.size)
        }

    @Test
    fun `a model that can't be reached falls back once, with the very same request`() =
        runBlocking<Unit> {
            val claude = Scripted("claude", Resource.error(IntegrationError.Unavailable("Anthropic")))
            val workers = Scripted("workers", answer("Hello from Workers AI"))
            val routed =
                RoutedLanguageModel(ModelRoute(claude, "x"), ModelRoute(claude, "anthropic", workers, "workers-ai"))

            val events = routed.events(request)

            assertEquals(ModelStreamEvent.Text("Hello from Workers AI"), events.first())
            assertIs<ModelStreamEvent.Done>(events.last())
            assertEquals(listOf(request), workers.requests)
        }

    @Test
    fun `a failure or a refusal before the first word falls back, and the fallback's answer is the turn's`() =
        runBlocking<Unit> {
            for (error in listOf(IntegrationError.Unavailable("Anthropic"), IntegrationError.Rejected("declined"))) {
                val claude = Scripted("claude", failing(error))
                val workers = Scripted("workers", answer("Fallback"))
                val routed =
                    RoutedLanguageModel(ModelRoute(claude, "x"), ModelRoute(claude, "anthropic", workers, "workers-ai"))

                assertEquals(listOf(ModelStreamEvent.Text("Fallback"), done("Fallback")), routed.events(request))
                assertEquals(1, claude.closed, "the failed answer is let go")
            }
        }

    @Test
    fun `a failure after the first words is the turn's own, with no fallback`() =
        runBlocking<Unit> {
            val claude =
                Scripted(
                    "claude",
                    Resource.success(
                        listOf(
                            ModelStreamEvent.Text("The dollar"),
                            ModelStreamEvent.Failed(IntegrationError.Unavailable("Anthropic")),
                        ),
                    ),
                )
            val workers = Scripted("workers")
            val routed =
                RoutedLanguageModel(ModelRoute(claude, "x"), ModelRoute(claude, "anthropic", workers, "workers-ai"))

            val events = routed.events(request)

            assertEquals(ModelStreamEvent.Text("The dollar"), events.first())
            assertIs<ModelStreamEvent.Failed>(events.last())
            assertTrue(workers.requests.isEmpty())
        }

    @Test
    fun `a tool call already started counts as an answer, there is only one fallback, and its failure is the turn's`() =
        runBlocking<Unit> {
            val call = ModelStreamEvent.ToolCallDelta(0, "toolu_1", "getWallet", "")
            val claude =
                Scripted(
                    "claude",
                    Resource.success(listOf(call, ModelStreamEvent.Failed(IntegrationError.Unavailable("Anthropic")))),
                    failing(),
                )
            val workers = Scripted("workers", failing())
            val routed =
                RoutedLanguageModel(ModelRoute(claude, "x"), ModelRoute(claude, "anthropic", workers, "workers-ai"))

            assertEquals(
                listOf(call, ModelStreamEvent.Failed(IntegrationError.Unavailable("Anthropic"))).map {
                    it::class
                },
                routed.events(request).map { it::class },
            )
            assertTrue(workers.requests.isEmpty())

            val second = routed.events(request)
            assertIs<ModelStreamEvent.Failed>(second.single())
            assertEquals(1, workers.requests.size)
        }

    @Test
    fun `without a fallback a failure is passed on as it is`() =
        runBlocking<Unit> {
            val claude = Scripted("claude", Resource.error(IntegrationError.Unavailable("Anthropic")))
            val routed = RoutedLanguageModel(ModelRoute(claude, "x"), ModelRoute(claude, "anthropic"))

            assertIs<Resource.Error>(routed.stream(request))
        }

    @Test
    fun `complete falls back too, and the log says which model answered and why it fell back`() =
        runBlocking<Unit> {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val logger = LoggerFactory.getLogger(RoutedLanguageModel::class.java) as Logger
            logger.addAppender(appender)
            try {
                val claude = Scripted("claude", failing(IntegrationError.Rejected("declined")))
                val workers = Scripted("workers", answer("Hi", model = null))
                val routed =
                    RoutedLanguageModel(ModelRoute(claude, "x"), ModelRoute(claude, "anthropic", workers, "workers-ai"))

                val answer = routed.complete(request)

                assertEquals("Hi", (answer as Resource.Success).data.text)
            } finally {
                logger.detachAppender(appender)
            }
            val lines = appender.list.map { it.formattedMessage }
            assertTrue(
                "asha model fallback: purpose=VOICE from=anthropic to=workers-ai reason=integrations.rejected" in lines,
                "$lines",
            )
            assertTrue(
                "asha model answered: purpose=VOICE model=workers-ai fallback=true toolCalls=0" in lines,
                "$lines",
            )
        }

    @Test
    fun `the answer's own model name wins in the log`() =
        runBlocking<Unit> {
            val claude = Scripted("claude", answer("Hi", model = "claude-haiku-5-5"))
            val routed = RoutedLanguageModel(ModelRoute(claude, "x"), ModelRoute(claude, "anthropic"))

            val done = assertIs<ModelStreamEvent.Done>(routed.events(request).last())

            assertEquals("claude-haiku-5-5", done.response.model)
            assertEquals(emptyList<ToolCall>(), done.response.toolCalls)
        }
}
