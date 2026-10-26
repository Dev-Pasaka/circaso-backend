package com.circaso.assistant

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.assistant.domain.models.Limits
import com.circaso.assistant.domain.models.LiveEnd
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.LiveVoiceStart
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.LiveConversation
import com.circaso.assistant.domain.support.today
import com.circaso.integrations.api.AudioSpan
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
import java.util.UUID
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

    private suspend fun awaitLatch(latch: CountDownLatch) =
        withContext(Dispatchers.IO) { assertTrue(latch.await(5, TimeUnit.SECONDS)) }

    private suspend fun eventually(check: suspend () -> Boolean) =
        withTimeout(5_000) {
            while (!check()) delay(20)
        }

    private suspend fun DefaultClientWebSocketSession.hangUp(into: Received): CloseReason? {
        send(Frame.Text("""{"type":"end"}"""))
        return readToClose(into)
    }

    private fun voiceSeconds(live: LiveHarness) =
        runBlocking {
            live.h.quota.voiceSeconds(live.userId, Clock.systemUTC().today())
        }

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
                assertEquals("mulaw", ready["audio"]!!.jsonObject.text("encoding"))
                assertEquals("8000", ready["audio"]!!.jsonObject.text("sampleRate"))

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
            val asked = CountDownLatch(1)
            val thinking = CountDownLatch(1)
            live.h.model
                .then {
                    asked.countDown()
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
                awaitLatch(asked) // the model has the first question: Asha is thinking

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
    fun `frames are at most 64 KB both ways`() =
        testApplication {
            // the scripted voice's audio is the sentence's bytes, so a ~90 KB sentence needs two frames
            val live = LiveHarness(Harness())
            live.serve(this)
            val long = "Asha talks for a while. " + "la ".repeat(30_000).trim() + "."
            live.h.model.say(long)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(TranscriptionEvent.Final("talk to me", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }

                send(Frame.Binary(true, ByteArray(64 * 1024 + 1)))
                val closed = readToClose(got)
                assertEquals(CloseReason.Codes.TOO_BIG.code, closed?.code)
            }
            val audio = got.frames.filterIsInstance<ByteArray>()
            assertTrue(audio.all { it.size <= 64 * 1024 })
            assertTrue(audio.size > 2, "the long sentence was split")
            assertEquals(
                long.length - 1,
                audio.sumOf { it.size },
                "every byte arrives (the space between sentences aside)",
            )
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
                val ears = live.ears.session()
                ears.push(TranscriptionEvent.SpeechStarted)
                val closed =
                    withTimeout(8_000) {
                        // the user keeps talking, without ever finishing the turn
                        while (closeReason.isActive) {
                            ears.push(TranscriptionEvent.Interim("and another thing"))
                            delay(100)
                        }
                        closeReason.await()
                    }

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code)
                assertEquals("assistant.quota_exceeded", closed?.message)
            }
            val used = live.h.quota.voiceSeconds(live.userId, Clock.systemUTC().today())
            assertTrue(used <= 3, "never charged past the cap (was $used)")
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

            for (token in listOf("agent-token", "forged", "revoked-token", "boom")) {
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

    // ---- metering: speech only ---------------------------------------------------------------------------------

    @Test
    fun `an open mic with nobody speaking is not charged`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                repeat(15) {
                    send(Frame.Binary(true, ByteArray(60)))
                    delay(100)
                }
                hangUp(got)
            }
            assertEquals(
                15,
                live.ears.sessions
                    .single()
                    .frames.size,
                "the audio reached the transcriber",
            )
            assertEquals(0, voiceSeconds(live), "silence is free")
        }

    @Test
    fun `without timestamps the user's speech is charged by arrival, from the first word to the last`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model.say("Ok.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                ears.push(TranscriptionEvent.SpeechStarted)
                repeat(5) {
                    delay(500)
                    ears.push(TranscriptionEvent.Interim("still talking"))
                }
                delay(1_500) // a pause before the end of the turn is not speech
                ears.push(TranscriptionEvent.Final("still talking about money", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                hangUp(got)
            }
            assertEquals(
                2,
                voiceSeconds(live),
                "2 s from the first word to the last; voice activity before them and the 1.5 s pause are free " +
                    "(was ${voiceSeconds(live)})",
            )
        }

    @Test
    fun `Asha's playback is metered exactly`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model.say("a".repeat(15_999) + ".") // 16,000 bytes of 8 kHz mulaw: 2 s
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(TranscriptionEvent.Final("read me something", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                delay(2_400) // let the phone play it all: audio is charged as it plays
                hangUp(got)
            }
            assertEquals(16_000, got.frames.filterIsInstance<ByteArray>().sumOf { it.size })
            assertEquals(2, voiceSeconds(live))
        }

    @Test
    fun `a long reply talked over after about a second charges about a second`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model.say("a".repeat(159_999) + ".") // 20 s of 8 kHz mulaw
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                ears.push(TranscriptionEvent.Final("tell me a story", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                delay(1_000)
                ears.push(TranscriptionEvent.SpeechStarted)
                readUntil(got) { it.ofType("interrupted").isNotEmpty() }
                hangUp(got)
            }
            val charged = voiceSeconds(live)
            assertTrue(charged in 1..2, "only what played before the barge-in is charged (was $charged)")
        }

    @Test
    fun `speech is metered on the transcriber's clock, not on when its events arrive`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model
                .say("Ok.")
                .say("Ok.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                // a burst: 5 s of speech reported all at once
                ears.push(
                    TranscriptionEvent.SpeechStarted,
                    TranscriptionEvent.Interim("one", AudioSpan(1.0, 1.4)),
                    TranscriptionEvent.Interim("one two three", AudioSpan(1.0, 3.5)),
                    TranscriptionEvent.Final("one two three four", endOfTurn = true, audio = AudioSpan(1.0, 6.0)),
                )
                readUntil(got) { it.ofType("reply_done").size == 1 }
                hangUp(got)
            }
            assertEquals(5, voiceSeconds(live), "the burst's 5 s of audio time")
        }

    @Test
    fun `a turn heard only as a final charges its duration`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model.say("Ok.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(
                    TranscriptionEvent.Final("send Mum 500", endOfTurn = true, audio = AudioSpan(10.0, 12.0)),
                )
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                hangUp(got)
            }
            assertEquals(2, voiceSeconds(live))
        }

    // ---- silence, session, device waits ------------------------------------------------------------------------

    @Test
    fun `speech resets the silence timer`() =
        testApplication {
            val live = LiveHarness(Harness(AssistantConfig(liveSilence = Duration.ofMillis(800))))
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                val opened = System.nanoTime()
                repeat(6) {
                    ears.push(TranscriptionEvent.Interim("hmm"))
                    delay(300)
                }
                assertTrue(closeReason.isActive, "still open after 1.8 s of talking, with a 0.8 s silence timeout")
                val closed = readToClose(got)
                val seconds = (System.nanoTime() - opened) / 1e9

                assertEquals("silence", closed?.message)
                assertTrue(seconds >= 2.4, "closed only after the talking stopped (after $seconds s)")
            }
        }

    @Test
    fun `a session revoked before a turn closes the socket as unauthorized, without a closing frame`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.verifier.revoked = true
                live.ears.session().push(TranscriptionEvent.Final("what is my balance", endOfTurn = true))
                val closed = readToClose(got)

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code)
                assertEquals("unauthorized", closed?.message)
            }
            assertTrue(got.ofType("closing").isEmpty())
            assertTrue(
                live.h.model.requests
                    .isEmpty(),
                "a revoked session asks nothing",
            )
            assertTrue(
                live.h.memory.messages
                    .isEmpty(),
            )
        }

    @Test
    fun `the session is re-checked between turns, and fresh tokens on the socket keep it going`() =
        testApplication {
            val live = LiveHarness(Harness(AssistantConfig(liveSessionRecheck = Duration.ofMillis(300))))
            live.serve(this)
            live.h.model
                .call("searchContacts", """{"query":"mum"}""")
                .say("Still here.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                send(Frame.Text("""{"type":"auth","accessToken":"fresh-token","agentToken":"agent-2"}"""))
                delay(200)
                live.verifier.expired += "user-token" // the first token's 15 minutes are up
                delay(700)
                live.ears.session().push(TranscriptionEvent.Final("who is mum", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                assertEquals(
                    "agent-2",
                    live.h.tools.calls
                        .single()
                        .token,
                    "the tools act with the fresh agent token",
                )

                live.verifier.revoked = true
                val closed = readToClose(got)
                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code)
                assertEquals("unauthorized", closed?.message)
            }
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
    fun `a device tool round trip on the socket, and answers that match no question`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model
                .call("searchChats", """{"query":"december contribution"}""")
                .say("The chama agreed on 500 shillings.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(TranscriptionEvent.Final("what did the chama agree", endOfTurn = true))
                readUntil(got) { it.ofType("tool_request").isNotEmpty() }
                val request = got.ofType("tool_request").single()
                assertEquals(
                    """{"query":"december contribution"}""",
                    request.text("arguments"),
                    "a JSON string, as on A2",
                )

                send(
                    Frame.Text(
                        """{"type":"tool_results","turnId":"${request.text(
                            "turnId",
                        )}","callId":"${request.text("callId")}",""" +
                            """"snippets":[{"chatId":"family","chatName":"Family","sender":"Amina",""" +
                            """"at":"2026-10-20T10:00:00Z","text":"500 each in December"}]}""",
                    ),
                )
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }

                send(Frame.Text("""{"type":"tool_results","turnId":"not-a-uuid","callId":"c1"}"""))
                readUntil(got) { it.ofType("error").size == 1 }
                send(Frame.Text("""{"type":"tool_results","turnId":"${UUID.randomUUID()}","callId":"c1"}"""))
                readUntil(got) { it.ofType("error").size == 2 }
                hangUp(got)
            }
            assertEquals("The chama agreed on 500 shillings.", got.ofType("reply").single().text("text"))
            assertEquals(
                listOf("request.malformed", "assistant.turn_not_found"),
                got.ofType("error").map { it.text("code") },
            )
            assertTrue(
                live.h.model.requests
                    .last()
                    .messages
                    .any { "500 each in December" in it.toString() },
            )
        }

    @Test
    fun `a device tool the phone never answers ends its turn with an error, and the conversation goes on`() =
        testApplication {
            val live = LiveHarness(Harness(AssistantConfig(liveDeviceToolTimeout = Duration.ofMillis(400))))
            live.serve(this)
            live.h.model
                .call("searchChats")
                .say("Hello again.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                ears.push(TranscriptionEvent.Final("search my chats", endOfTurn = true))
                readUntil(got) { it.ofType("error").isNotEmpty() }
                assertEquals("assistant.turn_not_found", got.ofType("error").single().text("code"))

                ears.push(TranscriptionEvent.Final("hello", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                hangUp(got)
            }
            assertEquals("Hello again.", got.ofType("reply").single().text("text"))
        }

    // ---- failures ---------------------------------------------------------------------------------------------

    @Test
    fun `a provider dropping mid-conversation closes with model_unavailable`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(TranscriptionEvent.Failed(IntegrationError.Unavailable("nova-3")))
                val closed = readToClose(got)

                assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, closed?.code)
                assertEquals("assistant.model_unavailable", closed?.message)
            }
            assertEquals("assistant.model_unavailable", got.ofType("closing").single().text("reason"))
            assertTrue(
                live.voice.sessions
                    .single()
                    .isClosed,
            )
        }

    @Test
    fun `a provider down at opening refuses with model_unavailable`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.ears.unavailable = true
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                val closed = readToClose(got)

                assertEquals(CloseReason.Codes.INTERNAL_ERROR.code, closed?.code)
                assertEquals("assistant.model_unavailable", closed?.message)
            }
            assertTrue(got.ofType("ready").isEmpty())
            assertTrue(live.voice.sessions.isEmpty())
        }

    @Test
    fun `the text allowance running out on a spoken turn closes with quota_exceeded`() =
        testApplication {
            val live = LiveHarness(Harness(smallLimits(turns = 0)))
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(TranscriptionEvent.Final("one more question", endOfTurn = true))
                val closed = readToClose(got)

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code)
                assertEquals("assistant.quota_exceeded", closed?.message)
            }
            assertTrue(
                got
                    .ofType("closing")
                    .single()["retryAfterSeconds"]!!
                    .jsonPrimitive.long > 0,
            )
        }

    @Test
    fun `a failed turn sends an error frame and the conversation goes on`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model
                .fail()
                .say("Back again.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                ears.push(TranscriptionEvent.Final("first try", endOfTurn = true))
                readUntil(got) { it.ofType("error").isNotEmpty() }
                ears.push(TranscriptionEvent.Final("second try", endOfTurn = true))
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                hangUp(got)
            }
            assertEquals("assistant.model_unavailable", got.ofType("error").single().text("code"))
            assertEquals("Back again.", got.ofType("reply").single().text("text"))
        }

    // ---- ending without leaks ---------------------------------------------------------------------------------

    @Test
    fun `hanging up mid-turn saves the turn, sends nothing more and closes both provider sessions`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val asked = CountDownLatch(1)
            val thinking = CountDownLatch(1)
            live.h.model.then {
                asked.countDown()
                thinking.await(5, TimeUnit.SECONDS)
                Resource.success(ModelResponse("Late answer.", emptyList(), ModelUsage(10, 5)))
            }
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                live.ears.session().push(TranscriptionEvent.Final("a slow question", endOfTurn = true))
                awaitLatch(asked)
                val closed = hangUp(got)
                assertEquals("ended", closed?.message)
            }
            thinking.countDown()
            eventually { live.h.memory.messages.size == 2 }

            assertEquals("closing", got.json.last().type(), "nothing after the closing frame")
            assertTrue(got.ofType("reply").isEmpty() && got.ofType("reply_done").isEmpty())
            assertTrue(
                live.ears.sessions
                    .single()
                    .isClosed &&
                    live.voice.sessions
                        .single()
                        .isClosed,
            )
            assertTrue(
                live.voice.sessions
                    .single()
                    .spoken
                    .isEmpty(),
                "the late answer is not spoken",
            )
        }

    @Test
    fun `a phone that drops without saying goodbye leaves no provider session open`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                close(CloseReason(CloseReason.Codes.GOING_AWAY, "tunnel"))
            }
            eventually {
                live.ears.sessions
                    .single()
                    .isClosed &&
                    live.voice.sessions
                        .single()
                        .isClosed
            }
        }

    @Test
    fun `a conversation cancelled from outside, or never run, still closes both provider sessions`() =
        runBlocking {
            fun conversation(): LiveConversation {
                val line =
                    LiveVoiceLine(
                        UserId.new(),
                        LiveVoiceStart("user-token", null, "en", true, null),
                        ScriptedEars.Session(),
                        RecordingVoice().Session(),
                        120,
                        120,
                        Duration.ofSeconds(30),
                        Duration.ofSeconds(60),
                        Duration.ofSeconds(30),
                    )
                return LiveConversation(
                    line,
                    ask = { _, _ -> error("not asked") },
                    answerDevice = { error("not asked") },
                    meter = { Resource.success(100L) },
                    checkSession = { _, _ -> true },
                )
            }

            fun LiveConversation.isReleased() =
                (line.transcription as ScriptedEars.Session).isClosed &&
                    (line.speech as RecordingVoice.Session).isClosed

            val cancelled = conversation()
            coroutineScope {
                val running = async(start = CoroutineStart.UNDISPATCHED) { cancelled.run { } }
                delay(100)
                running.cancel()
            }
            assertTrue(cancelled.isReleased(), "cancelled while running")

            val neverRun = conversation()
            neverRun.release()
            assertTrue(neverRun.isReleased(), "released after a failed opening")

            val hungUp = conversation()
            val end =
                coroutineScope {
                    val running = async { hungUp.run { } }
                    hungUp.hangUp()
                    running.await()
                }
            assertEquals(LiveEnd.HungUp, end)
            assertTrue(hungUp.isReleased())
        }

    // ---- the agent token is the user's own --------------------------------------------------------------------

    @Test
    fun `a refresh with someone else's token, or an agent token as the access token, closes as unauthorized`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)

            for (refresh in listOf(
                """{"type":"auth","accessToken":"other-user-token"}""",
                """{"type":"auth","accessToken":"agent-token"}""",
                """{"type":"auth","accessToken":"fresh-token","agentToken":"other-agent"}""",
            )) {
                val got = Received()
                phone().webSocket("/v1/assistant/voice/live") {
                    auth()
                    readUntil(got) { it.ofType("ready").isNotEmpty() }
                    send(Frame.Text(refresh))
                    val closed = readToClose(got)

                    assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, closed?.code, refresh)
                    assertEquals("unauthorized", closed?.message, refresh)
                }
                assertTrue(got.ofType("closing").isEmpty(), refresh)
            }
        }

    @Test
    fun `an agent token that isn't the user's own is refused at the opening`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth(agentToken = "other-agent")
                assertEquals("unauthorized", readToClose(got)?.message)
            }
            assertTrue(got.frames.isEmpty())
            assertTrue(live.ears.sessions.isEmpty())
        }

    @Test
    fun `voice activity with no words is never charged`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                repeat(4) {
                    ears.push(TranscriptionEvent.SpeechStarted) // a cough, a passing car
                    delay(1_000)
                }
                hangUp(got)
            }
            assertEquals(0, voiceSeconds(live))
        }

    @Test
    fun `repeated voice activity doesn't add to the transcriber's timed speech`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            live.h.model.say("Ok.")
            val got = Received()

            phone().webSocket("/v1/assistant/voice/live") {
                auth()
                readUntil(got) { it.ofType("ready").isNotEmpty() }
                val ears = live.ears.session()
                ears.push(TranscriptionEvent.SpeechStarted, TranscriptionEvent.Interim("send", AudioSpan(0.2, 1.0)))
                delay(1_500)
                ears.push(
                    TranscriptionEvent.SpeechStarted,
                    TranscriptionEvent.Interim("send Mum five hundred", AudioSpan(0.2, 3.2)),
                    TranscriptionEvent.Final("send Mum five hundred", endOfTurn = true, audio = AudioSpan(0.2, 3.2)),
                )
                readUntil(got) { it.ofType("reply_done").isNotEmpty() }
                hangUp(got)
            }
            assertEquals(3, voiceSeconds(live), "exactly the 3 s span the transcriber timed")
        }
}
