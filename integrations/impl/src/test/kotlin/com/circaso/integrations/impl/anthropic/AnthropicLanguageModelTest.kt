package com.circaso.integrations.impl.anthropic

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.integrations.api.ModelRequest
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.ToolCall
import com.circaso.integrations.api.ToolSpec
import com.circaso.integrations.impl.anthropic.AnthropicKit.args
import com.circaso.integrations.impl.anthropic.AnthropicKit.drain
import com.circaso.integrations.impl.anthropic.AnthropicKit.end
import com.circaso.integrations.impl.anthropic.AnthropicKit.error
import com.circaso.integrations.impl.anthropic.AnthropicKit.fakeAnthropic
import com.circaso.integrations.impl.anthropic.AnthropicKit.model
import com.circaso.integrations.impl.anthropic.AnthropicKit.refusal
import com.circaso.integrations.impl.anthropic.AnthropicKit.slowSse
import com.circaso.integrations.impl.anthropic.AnthropicKit.sse
import com.circaso.integrations.impl.anthropic.AnthropicKit.start
import com.circaso.integrations.impl.anthropic.AnthropicKit.stop
import com.circaso.integrations.impl.anthropic.AnthropicKit.text
import com.circaso.integrations.impl.anthropic.AnthropicKit.textAnswer
import com.circaso.integrations.impl.anthropic.AnthropicKit.textStart
import com.circaso.integrations.impl.anthropic.AnthropicKit.toolStart
import com.circaso.shared.Resource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Claude through Koog's Anthropic client, against Anthropic-shaped Server-Sent Events from a MockEngine. */
class AnthropicLanguageModelTest {
    private val rate =
        ToolSpec(
            "getExchangeRate",
            "Today's rate between two currencies.",
            """{"type":"object","properties":{"from":{"type":"string","description":"A currency code"},""" +
                """"to":{"type":"string"},"limit":{"type":"integer","minimum":1,"maximum":30},""" +
                """"direction":{"type":"string","enum":["IN","OUT"]},""" +
                """"assets":{"type":"array","items":{"type":"string"}}},"required":["from"]}""",
        )
    private val usage = ToolSpec("getAshaUsage", "Today's allowance.", """{"type":"object","properties":{}}""")

    private fun request(
        purpose: ModelPurpose = ModelPurpose.VOICE,
        messages: List<ModelMessage> = listOf(ModelMessage.User("What's the dollar in shillings?")),
        tools: List<ToolSpec> = listOf(usage, rate),
    ) = ModelRequest("You are Asha.", messages, tools, purpose, systemTail = "- Mode: voice.")

    private suspend fun FakeAnthropic.events(request: ModelRequest = request()) =
        (model().stream(request) as Resource.Success).data.drain()

    private fun FakeAnthropic.body(): JsonObject = Json.parseToJsonElement(last.body).jsonObject

    @Test
    fun `text streams piece by piece, without Koog's empty block start, then the answer with its cached usage`() =
        fakeAnthropic {
            answer = { sse(*textAnswer("The dollar is ", "about KES 129.")) }

            val events = events()

            assertEquals(
                listOf(ModelStreamEvent.Text("The dollar is "), ModelStreamEvent.Text("about KES 129.")),
                events.dropLast(1),
            )
            assertEquals(
                ModelStreamEvent.Done(
                    ModelResponse(
                        "The dollar is about KES 129.",
                        emptyList(),
                        ModelUsage(inputTokens = 812 + 6144, outputTokens = 42, cacheReadTokens = 6144),
                        model = "claude-haiku-5-5",
                    ),
                ),
                events.last(),
            )
        }

