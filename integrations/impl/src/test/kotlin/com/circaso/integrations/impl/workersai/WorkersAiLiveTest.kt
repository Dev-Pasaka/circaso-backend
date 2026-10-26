package com.circaso.integrations.impl.workersai

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.integrations.api.AudioEncoding
import com.circaso.integrations.api.AudioSpan
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LiveAudioFormat
import com.circaso.integrations.api.LiveTranscriptionOptions
import com.circaso.integrations.api.SpeechEvent
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.Resource
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.Headers
import io.ktor.http.Parameters
import io.ktor.server.application.install
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import io.ktor.websocket.readText
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.ktor.server.websocket.WebSockets as ServerWebSockets

/** Nova-3 and Aura on Workers AI's live sockets, against a fake vendor on Ktor's test host. */
class WorkersAiLiveTest {
    /** What the fake vendor saw: the path, query and headers of each socket, and every frame the client sent. */
    private class Vendor {
        val opened = Channel<Triple<String, Parameters, Headers>>(Channel.UNLIMITED)
        val received = Channel<Frame>(Channel.UNLIMITED)

        /** What the vendor sends once a socket is open: a script run against the session. */
        var script: suspend DefaultWebSocketServerSession.() -> Unit = {}
    }

    private val logs = ListAppender<ILoggingEvent>()
    private val workersAiLog = LoggerFactory.getLogger("com.circaso.integrations.impl.workersai") as Logger

    @BeforeTest
    fun listen() {
        logs.start()
        workersAiLog.addAppender(logs)
        workersAiLog.level = Level.DEBUG
    }

    @AfterTest
    fun stop() {
        workersAiLog.detachAppender(logs)
    }

    private fun ApplicationTestBuilder.vendor(): Vendor {
        val vendor = Vendor()
        application {
            install(ServerWebSockets)
            routing {
                webSocket("/client/v4/accounts/{account}/ai/run/{author}/{family}/{model}") {
                    val path =
                        call.request.local.uri
                            .substringBefore('?')
                    vendor.opened.send(Triple(path, call.request.queryParameters, call.request.headers))
                    vendor.script(this)
                    for (frame in incoming) vendor.received.send(frame.copy())
                }
            }
        }
        return vendor
    }

    private fun ApplicationTestBuilder.socket() =
        WorkersAiLiveSocket(
            http = createClient { install(WebSockets) },
            token = "cf-token",
            gateway = "asha",
            socketBase = "ws://localhost/client/v4/accounts/acc123/ai/",
        )

    private suspend fun <T> Channel<T>.next(): T = withTimeout(3_000) { receive() }

    private fun results(
        text: String,
        isFinal: Boolean,
        speechFinal: Boolean = false,
        words: String = "",
    ) = Frame.Text(
        """{"type":"Results","channel":{"alternatives":[{"transcript":"$text","confidence":0.9""" +
            (if (words.isEmpty()) "" else ""","words":[$words]""") +
            """}]},"is_final":$isFinal,"speech_final":$speechFinal,"start":0.5,"duration":1.2}""",
    )

    @Test
    fun `nova-3 opens on the model route with Opus, the turn options and mip_opt_out, through the gateway`() =
        testApplication {
            val vendor = vendor()
            val live = WorkersAiLiveTranscription(socket(), "@cf/deepgram/nova-3")

            val session = (live.open(LiveTranscriptionOptions("en-KE")) as Resource.Success).data
            val (path, query, headers) = vendor.opened.next()

            assertEquals("/client/v4/accounts/acc123/ai/run/@cf/deepgram/nova-3", path.replace("%40", "@"))
            assertEquals("opus", query["encoding"])
            assertEquals("16000", query["sample_rate"])
            assertEquals("en-KE", query["language"])
            assertEquals("true", query["interim_results"])
            assertEquals("400", query["endpointing"])
            assertEquals("1000", query["utterance_end_ms"])
            assertEquals("true", query["vad_events"])
            assertEquals("true", query["mip_opt_out"])
            assertEquals("Bearer cf-token", headers["Authorization"])
            assertEquals("asha", headers["cf-aig-gateway-id"])
            assertEquals("false", headers["cf-aig-collect-log"])
            session.close()
        }

    @Test
    fun `audio goes out as binary frames and Nova-3's messages come back as our events`() =
        testApplication {
            val vendor = vendor()
            vendor.script = {
                send(Frame.Text("""{"type":"Metadata","request_id":"r1"}"""))
                send(Frame.Text("""{"type":"SpeechStarted","channel":[0],"timestamp":0.1}"""))
                send(results("", isFinal = false))
                send(results("how much did", isFinal = false))
                send(
                    results(
                        "how much did I send",
                        isFinal = true,
                        words = """{"word":"how","start":0.62,"end":0.8},{"word":"send","start":1.3,"end":1.55}""",
                    ),
                )
                send(results("mum", isFinal = true, speechFinal = true))
                send(Frame.Text("""{"type":"UtteranceEnd","channel":[0,1],"last_word_end":2.1}"""))
            }
            val session =
                (
                    WorkersAiLiveTranscription(socket(), "@cf/deepgram/nova-3").open(LiveTranscriptionOptions(null)) as
                        Resource.Success
                ).data
            vendor.opened.next()

            session.send(byteArrayOf(1, 2, 3))
            assertContentEquals(byteArrayOf(1, 2, 3), vendor.received.next().readBytes())

            assertEquals(TranscriptionEvent.SpeechStarted, session.next())
            assertEquals(
                TranscriptionEvent.Interim("how much did", AudioSpan(0.5, 1.7)),
                session.next(),
                "an empty interim is skipped; without words the result's window is the span",
            )
            assertEquals(
                TranscriptionEvent.Final("how much did I send", endOfTurn = false, audio = AudioSpan(0.62, 1.55)),
                session.next(),
                "the first word's start to the last word's end",
            )
            assertEquals(TranscriptionEvent.Final("mum", endOfTurn = true, audio = AudioSpan(0.5, 1.7)), session.next())
            assertEquals(TranscriptionEvent.TurnEnded, session.next())

            session.close()
            assertEquals("""{"type":"CloseStream"}""", (vendor.received.next() as Frame.Text).readText())
            assertNull(session.next(), "a session we closed just ends")
            assertTrue(
                logs.list.none { "how much" in it.formattedMessage || "mum" in it.formattedMessage },
                "transcripts never reach a log",
            )
        }

