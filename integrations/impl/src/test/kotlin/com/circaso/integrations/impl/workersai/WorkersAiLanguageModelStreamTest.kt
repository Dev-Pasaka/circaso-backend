package com.circaso.integrations.impl.workersai

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.workersai.WorkersAiKit.json
import com.circaso.shared.Resource
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Asha's replies streamed from Kimi (`stream: true`): server-sent events from a MockEngine body written as we go. */
class WorkersAiLanguageModelStreamTest {
    /** The answer's body, written by the test while the model reads it. */
    private val body = ByteChannel(autoFlush = true)
    private var status = 200
    private var refusal = ""
    private val clients =
        FakeHttpClients { _ ->
            if (status != 200) {
                json(refusal, status)
            } else {
                respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
            }
        }
    private val model =
        WorkersAiLanguageModel(WorkersAiKit.gateway(clients), "@cf/moonshotai/kimi-k2.6", maxOutputTokens = 4096)
    private val request = ModelRequest("You are Asha.", listOf(ModelMessage.User("Hello")))

    private suspend fun send(vararg lines: String) = lines.forEach { body.writeStringUtf8("$it\n\n") }

    private fun chunk(delta: String, finish: String? = null) =
        """data: {"id":"c1","object":"chat.completion.chunk","choices":[{"index":0,"delta":$delta,""" +
            """"finish_reason":${finish?.let { "\"$it\"" } ?: "null"}}]}"""

    private fun text(piece: String) = chunk("""{"content":"$piece"}""")

    private suspend fun open(): ModelStream = (model.stream(request) as Resource.Success).data

    /** Every event to the end of the answer. */
    private suspend fun ModelStream.drain(): List<ModelStreamEvent> {
        val events = mutableListOf<ModelStreamEvent>()
        while (true) events += next() ?: return events
    }

    @Test
    fun `text arrives piece by piece while the model writes it, then the whole answer with its usage`() =
        runBlocking<Unit> {
            send(""": keep-alive""", chunk("""{"role":"assistant","content":""}"""), text("Hi"))
            val stream = open()

            assertEquals(ModelStreamEvent.Text("Hi"), stream.next(), "the first words, before the rest is written")
            send(
                chunk("""{"reasoning_content":"the user greets me"}"""),
                text(" Amina!"),
                chunk("{}", finish = "stop"),
                """data: {"choices":[],"usage":{"prompt_tokens":120,"completion_tokens":18,"total_tokens":138}}""",
                "data: [DONE]",
            )

            assertEquals(ModelStreamEvent.Text(" Amina!"), stream.next(), "reasoning is never passed on")
            assertEquals(
                ModelStreamEvent.Done(ModelResponse("Hi Amina!", emptyList(), ModelUsage(120, 18))),
                stream.next(),
            )
            assertNull(stream.next())

            val sent = clients.last.json()
            assertEquals("/client/v4/accounts/acc123/ai/v1/chat/completions", clients.last.url.encodedPath)
            assertTrue(sent["stream"]!!.jsonPrimitive.boolean)
            assertTrue(
                sent["stream_options"]!!
                    .jsonObject["include_usage"]!!
                    .jsonPrimitive.boolean,
            )
            assertEquals("asha", clients.last.headers["cf-aig-gateway-id"], "streamed replies keep the gateway")
            assertEquals("false", clients.last.headers["cf-aig-collect-log"])
        }

