package com.circaso.assistant

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.support.ConversationTitles
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelUsage
import com.circaso.integrations.api.TranscriptionEvent
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Separate conversations with Asha (decision 2026-10-31): C1 to C5, A2's `conversationId` and A11's. */
class ConversationsTest {
    private val me = UserId.new()

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private fun ask(text: String, conversationId: UUID? = null) =
        TurnCommand(me, text, null, true, "agent-token", "req-1", conversationId = conversationId)

    private fun answer(result: Resource<TurnResult>) = result.data() as TurnResult.Answer

    // ---- A2's fallback ----

    @Test
    fun `without a conversation, the first question opens one and the next goes to the same`() =
        runTest {
            val h = Harness(owner = me)
            h.model.say("Hi!").say("Again.")

            val first = answer(h.start(ask("Hello")))
            val second = answer(h.start(ask("And again")))

            assertEquals(1, h.memory.conversations.size)
            assertEquals(first.conversationId, second.conversationId)
            assertEquals(
                first.conversationId,
                h.memory.conversations
                    .single()
                    .conversationId,
            )
            assertTrue(h.memory.messages.all { it.conversationId == first.conversationId })
        }

    @Test
    fun `without a conversation, a question goes to the most recent one, so a new chat catches it`() =
        runTest {
            val h = Harness(owner = me)
            h.model.say("One.").say("Two.")
            val older = answer(h.start(ask("First chat"))).conversationId

            val fresh = h.createConversation(me, null).data()
            val next = answer(h.start(ask("Which chat am I in?")))

            assertNotEquals(older, fresh.conversationId)
            assertEquals(fresh.conversationId, next.conversationId)
        }

    @Test
    fun `a named conversation that isn't the user's is not found, and costs no allowance`() =
        runTest {
            val h = Harness(owner = me)
            val theirs = h.createConversation(UserId.new(), "Theirs").data()

            val refused = h.start(ask("Read their chat", theirs.conversationId))

            assertEquals("assistant.conversation_not_found", refused.errorCode())
            assertEquals(0, h.usage(me).data().textTurnsUsed)
            assertTrue(h.model.requests.isEmpty())
        }

    // ---- context ----

    @Test
    fun `Asha reads only the conversation she is asked in`() =
        runTest {
            val h = Harness(owner = me)
            h.model
                .say("Rent is due Friday.")
                .say("Groceries noted.")
                .say("Friday, as I said.")
            val rent = h.createConversation(me, null).data().conversationId
            val groceries = h.createConversation(me, null).data().conversationId
            h.start(ask("When is rent due?", rent))
            h.start(ask("Add milk to groceries", groceries))

            h.start(ask("Remind me when?", rent))

            val texts =
                h.model.requests
                    .last()
                    .messages
                    .map { it.toString() }
            assertTrue(texts.any { "When is rent due?" in it })
            assertTrue(texts.any { "Rent is due Friday." in it })
            assertFalse(texts.any { "milk" in it || "Groceries noted." in it }, "another conversation never leaks")
        }

    @Test
    fun `the context is capped by the plan within the conversation`() =
        runTest {
            val h = Harness(smallLimits(turns = 10, context = 2), owner = me)
            h.model
                .say("a1")
                .say("a2")
                .say("a3")
            val chat = h.createConversation(me, null).data().conversationId
            h.start(ask("q1", chat))
            h.start(ask("q2", chat))

            h.start(ask("q3", chat))

            val history =
                h.model.requests
                    .last()
                    .messages
            assertEquals(3, history.size, "two earlier messages and the question")
            assertEquals(ModelMessage.User("q2"), history[0])
        }

    // ---- titles ----

    @Test
    fun `the first exchange titles the conversation from its question, and the done answer carries it`() =
        runTest {
            val h = Harness(owner = me)
            h.model.say("You sent 2,000.").say("Yes.")
            val chat = h.createConversation(me, null).data()
            assertNull(chat.title)
            assertEquals("New chat", ConversationTitles.shownFor(chat))

            val done = answer(h.start(ask("Hi Asha, how much did I send Mum last week?", chat.conversationId)))
            answer(h.start(ask("Are you sure?", chat.conversationId)))

            assertEquals("How much did I send Mum", done.conversation?.title)
            assertEquals(
                "How much did I send Mum",
                h.memory.conversation(me, chat.conversationId)?.title,
                "a later question doesn't retitle it",
            )
        }

    @Test
    fun `a title the user chose is never replaced`() =
        runTest {
            val h = Harness(owner = me)
            h.model.say("Sure.")
            val chat = h.createConversation(me, "Budget").data()

            h.start(ask("Plan my December spending", chat.conversationId))

            assertEquals("Budget", h.memory.conversation(me, chat.conversationId)?.title)
            val renamed = h.renameConversation(me, chat.conversationId, "  December  ").data()
            assertEquals("December", renamed.title)
        }