    @Test
    fun `a session the vendor closes reports one failure and then ends`() =
        testApplication {
            val vendor = vendor()
            vendor.script = { close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "DATA-0000")) }
            val session =
                (
                    WorkersAiLiveTranscription(socket(), "@cf/deepgram/nova-3").open(LiveTranscriptionOptions("en")) as
                        Resource.Success
                ).data

            val failed = assertIs<TranscriptionEvent.Failed>(session.next())
            assertIs<IntegrationError.Unavailable>(failed.error)
            assertNull(session.next())
        }

    @Test
    fun `Swahili is refused before any socket opens, so the phone falls back to clips`() =
        testApplication {
            val vendor = vendor()

            val refused =
                WorkersAiLiveTranscription(
                    socket(),
                    "@cf/deepgram/nova-3",
                ).open(LiveTranscriptionOptions("sw"))
            val speech = WorkersAiLiveSpeech(socket(), "@cf/deepgram/aura-2-en").open("sw-KE")

            assertIs<IntegrationError.UnsupportedLanguage>((refused as Resource.Error).error)
            assertIs<IntegrationError.UnsupportedLanguage>((speech as Resource.Error).error)
            assertTrue(vendor.opened.tryReceive().isFailure)
        }

    @Test
    fun `a socket that doesn't open is unavailable, and the log holds no URL`() =
        testApplication {
            vendor()
            val socket =
                WorkersAiLiveSocket(
                    http = createClient { install(WebSockets) },
                    token = "cf-token",
                    gateway = "asha",
                    socketBase = "ws://localhost/nowhere/",
                )

            val opened = WorkersAiLiveTranscription(socket, "@cf/deepgram/nova-3").open(LiveTranscriptionOptions("en"))

            assertIs<IntegrationError.Unavailable>((opened as Resource.Error).error)
            assertTrue(logs.list.none { "localhost" in it.formattedMessage || "cf-token" in it.formattedMessage })
        }

    @Test
    fun `aura speaks sentence by sentence as 8 kHz mulaw, and flush, clear and close are Deepgram's messages`() =
        testApplication {
            val vendor = vendor()
            vendor.script = {
                send(Frame.Text("""{"type":"Metadata","model_name":"aura-2-luna-en"}"""))
                send(Frame.Binary(true, byteArrayOf(9, 8, 7, 6)))
                send(Frame.Text("""{"type":"Flushed","sequence_id":0}"""))
                send(Frame.Text("""{"type":"Cleared","sequence_id":1}"""))
            }
            val session = (WorkersAiLiveSpeech(socket(), "@cf/deepgram/aura-2-en").open("en") as Resource.Success).data
            val (path, query, _) = vendor.opened.next()

            assertEquals("/client/v4/accounts/acc123/ai/run/@cf/deepgram/aura-2-en", path.replace("%40", "@"))
            assertEquals("mulaw", query["encoding"])
            assertEquals("8000", query["sample_rate"])
            assertEquals("true", query["mip_opt_out"])
            assertEquals(AudioEncoding.MULAW, session.format.encoding)
            assertEquals(2.0, session.format.seconds(16_000))
            assertContentEquals(byteArrayOf(9, 8, 7, 6), assertIs<SpeechEvent.Audio>(session.next()).bytes)
            assertEquals(SpeechEvent.Flushed, session.next())
            assertEquals(SpeechEvent.Cleared, session.next())

            session.speak("You sent Mum 2,000 shillings.")
            session.flush()
            session.clear()
            session.close()
            assertEquals(
                """{"type":"Speak","text":"You sent Mum 2,000 shillings."}""",
                (vendor.received.next() as Frame.Text).readText(),
            )
            assertEquals("""{"type":"Flush"}""", (vendor.received.next() as Frame.Text).readText())
            assertEquals("""{"type":"Clear"}""", (vendor.received.next() as Frame.Text).readText())
            assertEquals("""{"type":"Close"}""", (vendor.received.next() as Frame.Text).readText())

            assertTrue(logs.list.none { "shillings" in it.formattedMessage }, "reply text never reaches a log")
        }

    @Test
    fun `audio length is told from its size, a byte a sample for mulaw and two for 16-bit PCM`() {
        assertEquals(1.0, LiveAudioFormat(AudioEncoding.MULAW, 8_000).seconds(8_000))
        assertEquals(0.5, LiveAudioFormat(AudioEncoding.LINEAR16, 16_000).seconds(16_000))
    }

    @Test
    fun `aura can be set to 16-bit PCM`() =
        testApplication {
            val vendor = vendor()
            val speech = WorkersAiLiveSpeech(socket(), "@cf/deepgram/aura-2-en", AudioEncoding.LINEAR16, 16_000)

            val session = (speech.open(null) as Resource.Success).data
            val (_, query, _) = vendor.opened.next()

            assertEquals("linear16", query["encoding"])
            assertEquals("16000", query["sample_rate"])
            assertEquals(AudioEncoding.LINEAR16, session.format.encoding)
            session.close()
        }
}
