package com.circaso.assistant

import com.circaso.assistant.domain.models.ChatSnippet
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.support.ReplyListener
import com.circaso.assistant.presentation.controllers.chatController
import com.circaso.assistant.presentation.services.ChatService
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelStreamEvent
import com.circaso.integrations.api.ToolCall
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.web.CallPrincipal
import com.circaso.web.CallPrincipalKey
import com.circaso.web.installJson
import com.circaso.web.installStatusPages
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Asha's reply streamed from the model (decision 2026-10-27): the use cases, and A2's server-sent events. */
class StreamingTest {
    private val h = Harness()

    private fun ask(text: String) = TurnCommand(h.owner, text, null, true, "agent-tok", "req-1")

    /** Collects what a turn passes on, in order. */
    private class Heard : ReplyListener {
        val pieces = CopyOnWriteArrayList<String>()

        override suspend fun text(delta: String) {
            pieces += delta
        }
    }

    @Test
    fun `the reply reaches the listener piece by piece, and the saved answer is the pieces joined`() =
        runTest {
            h.model.streamed("You sent ", "Mum 2,000 ", "shillings.")
            val heard = Heard()

            val reply = assertIs<TurnResult.Answer>((h.start(ask("how much?"), heard) as Resource.Success).data)

            assertEquals(listOf("You sent ", "Mum 2,000 ", "shillings."), heard.pieces)
            assertEquals("You sent Mum 2,000 shillings.", reply.message.text)
            assertTrue(
                h.model.streams
                    .single()
                    .isClosed,
                "the stream is let go",
            )
        }

    @Test
    fun `text written before a tool call is shown, kept for the model, and saved with the answer`() =
        runTest {
            h.model
                .streamed("Let me look that up.", toolCalls = listOf(ToolCall("c1", "searchContacts", "{}")))
                .streamed("Mum is Mum Wanjiku.")
            val heard = Heard()

            val reply = assertIs<TurnResult.Answer>((h.start(ask("who is Mum?"), heard) as Resource.Success).data)

            assertEquals(listOf("Let me look that up.", "\n\nMum is Mum Wanjiku."), heard.pieces)
            assertEquals("Let me look that up.\n\nMum is Mum Wanjiku.", reply.message.text)
            val second = h.model.requests[1].messages
            assertTrue(second.any { it is ModelMessage.Assistant && it.text == "Let me look that up." })
        }

    @Test
    fun `a turn paused for the phone keeps what was said before the pause in the saved answer`() =
        runTest {
            h.model
                .streamed("Let me look in your chats.", toolCalls = listOf(ToolCall("c9", "searchChats", "{}")))
                .streamed("You agreed KES 2,000.")
            val paused =
                assertIs<TurnResult.NeedsDevice>((h.start(ask("what did we agree?"), Heard()) as Resource.Success).data)
            val heard = Heard()

            val snippet = ChatSnippet("family", "Family", "Brian", Instant.now(), "KES 2,000 each")
            val result = DeviceToolResult(h.owner, paused.turnId, "c9", listOf(snippet), "agent-tok", "req-2")
            val reply = assertIs<TurnResult.Answer>((h.resume(result, heard) as Resource.Success).data)

            assertEquals(listOf("\n\nYou agreed KES 2,000."), heard.pieces, "only the rest streams now")
            assertEquals("Let me look in your chats.\n\nYou agreed KES 2,000.", reply.message.text)
        }

    @Test
    fun `a stream that breaks half way is model_unavailable, and nothing is saved`() =
        runTest {
            h.model.streamed("You sent ", end = ModelStreamEvent.Failed(IntegrationError.Unavailable("model")))

            val result = h.start(ask("how much?"), Heard())

            assertEquals("assistant.model_unavailable", (result as Resource.Error).error.code)
            assertTrue(h.memory.messages.isEmpty())
            assertTrue(
                h.model.streams
                    .single()
                    .isClosed,
            )
        }

    // ---- A2 over HTTP --------------------------------------------------------------------------------------

