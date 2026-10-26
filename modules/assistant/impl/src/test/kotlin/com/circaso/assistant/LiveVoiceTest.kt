package com.circaso.assistant

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.today
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.Resource
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun JsonObject.type() = this["type"]?.jsonPrimitive?.content

/** A11, the live voice socket, end to end on Ktor's test host with a scripted transcription, voice and model. */
class LiveVoiceTest {
    private val logs = ListAppender<ILoggingEvent>()
    private val assistantLog = LoggerFactory.getLogger("com.circaso.assistant") as Logger

    @BeforeTest
    fun listen() {
        logs.start()
        assistantLog.addAppender(logs)
        assistantLog.level = Level.DEBUG
    }

    @AfterTest
    fun stop() {
        assistantLog.detachAppender(logs)
    }

    /** What the phone received: JSON text frames and audio, in order. */
    private class Received {
        val frames = mutableListOf<Any>()

        val json: List<JsonObject> get() = frames.filterIsInstance<JsonObject>()

        fun ofType(type: String) = json.filter { it.type() == type }
    }

    private fun JsonObject.text(field: String) = this[field]!!.jsonPrimitive.content

    private suspend fun DefaultClientWebSocketSession.auth(
        token: String = "user-token",
        language: String? = "en",
        agentToken: String = "agent-tok",
    ) = send(
        Frame.Text(
            """{"type":"auth","accessToken":"$token","agentToken":"$agentToken"""" +
                (language?.let { ""","language":"$it"""" } ?: "") + "}",
        ),
    )

    /** Reads frames into [into] until [done] holds, failing after a few seconds. */
    private suspend fun DefaultClientWebSocketSession.readUntil(into: Received, done: (Received) -> Boolean) =
        withTimeout(5_000) {
            while (!done(into)) {
                when (val frame = incoming.receive()) {
                    is Frame.Text -> into.frames += Json.parseToJsonElement(frame.readText()).jsonObject
                    is Frame.Binary -> into.frames += frame.readBytes()
                    else -> Unit
                }
            }
        }

    /** Reads to the end of the socket; the close reason, or null if it closed without one. */
    private suspend fun DefaultClientWebSocketSession.readToClose(into: Received): CloseReason? {
        withTimeout(5_000) {
            for (frame in incoming) {
                when (frame) {
                    is Frame.Text -> into.frames += Json.parseToJsonElement(frame.readText()).jsonObject
                    is Frame.Binary -> into.frames += frame.readBytes()
                    else -> Unit
                }
            }
        }
        return closeReason.await()
    }

    private fun ApplicationTestBuilder.phone() = createClient { install(WebSockets) }

    private suspend fun ScriptedEars.session() = withTimeout(3_000) { opened.receive() }

    @Test
    fun `a spoken turn is an A2 turn, and the reply comes back as text and audio, sentence by sentence`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model
                .call("searchContacts", """{"query":"Mum"}""")
                .say("You sent Mum 2,000 shillings yesterday. Anything else?")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ready = got.ofType("ready").single()
                assertEquals(120, ready["voiceSecondsLeft"]!!.jsonPrimitive.long)
                assertEquals("linear16", ready["audio"]!!.jsonObject.text("encoding"))
                assertEquals("16000", ready["audio"]!!.jsonObject.text("sampleRate"))

                val ears = live.ears.session()
                send(Frame.Binary(true, byteArrayOf(1, 2, 3)))
                ears.push(
                    TranscriptionEvent.SpeechStarted,
                    TranscriptionEvent.Interim("how much did"),
                    TranscriptionEvent.Final("how much did I send Mum", endOfTurn = true),
                )
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                send(Frame.Text("""{"type":"end"}"""))
                val closed = readToClose(got)

                assertContentEquals(byteArrayOf(1, 2, 3), ears.frames.single(), "the phone's Opus reaches Nova-3")
                assertEquals(CloseReason.Codes.NORMAL.code, closed?.code)
                assertEquals("ended", got.ofType("closing").single().text("reason"))
            }

