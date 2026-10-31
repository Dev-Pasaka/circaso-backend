package com.circaso.assistant

import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.ChatRepositoryImpl
import com.circaso.assistant.data.repositories.ConversationRepositoryImpl
import com.circaso.assistant.di.assistantDatabaseSpec
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ConversationCursor
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.support.ConversationTitler
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.sql.Connection
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Conversations on a real PostgreSQL: the V2 migration's conversation 1, and the repository's scoping and deletes. */
class ConversationDataTest {
    private val database: Database = TestPostgres.database(assistantDatabaseSpec)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val vault =
        AesTextVault(
            object : Secrets {
                override fun get(name: String): String? = null
            },
            local = true,
        )
    private val chat = ChatRepositoryImpl(database, vault)
    private val conversations = ConversationRepositoryImpl(database, vault)
    private val store = AssistantStoreImpl(database, vault, Clock.fixed(now, ZoneOffset.UTC))

    @BeforeTest
    fun clean() = TestPostgres.truncate("assistant")

    private fun conversation(
        user: UserId,
        at: Instant = now,
        title: String? = null,
    ) = Conversation(user, Uuid7.generate(at), title, at, at, at)

    private fun message(
        user: UserId,
        conversationId: UUID,
        text: String,
        role: Role = Role.USER,
        at: Instant = now,
    ) = ChatMessage(user, Uuid7.generate(at), role, text, null, null, null, null, at, conversationId)

    // ---- V2 ----

    /** Runs a script of `db/assistant` against [schema] instead of `assistant`, as the superuser. */
    private fun Connection.runScript(name: String, schema: String) {
        val sql =
            ConversationDataTest::class.java
                .getResource("/db/assistant/$name")!!
                .readText()
                .replace("assistant.", "$schema.")
        createStatement().use { it.execute(sql) }
    }

    private fun Connection.insertV1Message(
        schema: String,
        user: UUID,
        role: String,
        at: Instant,
    ) = prepareStatement(
        "INSERT INTO $schema.asha_messages (user_id, message_id, role, body, created_at) VALUES (?, ?, ?, ?, ?)",
    ).use {
        it.setObject(1, user)
        it.setObject(2, Uuid7.generate(at))
        it.setString(3, role)
        it.setString(4, "sealed")
        it.setTimestamp(5, Timestamp.from(at))
        it.executeUpdate()
    }