    @Test
    fun `each piece of text reaches the caller as it arrives, not when the answer is whole`() =
        fakeAnthropic {
            answer = { slowSse(*textAnswer("The dollar ", "is about ", "KES 129.")) }
            val stream = (model().stream(request()) as Resource.Success).data

            val arrivals = mutableListOf<Long>()
            while (true) {
                val event = stream.next() ?: break
                if (event is ModelStreamEvent.Text) arrivals += System.nanoTime() / 1_000_000
            }

            assertEquals(3, arrivals.size)
            // the server waits between pieces; had Koog or Ktor held the answer back, they would arrive together
            assertTrue(
                arrivals.zipWithNext().all { (a, b) ->
                    b - a >= AnthropicKit.CHUNK_PAUSE_MILLIS / 2
                },
                "$arrivals",
            )
        }

    @Test
    fun `a tool call sent in many fragments comes back whole, with the text written before it`() =
        fakeAnthropic {
            answer = {
                sse(
                    start(),
                    textStart(0),
                    text(0, "Let me check."),
                    stop(0),
                    toolStart(1, "toolu_01", "getExchangeRate"),
                    args(1, ""),
                    args(1, """{"fr"""),
                    args(1, """om": "U"""),
                    args(1, """SD", "to"""),
                    args(1, """": "KES"}"""),
                    stop(1),
                    end("tool_use"),
                )
            }

            val events = events()

            val done = assertIs<ModelStreamEvent.Done>(events.last())
            assertEquals("Let me check.", done.response.text)
            assertEquals(
                listOf(ToolCall("toolu_01", "getExchangeRate", """{"from": "USD", "to": "KES"}""")),
                done.response.toolCalls,
            )
            val deltas = events.filterIsInstance<ModelStreamEvent.ToolCallDelta>()
            assertEquals("toolu_01", deltas.first().id)
            assertEquals("getExchangeRate", deltas.first().name)
            assertTrue(deltas.all { it.index == 1 })
        }

    @Test
    fun `two calls one after the other stay apart, and a call with no arguments has an empty object`() =
        fakeAnthropic {
            answer = {
                sse(
                    start(),
                    toolStart(0, "toolu_a", "getAshaUsage"),
                    args(0, ""),
                    stop(0),
                    toolStart(1, "toolu_b", "getExchangeRate"),
                    args(1, """{"from":"""),
                    args(1, """"EUR"}"""),
                    stop(1),
                    end("tool_use"),
                )
            }

            val done = assertIs<ModelStreamEvent.Done>(events().last())

            assertNull(done.response.text)
            assertEquals(
                listOf(
                    ToolCall("toolu_a", "getAshaUsage", "{}"),
                    ToolCall("toolu_b", "getExchangeRate", """{"from":"EUR"}"""),
                ),
                done.response.toolCalls,
            )
        }

