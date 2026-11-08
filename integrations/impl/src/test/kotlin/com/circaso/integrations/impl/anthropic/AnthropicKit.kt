package com.circaso.integrations.impl.anthropic

import com.circaso.integrations.api.ModelStream
import com.circaso.integrations.api.ModelStreamEvent
import com.sun.net.httpserver.HttpServer
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.http.ContentType
import kotlinx.coroutines.runBlocking
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * What the fake Anthropic answers: a status and a body, events or a JSON error. A body in several [chunks] is written
 * a chunk at a time, a moment apart, as a model writes its answer.
 */
class FakeAnswer(val status: Int, val chunks: List<String>, val contentType: ContentType)

/** A request the fake Anthropic received: its body and the headers we look at. */
class SentRequest(val body: String, val headers: Map<String, String?>, val path: String)

/**
 * Anthropic for the tests: a local HTTP server answering `POST /v1/messages` with Anthropic's own Server-Sent Events,
 * called over Ktor's CIO engine as in production. (Ktor's MockEngine can't stream SSE, and the test host's client
 * hands over a response only once it is whole; here a slow answer really arrives a piece at a time.)
 */
class FakeAnthropic(val http: HttpClient, val baseUrl: String) {
    @Volatile var answer: () -> FakeAnswer = { AnthropicKit.sse(*AnthropicKit.textAnswer("Hi")) }
    val requests = CopyOnWriteArrayList<SentRequest>()
    val last: SentRequest get() = requests.last()
}

/** Claude through Koog over the fake Anthropic, answering with Anthropic's own Server-Sent Events. */
object AnthropicKit {
    const val KEY = "test-anthropic-key-0123456789"

    /** Runs [block] with a fake Anthropic up. */
    fun fakeAnthropic(block: suspend FakeAnthropic.() -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.executor = Executors.newCachedThreadPool()
        val http = HttpClient(CIO) { expectSuccess = false }
        val fake = FakeAnthropic(http, "http://127.0.0.1:${server.address.port}/")
        server.createContext("/v1/messages") { exchange ->
            fake.requests +=
                SentRequest(
                    exchange.requestBody.readAllBytes().decodeToString(),
                    listOf(
                        "x-api-key",
                        "anthropic-version",
                        "accept",
                    ).associateWith { exchange.requestHeaders.getFirst(it) },
                    exchange.requestURI.path,
                )
            val answer = fake.answer()
            exchange.responseHeaders.add("Content-Type", answer.contentType.toString())
            exchange.sendResponseHeaders(answer.status, 0)
            exchange.responseBody.use { out ->
                answer.chunks.forEachIndexed { i, chunk ->
                    if (i > 0) Thread.sleep(CHUNK_PAUSE_MILLIS)
                    out.write(chunk.encodeToByteArray())
                    out.flush()
                }
            }
        }
        server.start()
        try {
            runBlocking { fake.block() }
        } finally {
            http.close()
            server.stop(0)
            (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
        }
    }

    fun mode(effort: AnthropicEffort = AnthropicEffort.LOW, maxTokens: Int = 1024) =
        AnthropicMode(
            model = "claude-haiku-5-5",
            effort = effort,
            maxTokens = maxTokens,
            connectTimeout = Duration.ofSeconds(5),
            socketTimeout = Duration.ofSeconds(10),
            requestTimeout = Duration.ofSeconds(30),
        )

    fun FakeAnthropic.model(chat: AnthropicMode = mode(maxTokens = 4096), voice: AnthropicMode = mode()) =
        AnthropicLanguageModel(http = http, apiKey = KEY, chat = chat, voice = voice, baseUrl = baseUrl)

    /** One answer as Anthropic streams it. */
    fun sse(vararg events: String) = FakeAnswer(200, listOf(events.joinToString("")), ContentType.Text.EventStream)

    /** An answer whose events arrive a moment apart, as they do from the model. */
    fun slowSse(vararg events: String) = FakeAnswer(200, events.toList(), ContentType.Text.EventStream)

    const val CHUNK_PAUSE_MILLIS = 100L

    /** A JSON error with [status]. */
    fun refusal(status: Int, body: String = """{"type":"error","error":{"type":"x","message":"secret body"}}""") =
        FakeAnswer(status, listOf(body), ContentType.Application.Json)

    fun event(type: String, data: String) = "event: $type\ndata: $data\n\n"

    fun start(
        input: Int = 812,
        cacheRead: Int = 6144,
        cacheWrite: Int = 0,
    ) = event(
        "message_start",
        """{"type":"message_start","message":{"id":"msg_01","type":"message","role":"assistant",""" +
            """"model":"claude-haiku-5-5","content":[],"stop_reason":null,"stop_sequence":null,""" +
            """"usage":{"input_tokens":$input,"cache_creation_input_tokens":$cacheWrite,""" +
            """"cache_read_input_tokens":$cacheRead,"output_tokens":1}}}""",
    )

    fun textStart(index: Int) =
        event(
            "content_block_start",
            """{"type":"content_block_start","index":$index,"content_block":{"type":"text","text":""}}""",
        )

    fun text(index: Int, piece: String) =
        event(
            "content_block_delta",
            """{"type":"content_block_delta","index":$index,"delta":{"type":"text_delta","text":${quoted(piece)}}}""",
        )

    fun toolStart(
        index: Int,
        id: String,
        name: String,
    ) = event(
        "content_block_start",
        """{"type":"content_block_start","index":$index,"content_block":{"type":"tool_use",""" +
            """"id":"$id","name":"$name","input":{}}}""",
    )

    fun args(index: Int, piece: String) =
        event(
            "content_block_delta",
            """{"type":"content_block_delta","index":$index,""" +
                """"delta":{"type":"input_json_delta","partial_json":${quoted(piece)}}}""",
        )

    fun stop(index: Int) = event("content_block_stop", """{"type":"content_block_stop","index":$index}""")

    fun end(reason: String, output: Int = 42) =
        event(
            "message_delta",
            """{"type":"message_delta","delta":{"stop_reason":"$reason","stop_sequence":null},""" +
                """"usage":{"output_tokens":$output}}""",
        ) + event("message_stop", """{"type":"message_stop"}""")

    fun ping() = event("ping", """{"type":"ping"}""")

    fun error(type: String) = event("error", """{"type":"error","error":{"type":"$type","message":"Overloaded"}}""")

    /** A whole text answer in [pieces]. */
    fun textAnswer(vararg pieces: String): Array<String> =
        arrayOf(start(), ping(), textStart(0)) + pieces.map { text(0, it) } + arrayOf(stop(0), end("end_turn"))

    private fun quoted(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    /** Every event to the end of the answer. */
    suspend fun ModelStream.drain(): List<ModelStreamEvent> {
        val events = mutableListOf<ModelStreamEvent>()
        while (true) events += next() ?: return events
    }
}