    @Test
    fun `titles keep six words, drop greetings and marks, and never come out empty`() {
        assertEquals(
            "What did the chama agree about",
            ConversationTitles.from("What did the chama agree about December?"),
        )
        assertEquals("Balance", ConversationTitles.from("hey, asha! **balance**?"))
        assertEquals("Hi", ConversationTitles.from("Hi"))
        assertEquals("???", ConversationTitles.from("???"))
        assertTrue(ConversationTitles.from("x".repeat(300)).length <= ConversationTitles.MAX_CHARS + 1)
    }

    @Test
    fun `conversation 1 from the migration is titled from its first question the first time it is listed`() =
        runTest {
            val h = Harness(owner = me)
            val at = Instant.parse("2026-10-01T08:00:00Z")
            val first = Conversation(me, Uuid7.generate(at), null, at, at, at)
            h.memory.conversations += first
            h.memory.messages += message(first.conversationId, Role.USER, "  How much is   in my wallet? ", at)
            h.memory.messages += message(first.conversationId, Role.ASHA, "KES 4,000.", at.plusSeconds(1))
            val asha = Conversation(me, Uuid7.generate(at.plusSeconds(5)), null, at, at, at)
            h.memory.conversations += asha
            h.memory.messages += message(asha.conversationId, Role.ASHA, "Welcome!", at.plusSeconds(6))

            val listed = h.listConversations(me, null, 50).data().conversations

            assertEquals("How much is in my wallet", listed.single { it.conversationId == first.conversationId }.title)
            assertEquals("Earlier chat", listed.single { it.conversationId == asha.conversationId }.title)
            assertEquals("How much is in my wallet", h.memory.conversation(me, first.conversationId)?.title, "stored")
        }

    // ---- C1, C3, C4, C5 ----

    @Test
    fun `the list is the user's own, newest first by last message, and pages by cursor`() =
        runTest {
            val h = Harness(owner = me)
            h.model.say("ok")
            val a = h.createConversation(me, "A").data()
            val b = h.createConversation(me, "B").data()
            h.createConversation(me, "C")
            h.createConversation(UserId.new(), "Not mine")
            h.start(ask("bump a", a.conversationId))

            val page = h.listConversations(me, null, 2).data()
            val rest = h.listConversations(me, page.nextCursor, 2).data()

            assertEquals(listOf("A", "C"), page.conversations.map { it.title })
            assertEquals(listOf("B"), rest.conversations.map { it.title })
            assertNull(rest.nextCursor)
            assertEquals(b.conversationId, rest.conversations.single().conversationId)
            assertEquals("request.malformed", h.listConversations(me, "nonsense", 2).errorCode())
        }

    @Test
    fun `another user's conversation is not found to rename, delete or read`() =
        runTest {
            val h = Harness(owner = me)
            val theirs = h.createConversation(UserId.new(), "Theirs").data().conversationId

            assertEquals("assistant.conversation_not_found", h.renameConversation(me, theirs, "Mine").errorCode())
            assertEquals("assistant.conversation_not_found", h.deleteConversation(me, theirs, null).errorCode())
            assertEquals("assistant.conversation_not_found", h.conversationMessages(me, theirs, null, 50).errorCode())
            assertEquals(
                "Theirs",
                h.memory.conversations
                    .single()
                    .title,
            )
        }

    @Test
    fun `a title must be 1 to 80 characters`() =
        runTest {
            val h = Harness(owner = me)
            val chat = h.createConversation(me, null).data().conversationId

            assertEquals("request.malformed", h.renameConversation(me, chat, "   ").errorCode())
            assertEquals("request.malformed", h.renameConversation(me, chat, "x".repeat(81)).errorCode())
            assertEquals("request.malformed", h.createConversation(me, "").errorCode())
        }

    @Test
    fun `a conversation's messages come newest first, paged, and only its own`() =
        runTest {
            val h = Harness(owner = me)
            h.model
                .say("a1")
                .say("a2")
                .say("other")
            val chat = h.createConversation(me, null).data().conversationId
            val other = h.createConversation(me, null).data().conversationId
            h.start(ask("q1", chat))
            h.start(ask("q2", chat))
            h.start(ask("elsewhere", other))

            val page = h.conversationMessages(me, chat, null, 3).data()
            val rest = h.conversationMessages(me, chat, page.nextCursor, 3).data()

            assertEquals(listOf("a2", "q2", "a1"), page.messages.map { it.text })
            assertEquals(listOf("q1"), rest.messages.map { it.text })
            assertTrue((page.messages + rest.messages).all { it.conversationId == chat })
        }