    @Test
    fun `the request has the model, thinking off, effort and cap per purpose, and a cached prefix`() =
        fakeAnthropic {
            events(request(ModelPurpose.VOICE))
            val voice = body()
            assertEquals("claude-haiku-5-5", voice["model"]!!.jsonPrimitive.content)
            assertEquals("disabled", voice["thinking"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals("low", voice["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
            assertEquals(1024, voice["max_tokens"]!!.jsonPrimitive.int)
            assertTrue(voice["stream"]!!.jsonPrimitive.content == "true")
            assertFalse("temperature" in voice || "top_p" in voice || "top_k" in voice || "tool_choice" in voice)
            assertEquals("ephemeral", voice["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            // the fixed system prompt is cached, this turn's part isn't
            val system = voice["system"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("You are Asha.", "- Mode: voice."), system.map { it["text"]!!.jsonPrimitive.content })
            assertTrue("cache_control" in system[0] && "cache_control" !in system[1])

            // the last tool carries the breakpoint; the schema is Anthropic's, with ranges and enums kept
            val tools = voice["tools"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("getAshaUsage", "getExchangeRate"), tools.map { it["name"]!!.jsonPrimitive.content })
            assertTrue("cache_control" !in tools[0] && "cache_control" in tools[1])
            val schema = tools[1]["input_schema"]!!.jsonObject
            assertEquals("object", schema["type"]!!.jsonPrimitive.content)
            assertEquals(listOf("from"), schema["required"]!!.jsonArray.map { it.jsonPrimitive.content })
            val properties = schema["properties"]!!.jsonObject
            assertEquals("1 to 30", properties["limit"]!!.jsonObject["description"]!!.jsonPrimitive.content)
            assertEquals("integer", properties["limit"]!!.jsonObject["type"]!!.jsonPrimitive.content)
            assertEquals(
                listOf("IN", "OUT"),
                properties["direction"]!!.jsonObject["enum"]!!.jsonArray.map {
                    it.jsonPrimitive.content
                },
            )
            assertEquals("array", properties["assets"]!!.jsonObject["type"]!!.jsonPrimitive.content)

            // the key goes in its header, with the API version
            assertEquals(AnthropicKit.KEY, last.headers["x-api-key"])
            assertEquals("2023-06-01", last.headers["anthropic-version"])
            assertEquals("/v1/messages", last.path)

            events(request(ModelPurpose.CHAT))
            assertEquals(4096, body()["max_tokens"]!!.jsonPrimitive.int)
        }

    @Test
    fun `a tool round trip goes back as tool_use, then every result of that turn in one user message`() =
        fakeAnthropic {
            events(
                request(
                    messages =
                        listOf(
                            ModelMessage.User("Rate and my usage?"),
                            ModelMessage.Assistant(
                                "Let me look.",
                                listOf(
                                    ToolCall("toolu_1", "getExchangeRate", """{"from":"USD"}"""),
                                    ToolCall("toolu_2", "getAshaUsage", "not json"),
                                ),
                            ),
                            ModelMessage.ToolResult("toolu_1", "getExchangeRate", """{"rate":"129.2"}"""),
                            ModelMessage.ToolResult("toolu_2", "getAshaUsage", "Skipped: call one tool at a time."),
                        ),
                ),
            )

            val messages = body()["messages"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("user", "assistant", "user"), messages.map { it["role"]!!.jsonPrimitive.content })
            val assistant = messages[1]["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("text", "tool_use", "tool_use"), assistant.map { it["type"]!!.jsonPrimitive.content })
            assertEquals("USD", assistant[1]["input"]!!.jsonObject["from"]!!.jsonPrimitive.content)
            assertEquals(JsonObject(emptyMap()), assistant[2]["input"], "arguments that aren't JSON go back as {}")
            val results = messages[2]["content"]!!.jsonArray.map { it.jsonObject }
            assertEquals(listOf("toolu_1", "toolu_2"), results.map { it["tool_use_id"]!!.jsonPrimitive.content })
            assertTrue(results.all { it["type"]!!.jsonPrimitive.content == "tool_result" })
            assertIs<JsonArray>(results[0]["content"])
        }

    @Test
    fun `a refusal before any word is Rejected, so the caller can fall back`() =
        fakeAnthropic {
            answer = { sse(start(), end("refusal", output = 0)) }

            val failed = assertIs<ModelStreamEvent.Failed>(events().single())

            assertIs<IntegrationError.Rejected>(failed.error)
        }

    @Test
    fun `max_tokens keeps the text written and drops a tool call cut off half way`() =
        fakeAnthropic {
            answer = {
                sse(
                    start(),
                    textStart(0),
                    text(0, "Here is what I found"),
                    stop(0),
                    toolStart(1, "toolu_1", "getExchangeRate"),
                    args(1, """{"from": "U"""),
                    end("max_tokens"),
                )
            }

            val done = assertIs<ModelStreamEvent.Done>(events().last())

            assertEquals("Here is what I found", done.response.text)
            assertTrue(done.response.toolCalls.isEmpty())
        }

    @Test
    fun `an answer with neither text nor a tool call is Unavailable`() =
        fakeAnthropic {
            answer = { sse(start(), textStart(0), stop(0), end("end_turn", output = 0)) }

            assertIs<IntegrationError.Unavailable>(assertIs<ModelStreamEvent.Failed>(events().single()).error)
        }

    @Test
    fun `refusals of what we sent are Rejected, everything else Unavailable, before the first event`() =
        fakeAnthropic {
            val cases =
                mapOf(
                    400 to IntegrationError.Rejected::class,
                    404 to IntegrationError.Rejected::class,
                    401 to IntegrationError.Unavailable::class,
                    429 to IntegrationError.Unavailable::class,
                    500 to IntegrationError.Unavailable::class,
                    529 to IntegrationError.Unavailable::class,
                )
            for ((status, kind) in cases) {
                answer = { refusal(status, """{"type":"error","error":{"type":"x","message":"secret body"}}""") }
                val opened = model().stream(request())
                assertIs<Resource.Error>(opened, "$status")
                assertEquals(kind, opened.error::class, "$status")
            }
        }

    @Test
    fun `an error event after the first words is Failed, keeping what was said`() =
        fakeAnthropic {
            answer = { slowSse(start(), textStart(0), text(0, "The dollar"), error("overloaded_error")) }

            val events = events()

            assertEquals(ModelStreamEvent.Text("The dollar"), events.first())
            assertIs<IntegrationError.Unavailable>(assertIs<ModelStreamEvent.Failed>(events.last()).error)
        }

    @Test
    fun `complete returns the whole answer`() =
        fakeAnthropic {
            answer = { sse(*textAnswer("One ", "two.")) }

            val whole = (model().complete(request()) as Resource.Success).data

            assertEquals("One two.", whole.text)
        }

    @Test
    fun `a tool schema outside the subset Asha uses is refused before any call`() =
        fakeAnthropic {
            val odd = ToolSpec("odd", "?", """{"type":"object","properties":{"x":{"oneOf":[{"type":"string"}]}}}""")

            val opened = model().stream(request(tools = listOf(odd)))

            assertIs<IntegrationError.Rejected>((opened as Resource.Error).error)
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `the key never reaches a log, Koog's included, while a call streams and while one is refused`() =
        fakeAnthropic {
            val appender = ListAppender<ILoggingEvent>().apply { start() }
            val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
            val koog = LoggerFactory.getLogger("ai.koog") as Logger
            val rootLevel = root.level
            val koogLevel = koog.level
            root.addAppender(appender)
            root.level = Level.TRACE
            koog.level = Level.TRACE
            try {
                answer = { sse(*textAnswer("Hi")) }
                events()
                answer = { refusal(401, """{"type":"error"}""") }
                model().stream(request())
            } finally {
                root.detachAppender(appender)
                root.level = rootLevel
                koog.level = koogLevel
            }
            assertTrue(appender.list.isNotEmpty())
            val logged = appender.list.joinToString("\n") { it.formattedMessage + " " + it.throwableProxy?.message }
            assertFalse(AnthropicKit.KEY in logged)
            assertTrue(
                appender.list.any {
                    "asha model call: purpose=VOICE model=claude-haiku-5-5" in
                        it.formattedMessage
                },
            )
            assertTrue(
                appender.list.any { it.level == Level.ERROR && "check ANTHROPIC_API_KEY" in it.formattedMessage },
            )
        }

    @Test
    fun `the usage line prices fresh, cached and written input and the output`() {
        val prices = AnthropicPrices(inputPerMillion = 0.10, outputPerMillion = 0.50)

        // 1,000 fresh (100 µ$) + 6,000 read at 0.1x (60 µ$) + 2,000 written at 1.25x (250 µ$) + 100 out (50 µ$)
        assertEquals(
            460,
            prices.microDollars(TokenUsage(input = 1000, output = 100, cacheRead = 6000, cacheWrite = 2000)),
        )
    }

    @Test
    fun `effort is low, medium or high only`() {
        assertEquals(AnthropicEffort.MEDIUM, AnthropicEffort.parse(" Medium "))
        kotlin.test.assertFailsWith<IllegalStateException> { AnthropicEffort.parse("max") }
    }
}