    @Test
    fun `V2 puts each user's messages into one conversation 1, untitled until the app reads it`() {
        val schema = "assistant_v2_check"
        val talker = UUID.randomUUID()
        val other = UUID.randomUUID()
        TestPostgres.superuser { connection ->
            connection.createStatement().use {
                it.execute("DROP SCHEMA IF EXISTS $schema CASCADE")
                it.execute("CREATE SCHEMA $schema")
            }
            connection.runScript("V1__assistant.sql", schema)
            connection.insertV1Message(schema, talker, "USER", now.minusSeconds(30))
            connection.insertV1Message(schema, talker, "ASHA", now.minusSeconds(29))
            connection.insertV1Message(schema, talker, "USER", now)
            connection.insertV1Message(schema, other, "ASHA", now.minusSeconds(5))

            connection.runScript("V2__asha_conversations.sql", schema)

            connection
                .prepareStatement(
                    "SELECT user_id, conversation_id, title, created_at, last_message_at " +
                        "FROM $schema.asha_conversations ORDER BY created_at",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        assertTrue(rows.next())
                        assertEquals(talker, rows.getObject("user_id"))
                        assertNull(rows.getString("title"), "titled by the app, which holds the key")
                        assertEquals(now.minusSeconds(30), rows.getTimestamp("created_at").toInstant())
                        assertEquals(now, rows.getTimestamp("last_message_at").toInstant())
                        assertTrue(rows.next())
                        assertEquals(other, rows.getObject("user_id"))
                        assertFalse(rows.next(), "one conversation per user")
                    }
                }
            connection
                .prepareStatement(
                    "SELECT count(DISTINCT m.conversation_id), count(*) FROM $schema.asha_messages m " +
                        "JOIN $schema.asha_conversations c " +
                        "ON c.user_id = m.user_id AND c.conversation_id = m.conversation_id",
                ).use { statement ->
                    statement.executeQuery().use { rows ->
                        rows.next()
                        assertEquals(2, rows.getInt(1), "each user's messages share their conversation")
                        assertEquals(4, rows.getInt(2), "every message is in one")
                    }
                }
            connection.createStatement().use { it.execute("DROP SCHEMA $schema CASCADE") }
        }
    }

    @Test
    fun `conversation 1 is titled from its first question, or Earlier chat when Asha spoke first`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val asked = conversation(user, now.minusSeconds(60))
            val greeted = conversation(user, now.minusSeconds(30))
            conversations.addConversation(asked)
            conversations.addConversation(greeted)
            val question =
                message(user, asked.conversationId, "hello asha what's my balance?", at = now.minusSeconds(60))
            val answer = message(user, asked.conversationId, "KES 10", Role.ASHA, now.minusSeconds(59))
            val welcome = message(user, greeted.conversationId, "Karibu!", Role.ASHA, now.minusSeconds(30))
            store.commit(AssistantChanges(messages = listOf(question, answer, welcome)))
            val titler = ConversationTitler(chat, conversations)

            assertEquals("What's my balance", titler.titled(asked).title)
            assertEquals("Earlier chat", titler.titled(greeted).title)
            assertEquals("What's my balance", conversations.conversation(user, asked.conversationId)?.title)
            val stored =
                TestPostgres.superuser { connection ->
                    connection
                        .prepareStatement("SELECT title FROM assistant.asha_conversations WHERE conversation_id = ?")
                        .use { statement ->
                            statement.setObject(1, asked.conversationId)
                            statement.executeQuery().use { rows -> rows.next().let { rows.getString(1) } }
                        }
                }
            assertFalse("balance" in stored, "the title is sealed like the messages")
        }

    // ---- the repository ----

    @Test
    fun `another user's conversation is never found, renamed, touched or deleted`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val stranger = UserId.new()
            val theirs = conversation(owner, title = "Mine")
            conversations.addConversation(theirs)
            val id = theirs.conversationId

            assertNull(conversations.conversation(stranger, id))
            assertNull(conversations.latestConversation(stranger))
            assertTrue(conversations.conversations(stranger, null, 10).isEmpty())
            assertNull(conversations.renameConversation(stranger, id, "Stolen", now))
            assertNull(conversations.touchConversation(stranger, id, now))
            assertFalse(conversations.titleConversation(stranger, id, "Stolen"))
            assertEquals(0, conversations.deleteConversation(stranger, id))
            assertEquals("Mine", conversations.conversation(owner, id)?.title)
        }

    @Test
    fun `the list is newest first by last message and pages by keyset, and the latest is its first row`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val all = (0 until 5).map { conversation(user, now.plusSeconds(it.toLong())) }
            all.forEach { conversations.addConversation(it) }
            conversations.touchConversation(user, all[1].conversationId, now.plusSeconds(60))

            val first = conversations.conversations(user, null, 3)
            val last = first.last()
            val rest =
                conversations.conversations(
                    user,
                    ConversationCursor(last.lastMessageAt, last.conversationId),
                    10,
                )

            val expected = listOf(all[1], all[4], all[3], all[2], all[0]).map { it.conversationId }
            assertEquals(expected, (first + rest).map { it.conversationId })
            assertEquals(all[1].conversationId, conversations.latestConversation(user)?.conversationId)
        }

    @Test
    fun `a title is set once, and a rename replaces it`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val chatOne = conversation(user)
            conversations.addConversation(chatOne)
            val id = chatOne.conversationId

            assertTrue(conversations.titleConversation(user, id, "First"))
            assertFalse(conversations.titleConversation(user, id, "Second"), "a title already there stands")
            assertEquals("Mine", conversations.renameConversation(user, id, "Mine", now.plusSeconds(1))?.title)
            assertEquals(now.plusSeconds(1), conversations.conversation(user, id)?.updatedAt)
        }

    @Test
    fun `deleting a conversation deletes its messages and nothing else`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val gone = conversation(user)
            val kept = conversation(user)
            conversations.addConversation(gone)
            conversations.addConversation(kept)
            store.commit(
                AssistantChanges(
                    messages =
                        listOf(
                            message(user, gone.conversationId, "forget me"),
                            message(user, gone.conversationId, "ok", Role.ASHA),
                            message(user, kept.conversationId, "keep me"),
                        ),
                ),
            )

            assertEquals(1, conversations.deleteConversation(user, gone.conversationId))
            assertEquals(0, conversations.deleteConversation(user, gone.conversationId), "gone already")

            assertTrue(chat.listIn(user, gone.conversationId, null, 10).isEmpty())
            assertEquals(listOf("keep me"), chat.listIn(user, kept.conversationId, null, 10).map { it.text })
            assertNotEquals(null, conversations.conversation(user, kept.conversationId))
        }

    @Test
    fun `deleting the whole chat removes the conversations with the messages`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val one = conversation(user)
            conversations.addConversation(one)
            conversations.addConversation(conversation(user))
            store.commit(AssistantChanges(messages = listOf(message(user, one.conversationId, "hi"))))

            assertEquals(3, store.deleteChat(user))

            assertTrue(conversations.conversations(user, null, 10).isEmpty())
            assertTrue(chat.list(user, null, 10).isEmpty())
        }
}