            val transcripts = got.ofType("transcript")
            assertEquals("how much did", transcripts.first().text("text"))
            assertEquals(false, transcripts.first()["final"]!!.jsonPrimitive.boolean)
            assertEquals("how much did I send Mum", transcripts.last().text("text"))
            assertEquals("how much did I send Mum", got.ofType("turn").single().text("text"))

            // Each sentence is a text frame followed by its audio.
            val afterTurn = got.frames.dropWhile { !(it is JsonObject && it.type() == "turn") }.drop(1)
            val replies = afterTurn.takeWhile { !(it is JsonObject && it.type() == "reply_done") }
            assertEquals(4, replies.size)
            assertEquals("You sent Mum 2,000 shillings yesterday.", (replies[0] as JsonObject).text("text"))
            assertEquals("You sent Mum 2,000 shillings yesterday.", String(replies[1] as ByteArray))
            assertEquals("Anything else?", (replies[2] as JsonObject).text("text"))
            assertEquals("Anything else?", String(replies[3] as ByteArray))
            val done = got.ofType("reply_done").single()
            assertEquals(true, done["spoken"]!!.jsonPrimitive.boolean)
            assertEquals(
                "You sent Mum 2,000 shillings yesterday. Anything else?",
                done["message"]!!.jsonObject.text("text"),
            )