    private fun ApplicationTestBuilder.serve() {
        val chat = ChatService(h.start, h.resume, h.list, h.deleteAll, h.deleteOne)
        application {
            installJson()
            installStatusPages()
            // the gateway's part: the caller is the harness's user, with a user token
            intercept(ApplicationCallPipeline.Plugins) {
                call.attributes.put(
                    CallPrincipalKey,
                    CallPrincipal(h.owner, DeviceId.new(), UUID.randomUUID(), Scope.APP),
                )
            }
            routing { route("/v1") { chatController(chat) } }
        }
    }

    /**
     * Ktor's test host hands the client the body once the call is over, so this checks the events, in order, as
     * the model wrote them; that each is written when the model writes it is the use case tests' and the code's.
     */
    @Test
    fun `A2 sends each piece the model wrote as its own message event, then done`() =
        testApplication {
            serve()
            h.model.streamed("Hi ", "Amina!")

            val response =
                client.post("/v1/assistant/chat/messages") {
                    contentType(ContentType.Application.Json)
                    header("X-Agent-Token", "agent-tok")
                    setBody("""{"text":"habari"}""")
                }

            assertEquals(HttpStatusCode.OK, response.status)
            val lines = response.bodyAsText().lines()
            val events = lines.filter { it.startsWith("event: ") }.map { it.removePrefix("event: ") }
            assertEquals(listOf("message", "message", "done"), events)
            val deltas = lines.filter { it.startsWith("data: {\"delta\"") }
            assertEquals(listOf("data: {\"delta\":\"Hi \"}", "data: {\"delta\":\"Amina!\"}"), deltas)
        }

    @Test
    fun `A2 brackets each tool call with tool_status and tool_done, and checks the currency's shape`() =
        testApplication {
            serve()
            h.model.call("searchContacts", """{"query":"mum"}""").say("Found her.")

            val response =
                client.post("/v1/assistant/chat/messages") {
                    contentType(ContentType.Application.Json)
                    header("X-Agent-Token", "agent-tok")
                    setBody("""{"text":"who is mum?","currency":"kes"}""")
                }

            val lines = response.bodyAsText().lines()
            val events = lines.filter { it.startsWith("event: ") }.map { it.removePrefix("event: ") }
            assertEquals(listOf("tool_status", "tool_done", "message", "done"), events)
            val status = lines.first { it.startsWith("data: {\"turnId\"") }
            assertTrue("\"tool\":\"searchContacts\"" in status && "\"label\":\"Looking at your contacts…\"" in status)
            assertTrue(lines.any { "\"ok\":true" in it })
            assertEquals(
                "KES",
                h.tools.calls
                    .single()
                    .currency,
            )

            val bad =
                client.post("/v1/assistant/chat/messages") {
                    contentType(ContentType.Application.Json)
                    header("X-Agent-Token", "agent-tok")
                    setBody("""{"text":"hi","currency":"shillings"}""")
                }
            assertEquals(HttpStatusCode.BadRequest, bad.status)
        }

    @Test
    fun `A2 refused before any text answers with the envelope, and a failure after text is an error event`() =
        testApplication {
            serve()
            h.model.streamed("You sent ", end = ModelStreamEvent.Failed(IntegrationError.Unavailable("model")))

            val refused =
                client.post("/v1/assistant/chat/messages") {
                    contentType(ContentType.Application.Json)
                    header("X-Agent-Token", "other-agent")
                    setBody("""{"text":"habari"}""")
                }
            assertEquals(HttpStatusCode.Unauthorized, refused.status)
            assertTrue("auth.token_invalid" in refused.bodyAsText())

            val broken =
                client.post("/v1/assistant/chat/messages") {
                    contentType(ContentType.Application.Json)
                    header("X-Agent-Token", "agent-tok")
                    setBody("""{"text":"how much?"}""")
                }
            val text = broken.bodyAsText()
            assertEquals(HttpStatusCode.OK, broken.status, "the status went out with the first words")
            assertTrue("event: message" in text)
            assertTrue("event: error\ndata: {\"code\":\"assistant.model_unavailable\"" in text, text)
            assertTrue("event: done" !in text)
        }
}