    @Test
    fun `deleting a conversation deletes its messages with an audit record, and leaves the others`() =
        runTest {
            val h = Harness(owner = me)
            h.model.say("a").say("b")
            val gone = h.createConversation(me, null).data().conversationId
            val kept = h.createConversation(me, null).data().conversationId
            h.start(ask("forget this", gone))
            h.start(ask("keep this", kept))

            h.deleteConversation(me, gone, "req-del").data()

            assertNull(h.memory.conversation(me, gone))
            assertTrue(h.memory.messages.none { it.conversationId == gone })
            assertEquals(2, h.memory.messages.count { it.conversationId == kept })
            assertEquals(1, h.memory.audit.count { it.kind == "CONVERSATION_DELETED" && it.requestId == "req-del" })
            assertEquals("assistant.conversation_not_found", h.deleteConversation(me, gone, null).errorCode())
            assertEquals(1, h.memory.audit.count { it.kind == "CONVERSATION_DELETED" }, "a repeat records nothing")
        }

    @Test
    fun `a conversation deleted while Asha answers keeps nothing of the exchange`() =
        runTest {
            val h = Harness(owner = me)
            val chat = h.createConversation(me, null).data().conversationId
            h.model.then { _ ->
                // the user deletes the conversation from another phone while Asha is answering
                h.memory.conversations.removeIf { it.conversationId == chat }
                Resource.success(ModelResponse("Too late.", emptyList(), ModelUsage(1, 1)))
            }

            val done = answer(h.start(ask("Quick question", chat)))

            assertNull(done.conversation)
            assertTrue(h.memory.messages.isEmpty())
        }

    @Test
    fun `deleting the whole history removes every conversation too`() =
        runTest {
            val h = Harness(owner = me)
            h.model.say("a")
            h.start(ask("hello"))
            h.createConversation(me, "Empty")

            h.deleteAll(me, null).data()

            assertTrue(h.memory.conversations.isEmpty())
            assertTrue(h.memory.messages.isEmpty())
        }

    // ---- A9 and A11 ----

    @Test
    fun `a turn that waited on the phone is saved in the conversation it started in`() =
        runTest {
            val h = Harness(owner = me)
            h.model.call("searchChats", """{"query":"rent"}""").say("Friday.")
            val chat = h.createConversation(me, null).data().conversationId
            h.createConversation(me, "Newer")

            val asked = h.start(ask("When is rent due?", chat)).data() as TurnResult.NeedsDevice
            assertEquals(chat, asked.conversationId)
            val done =
                h
                    .resume(
                        DeviceToolResult(
                            me,
                            asked.turnId,
                            asked.callId,
                            emptyList(),
                            "agent-token",
                            "req-2",
                        ),
                    ).data() as TurnResult.Answer

            assertEquals(chat, done.conversationId)
            assertEquals(2, h.memory.messages.count { it.conversationId == chat })
        }

    @Test
    fun `live voice turns are saved in the conversation the auth frame names`() =
        testApplication {
            val live = LiveHarness(Harness())
            live.serve(this)
            val owner = live.userId
            val named =
                live.h
                    .createConversation(owner, "Voice")
                    .data()
                    .conversationId
            live.h.createConversation(owner, "Newer")
            live.h.model.say("Twelve.")

            createClient { install(WebSockets) }.webSocket("/v1/assistant/voice/live") {
                send(
                    Frame.Text(
                        """{"type":"auth","accessToken":"user-token","agentToken":"agent-tok",""" +
                            """"language":"en","conversationId":"$named"}""",
                    ),
                )
                readUntilType("ready")
                val ears = withTimeout(3_000) { live.ears.opened.receive() }
                ears.push(TranscriptionEvent.SpeechStarted, TranscriptionEvent.Final("what is six times two", true))
                val done = readUntilType("reply_done")
                assertEquals(named.toString(), done["message"]!!.jsonObject["conversationId"]!!.jsonPrimitive.content)
                send(Frame.Text("""{"type":"end"}"""))
            }

            val saved = live.h.memory.messages
            assertEquals(2, saved.size)
            assertTrue(saved.all { it.conversationId == named })
        }

    private suspend fun DefaultClientWebSocketSession.readUntilType(type: String) =
        withTimeout(5_000) {
            var found: JsonObject? = null
            while (found == null) {
                val frame = incoming.receive() as? Frame.Text ?: continue
                val json = Json.parseToJsonElement(frame.readText()).jsonObject
                if (json["type"]?.jsonPrimitive?.content == type) found = json
            }
            found
        }

    private fun message(
        conversationId: UUID,
        role: Role,
        text: String,
        at: Instant,
    ) = ChatMessage(me, Uuid7.generate(at), role, text, null, null, null, null, at, conversationId)
}
