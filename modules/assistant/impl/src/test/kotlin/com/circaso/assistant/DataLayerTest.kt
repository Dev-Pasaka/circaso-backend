package com.circaso.assistant

import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.entities.AshaMessageRow
import com.circaso.assistant.data.entities.AssistantAuditRow
import com.circaso.assistant.data.entities.AssistantOutboxRow
import com.circaso.assistant.data.entities.EntitlementRow
import com.circaso.assistant.data.entities.UsageDailyRow
import com.circaso.assistant.data.migrations.V001_CreateAssistantTables
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.ChatRepositoryImpl
import com.circaso.assistant.data.repositories.EntitlementRepositoryImpl
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.KSerializer
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data layer against a real Cassandra-compatible container: the migration, sealed text, ordering, bulk delete. */
class DataLayerTest {

    private val db: io.kandra.test.KandraRuntimeHandle = run {
        KandraTestBackend.newSession().use { admin ->
            admin.execute(KandraTestBackend.createKeyspaceCql("circaso_assistant"))
            KandraMigrationRunner(admin, keyspace = "circaso_assistant").run(V001_CreateAssistantTables)
        }
        KandraTestcontainers.freshKeyspace(AshaMessageRow::class, UsageDailyRow::class, EntitlementRow::class, AssistantAuditRow::class, AssistantOutboxRow::class)
    }

    private val vault = AesTextVault(object : Secrets { override fun get(name: String): String? = null }, local = true)
    private val bus = object : EventBus {
        override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>) = EventEnvelope.of(event, eventSerializer)
        override suspend fun publish(envelope: EventEnvelope) = Unit
        override fun subscribe(subscription: EventSubscription) = Unit
    }
    private val messageRows = db.suspendRepository<AshaMessageRow>()
    private val chat = ChatRepositoryImpl(messageRows, vault)
    private val entitlements = EntitlementRepositoryImpl(db.suspendRepository())
    private val store = AssistantStoreImpl(db.runtime, messageRows, db.suspendRepository(), db.suspendRepository(), db.suspendRepository(), vault, bus)

    @AfterTest
    fun close() {
        db.close()
        KandraTestUtils.reset()
    }

    private fun message(user: UserId, text: String, role: Role = Role.USER, at: Instant = Instant.now(), replyTo: UUID? = null, note: ReadNote? = null) =
        ChatMessage(user, Uuid7.generate(at), role, text, replyTo, null, if (role == Role.USER) "Family" else null, note, at)

    @Test
    fun `text is sealed in the table and comes back whole, with the read note`() = runBlocking {
        val user = UserId.new()
        val note = ReadNote(listOf("family"), 4, listOf("Family"))
        val question = message(user, "What did Mum say about the rent?")
        val answer = message(user, "She said Friday.", Role.ASHA, replyTo = question.messageId, note = note)

        store.commit(AssistantChanges(messages = listOf(question, answer), audit = listOf(AuditEntry(user, "TURN", "tools=none", "req"))))

        val stored = messageRows.findById(user.value, question.messageId)!!
        assertFalse("rent" in stored.body, "the database holds sealed text")
        val read = chat.find(user, question.messageId)!!
        assertEquals("What did Mum say about the rent?", read.text)
        assertEquals("Family", read.askedFrom)
        assertEquals(note, chat.find(user, answer.messageId)?.readNote)
        assertEquals(question.messageId, chat.find(user, answer.messageId)?.replyTo)
    }

    @Test
    fun `the chat lists newest first and pages by cursor`() = runBlocking {
        val user = UserId.new()
        val base = Instant.now()
        val all = (0 until 5).map { message(user, "m$it", at = base.plusMillis(it * 10L)) }
        store.commit(AssistantChanges(messages = all))

        val first = chat.list(user, null, 3)
        assertEquals(listOf("m4", "m3", "m2"), first.map { it.text })
        assertEquals(listOf("m1", "m0"), chat.list(user, first.last().messageId, 10).map { it.text })
    }

    @Test
    fun `deleting messages and a whole long chat really removes them`() = runBlocking {
        val user = UserId.new()
        val other = UserId.new()
        val base = Instant.now()
        val mine = (0 until 90).map { message(user, "m$it", at = base.plusMillis(it.toLong())) }
        val theirs = message(other, "keep me")
        store.commit(AssistantChanges(messages = mine.take(45)))
        store.commit(AssistantChanges(messages = mine.drop(45) + theirs))

        store.commit(AssistantChanges(deletedMessages = listOf(mine[0])))
        assertNull(chat.find(user, mine[0].messageId))

        store.deleteChat(user)
        assertTrue(chat.list(user, null, 200).isEmpty())
        assertEquals("keep me", chat.find(other, theirs.messageId)?.text, "another user's chat is untouched")
    }

    @Test
    fun `the plan is free until an entitlement says otherwise and runs out with it`() = runBlocking {
        val user = UserId.new()
        assertEquals(Plan.FREE, entitlements.planOf(user))

        val rows = db.suspendRepository<EntitlementRow>()
        rows.save(EntitlementRow(user.value, "PLUS", Instant.now().plusSeconds(3600)))
        assertEquals(Plan.PLUS, entitlements.planOf(user))

        rows.save(EntitlementRow(user.value, "PLUS", Instant.now().minusSeconds(60)))
        assertEquals(Plan.FREE, entitlements.planOf(user), "an expired subscription is the free plan")
    }

    @Test
    fun `usage totals and audit records are stored`() = runBlocking {
        val user = UserId.new()
        store.saveUsage(user, "2026-10-22", 3, 12, 900, 140, Instant.now())
        store.commit(AssistantChanges(audit = listOf(AuditEntry(user, "TURN", "tools=none", "req-1"), AuditEntry(user, "CHAT_DELETED", "all", null))))

        val usage = db.suspendRepository<UsageDailyRow>().findById(user.value, "2026-10-22")
        assertNotNull(usage)
        assertEquals(3, usage.textTurns)
        assertEquals(900L, usage.inputTokens)
    }
}