    @Test
    fun `tool calls split across chunks are joined by their index, and usage is estimated when left out`() =
        runBlocking<Unit> {
            send(
                chunk(
                    """{"tool_calls":[{"index":0,"id":"call-a","type":"function",""" +
                        """"function":{"name":"searchContacts","arguments":""}}]}""",
                ),
                chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"{\"que"}}]}"""),
                chunk(
                    """{"tool_calls":[{"index":1,"id":"call-b","function":{"name":"listContacts","arguments":"{}"}}]}""",
                ),
                chunk("""{"tool_calls":[{"index":0,"function":{"arguments":"ry\":\"Mum\"}"}}]}"""),
                chunk("{}", finish = "tool_calls"),
                "data: [DONE]",
            )

            val events = open().drain()

            val pieces = events.filterIsInstance<ModelStreamEvent.ToolCallDelta>()
            assertEquals(listOf(0, 0, 1, 0), pieces.map { it.index })
            assertEquals("searchContacts", pieces.first().name)
            val done = assertIs<ModelStreamEvent.Done>(events.last()).response
            assertNull(done.text)
            assertEquals(
                listOf(
                    ToolCall("call-a", "searchContacts", """{"query":"Mum"}"""),
                    ToolCall("call-b", "listContacts", "{}"),
                ),
                done.toolCalls,
            )
            assertTrue(done.usage.inputTokens > 0 && done.usage.outputTokens > 0, "never metered as free")
        }

    /** Seen from Kimi on Workers AI in the smoke test of 2026-10-27, on requests with tools. */
    @Test
    fun `fields sent as null read as absent`() =
        runBlocking<Unit> {
            send(
                chunk("""{"role":"assistant","content":"Hi","tool_calls":null,"reasoning_content":null}"""),
                """data: {"choices":[{"index":0,"delta":{"content":null,"tool_calls":null},""" +
                    """"finish_reason":"stop"}],"usage":null}""",
                "data: [DONE]",
            )

            val events = open().drain()

            assertEquals(ModelStreamEvent.Text("Hi"), events.first())
            assertEquals("Hi", assertIs<ModelStreamEvent.Done>(events.last()).response.text)
        }

    @Test
    fun `a connection cut mid-answer fails after the words already sent`() =
        runBlocking<Unit> {
            send(text("You sent"))
            val stream = open()
            assertEquals(ModelStreamEvent.Text("You sent"), stream.next())

            body.cancel(IOException("connection reset"))

            assertIs<IntegrationError.Unavailable>(assertIs<ModelStreamEvent.Failed>(stream.next()).error)
            assertNull(stream.next())
        }

    @Test
    fun `an error chunk, or a body that stops before its end, is a failure`() =
        runBlocking<Unit> {
            send(text("Hello"), """data: {"error":{"message":"overloaded","code":3040}}""")
            val failed = open().drain().last()
            assertIs<IntegrationError.Unavailable>(assertIs<ModelStreamEvent.Failed>(failed).error)
        }

    @Test
    fun `a body that ends without DONE or a finish reason is a failure`() =
        runBlocking<Unit> {
            send(text("Hello"))
            body.flushAndClose()

            assertIs<ModelStreamEvent.Failed>(open().drain().last())
        }

    @Test
    fun `an answer with neither text nor a tool call is Unavailable`() =
        runBlocking<Unit> {
            send(chunk("""{"reasoning_content":"thinking…"}"""), chunk("{}", finish = "length"), "data: [DONE]")

            val events = open().drain()

            assertEquals(1, events.size, "no text was passed on")
            assertIs<IntegrationError.Unavailable>(assertIs<ModelStreamEvent.Failed>(events.single()).error)
        }

    @Test
    fun `a refusal before the first word is the error, as for a plain call`() =
        runBlocking<Unit> {
            status = 429
            refusal = """{"errors":[{"code":3040}]}"""
            assertIs<IntegrationError.Unavailable>((model.stream(request) as Resource.Error).error)

            status = 400
            refusal = """{"errors":[{"code":5006}]}"""
            assertIs<IntegrationError.Rejected>((model.stream(request) as Resource.Error).error)
        }

    @Test
    fun `closing the stream stops reading, and a cancelled caller doesn't wait for the rest`() =
        runBlocking<Unit> {
            send(text("One"))
            val stream = open()
            assertEquals(ModelStreamEvent.Text("One"), stream.next())

            stream.close()
            assertNull(stream.next(), "nothing more once closed")

            val reading = CompletableDeferred<Unit>()
            val second = ByteChannel(autoFlush = true)
            val other =
                WorkersAiLanguageModel(
                    WorkersAiKit.gateway(
                        FakeHttpClients { _ ->
                            respond(second, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/event-stream"))
                        },
                    ),
                    "@cf/moonshotai/kimi-k2.6",
                    maxOutputTokens = 4096,
                )
            val caller =
                launch {
                    val answer = (other.stream(request) as Resource.Success).data
                    reading.complete(Unit)
                    answer.next() // waits for words that never come
                }
            reading.await()
            withTimeout(2_000) { caller.cancelAndJoin() }
        }

    @Test
    fun `nothing of the answer reaches a log`() =
        runBlocking<Unit> {
            val logs = ListAppender<ILoggingEvent>().apply { start() }
            val workersAiLog = LoggerFactory.getLogger("com.circaso.integrations.impl.workersai") as Logger
            workersAiLog.addAppender(logs)
            try {
                send(text("Your PIN is 4321"), """data: {"error":{"message":"Your PIN is 4321"}}""")

                open().drain()

                assertTrue(logs.list.any { "chat stream failed" in it.formattedMessage })
                assertTrue(logs.list.none { "4321" in it.formattedMessage })
            } finally {
                workersAiLog.detachAppender(logs)
            }
        }
}