            // Exactly A2: the agent token reached the tools, the turn counted, the exchange and its audit were saved.
            assertEquals(
                "agent-tok",
                live.h.tools.calls
                    .single()
                    .token,
            )
            val me = live.userId
            val day = Clock.systemUTC().today()
            assertEquals(1, live.h.quota.textTurns(me, day))
            assertEquals(
                listOf(Role.USER to "how much did I send Mum", Role.ASHA to done["message"]!!.jsonObject.text("text")),
                live.h.memory.messages
                    .sortedBy { it.messageId }
                    .map { it.role to it.text },
            )
            assertEquals(
                "TURN",
                live.h.memory.audit
                    .single()
                    .kind,
            )
            assertTrue(live.h.quota.voiceSeconds(me, day) >= 1, "voice seconds are metered")
            assertTrue(
                live.ears.sessions
                    .single()
                    .isClosed &&
                    live.voice.sessions
                        .single()
                        .isClosed,
            )
        }

    @Test
    fun `nothing said is stored beyond the turn's text, and no log line carries audio or words`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model.say("Your secret balance is 4,200 shillings.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(TranscriptionEvent.Final("what is my hidden balance", endOfTurn = true))
                send(Frame.Binary(true, "opus-bytes-xyz".toByteArray()))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                send(Frame.Text("""{"type":"end"}"""))
                readToClose(got)
            }

            assertEquals(2, live.h.memory.messages.size, "the question and the answer as text, nothing else")
            assertTrue(
                live.h.memory.audit
                    .none { "hidden balance" in it.detail || "secret balance" in it.detail },
            )
            val lines = logs.list.map { it.formattedMessage + " " + it.argumentArray.orEmpty().joinToString() }
            assertTrue(lines.any { "live voice closed" in it }, "the socket's life is logged")
            assertTrue(
                lines.none { "hidden balance" in it || "secret balance" in it || "opus-bytes" in it },
                "no log line carries what was said or heard",
            )
        }

    @Test
    fun `talking over Asha clears her speech and the new words become the next turn`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val thinking = CountDownLatch(1)
            live.h.model
                .then {
                    thinking.await(5, TimeUnit.SECONDS)
                    Resource.success(ModelResponse("Your balance is 4,200 shillings.", emptyList(), ModelUsage(10, 5)))
                }.say("I'm well, thank you.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                ears.push(TranscriptionEvent.Final("what is my balance", endOfTurn = true))
                readUntil(got) { it.ofType("turn").isNotEmpty() }

                ears.push(TranscriptionEvent.SpeechStarted)
                readUntil(got) { it.ofType("interrupted").isNotEmpty() }
                ears.push(TranscriptionEvent.Final("never mind, how are you", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }

                thinking.countDown()
                readUntil(got) { it.ofType("reply_done").size == 2 }
                send(Frame.Text("""{"type":"end"}"""))
                readToClose(got)
            }

            assertEquals(
                1,
                live.voice.sessions
                    .single()
                    .clears
                    .get(),
                "the speech provider's queue was cleared",
            )
            assertEquals(
                listOf("what is my balance", "never mind, how are you"),
                got.ofType("turn").map { it.text("text") },
            )
            assertEquals(listOf("I'm well, thank you."), got.ofType("reply").map { it.text("text") })
            assertEquals(listOf("I'm well, thank you."), got.frames.filterIsInstance<ByteArray>().map { String(it) })
            val (heard, stale) = got.ofType("reply_done")
            assertEquals(true, heard["spoken"]!!.jsonPrimitive.boolean)
            assertEquals(false, stale["spoken"]!!.jsonPrimitive.boolean, "the reply talked over is not spoken")
            assertEquals(4, live.h.memory.messages.size, "both turns are still in the Asha chat")
        }

    @Test
    fun `a user with no voice allowance left is refused with the reset time`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.quota.addVoiceSeconds(live.userId, Clock.systemUTC().today(), 120)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                val closed = readToClose(got)

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code)
                assertEquals("assistant.quota_exceeded", closed?.message)
            }
            val closing = got.ofType("closing").single()
            assertEquals("assistant.quota_exceeded", closing.text("reason"))
            assertTrue(closing["retryAfterSeconds"]!!.jsonPrimitive.long in 1..86_400)
            assertTrue(live.ears.sessions.isEmpty(), "no provider session is opened")
        }

    @Test
    fun `the socket closes when the voice allowance runs out mid-conversation`() =
        testApplication {
            val live = LiveHarness(Harness(AssistantConfig(free = Limits(20, 3, 6))))
            live.serve(this)
            live.h.quota.addVoiceSeconds(live.userId, Clock.systemUTC().today(), 1)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                assertEquals(
                    2,
                    got
                        .ofType("ready")
                        .single()["voiceSecondsLeft"]!!
                        .jsonPrimitive.long,
                )
                val closed =
                    withTimeout(8_000) {
                        while (closeReason.isActive) {
                            runCatching { send(Frame.Binary(true, ByteArray(60))) }
                            delay(100)
                        }
                        closeReason.await()
                    }

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code)
                assertEquals("assistant.quota_exceeded", closed?.message)
            }
            assertEquals(3, live.h.quota.voiceSeconds(live.userId, Clock.systemUTC().today()))
        }

    @Test
    fun `the socket closes after the silence timeout`() =
        testApplication {
            val live = LiveHarness(Harness(AssistantConfig(liveSilence = Duration.ofMillis(600))))
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                val closed = readToClose(got)

                assertEquals(CloseReason.Codes.NORMAL.code, closed?.code)
                assertEquals("silence", closed?.message)
            }
            assertEquals("silence", got.ofType("closing").single().text("reason"))
        }

    @Test
    fun `Swahili is refused with a clear reason, so the phone uses clips and its own voice`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth(language = "sw")
                val closed = readToClose(got)

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code)
                assertEquals("assistant.language_not_supported", closed?.message)
            }
            assertEquals("assistant.language_not_supported", got.ofType("closing").single().text("reason"))
            assertTrue(got.ofType("ready").isEmpty())
            assertTrue(live.ears.sessions.isEmpty() && live.voice.sessions.isEmpty())
        }

    @Test
    fun `the agent token, a bad token and a missing auth frame are refused like on the main socket`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)

            for (token in listOf("agent-token", "forged")) {
                val got = Received()
                phone().webSocket("/v1/assistant/voice/live") {
                    auth(token = token)
                    val closed = readToClose(got)

                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code, token)
                    assertEquals("unauthorized", closed?.message, token)
                }
                assertTrue(got.frames.isEmpty(), token)
            }
            phone().webSocket("/v1/assistant/voice/live") {
                send(Frame.Binary(true, byteArrayOf(1)))
                assertEquals("auth required", readToClose(Received())?.message)
            }
            assertTrue(live.ears.sessions.isEmpty())
            assertNull(
                live.h.memory.messages
                    .firstOrNull(),
            )
        }
}
