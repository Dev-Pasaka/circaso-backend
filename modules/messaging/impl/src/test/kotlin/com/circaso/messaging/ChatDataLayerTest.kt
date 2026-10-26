package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.DirectChatClaimRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.MessagingOutboxRow
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.V002_CreateKeyDirectoryTables
import com.circaso.messaging.data.migrations.V003_CreateChatTables
import com.circaso.messaging.data.migrations.V004_AddChatOwner
import com.circaso.messaging.data.repositories.ChatRepositoryImpl
import com.circaso.messaging.data.repositories.MessagingOutboxStore
import com.circaso.messaging.data.repositories.MessagingStoreImpl
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatSettings
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.Join
import com.circaso.messaging.domain.repositories.Leave
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.cql.AsyncResultSet
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.BoundStatement
import com.datastax.oss.driver.api.core.cql.ResultSet
import com.datastax.oss.driver.api.core.cql.Statement
import io.kandra.core.ExperimentalKandraApi
import io.kandra.core.InternalKandraApi
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.runtime.BatchEngine
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.StatementBuilder
import io.kandra.runtime.codec.KandraCodec
import io.kandra.test.KandraRuntimeHandle
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chats and membership against a real Cassandra-compatible container: V003 against the entities, the pair claim,
 * the versioned update, and the store writing each member's rows and outbox row in one batch.
 */
// Internal: the store gets a runtime over a recording session, the one way to see which rows share a batch.
@OptIn(ExperimentalKandraApi::class, InternalKandraApi::class)
class ChatDataLayerTest {
    private val entities =
        arrayOf(
            ChatRow::class,
            DirectChatClaimRow::class,
            MemberRow::class,
            UserChatRow::class,
            MessagingOutboxRow::class,
        )

    private val db: KandraRuntimeHandle =
        run {
            KandraTestBackend.newSession().use { admin ->
                admin.execute(KandraTestBackend.createKeyspaceCql(KEYSPACE))
                KandraMigrationRunner(admin, keyspace = KEYSPACE).run(
                    V001_CreateMessagingTables,
                    V002_CreateKeyDirectoryTables,
                    V003_CreateChatTables,
                    V004_AddChatOwner,
                )
            }
            KandraTestcontainers.freshKeyspace(*entities)
        }

    /** Every statement the store's runtime sends, so a test can see which rows shared a batch. */
    private val recording = RecordingSession(KandraTestBackend.newSession(db.keyspace))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val runtime =
        KandraCodec.default.let { codec ->
            KandraRuntime(recording, BatchEngine(recording, StatementBuilder(recording, codec), scope), codec)
        }
    private val bus = MemoryBus()
    private val chats =
        ChatRepositoryImpl(
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
        )
    private val store =
        MessagingStoreImpl(
            runtime,
            runtime.suspendRepository(),
            runtime.suspendRepository(),
            runtime.suspendRepository(),
            runtime.suspendRepository(),
            bus,
        )
    private val outbox = MessagingOutboxStore(db.suspendRepository())

    @AfterTest
    fun close() {
        scope.cancel()
        recording.close()
        db.close()
        KandraTestUtils.reset()
    }

    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    private fun group(owner: UserId) =
        Chat(ChatId.new(), ChatKind.GROUP, "AAEC", 1, ChatSettings(), 1, owner, now, version = 1)

    private fun join(
        chat: Chat,
        userId: UserId,
        role: Role = Role.MEMBER,
        withEvent: Boolean = true,
    ): Join {
        val event =
            if (withEvent) {
                bus.envelope(
                    MemberAdded(chat.chatId, userId, chat.createdBy),
                    MemberAdded.serializer(),
                )
            } else {
                null
            }
        return Join(Member(chat.chatId, userId, role, now), ChatKind.GROUP, event)
    }

    /**
     * The entities name their keyspace, so Kandra uses the tables V003 created rather than making its own: every
     * test here runs on the migrated schema. This one checks V003 has a column for each entity field, no more.
     */
    @Test
    fun `V003 has exactly the columns the entities name`() =
        runBlocking<Unit> {
            val tables =
                mapOf(
                    "chats" to ChatRow::class.java,
                    "direct_chats_by_pair" to DirectChatClaimRow::class.java,
                    "members_by_chat" to MemberRow::class.java,
                    "chats_by_user" to UserChatRow::class.java,
                )
            KandraTestBackend.newSession().use { admin ->
                for ((table, entity) in tables) {
                    assertEquals(fieldColumns(entity), columns(admin, table), table)
                }
            }
        }

    private fun fieldColumns(entity: Class<*>): Set<String> =
        entity.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .map { field -> field.name.replace(Regex("([A-Z])")) { "_" + it.value.lowercase() } }
            .toSet()

    private fun columns(session: CqlSession, table: String): Set<String> =
        session
            .execute(
                "SELECT column_name FROM system_schema.columns WHERE keyspace_name = ? AND table_name = ?",
                KEYSPACE,
                table,
            ).all()
            .map { it.getString(0)!! }
            .toSet()

    @Test
    fun `a pair is claimed once, and the claim names its chat`() =
        runBlocking<Unit> {
            val first = ChatId.new()

            assertTrue(chats.claimPair("a:b", first))
            assertFalse(chats.claimPair("a:b", ChatId.new()))
            assertEquals(first, chats.pairChat("a:b"))
            assertNull(chats.pairChat("c:d"))
        }

    @Test
    fun `a chat is written with its members and read back, nil markers as null`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val brian = UserId.new()
            val chat = group(amina)

            store.commit(
                ChatChanges(
                    chats = listOf(chat),
                    joins = listOf(join(chat, amina, Role.OWNER, false), join(chat, brian)),
                ),
            )

            assertEquals(chat, chats.chat(chat.chatId))
            assertEquals(setOf(amina, brian), chats.members(chat.chatId).map { it.userId }.toSet())
            assertEquals(Role.OWNER, chats.member(chat.chatId, amina)!!.role)
            val line = chats.userChat(brian, chat.chatId)!!
            assertEquals(ChatKind.GROUP, line.kind)
            assertNull(line.lastMessageId)
            assertNull(line.readUpTo)
            assertEquals(listOf(chat.chatId), chats.chatsOf(brian).map { it.chatId })
        }

    @Test
    fun `a member's rows and their outbox row go in one batch`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chat = group(amina)
            store.commit(ChatChanges(chats = listOf(chat), joins = listOf(join(chat, amina, Role.OWNER, false))))
            recording.batches.clear()
            val brian = UserId.new()
            val joined = join(chat, brian)

            store.commit(ChatChanges(joins = listOf(joined)))

            val batch = recording.batches.single()
            val tables = batch.mapNotNull { (it as? BoundStatement)?.preparedStatement?.query }.map(::tableOf)
            assertEquals(listOf("members_by_chat", "chats_by_user", "outbox"), tables)
            val stored = outbox.unpublished(joined.event!!.bucket).map { it.eventId }
            assertTrue(joined.event.eventId in stored)
            assertEquals(listOf(MemberAdded.TYPE), bus.published.map(EventEnvelope::type))
        }

    @Test
    fun `many members go in chunks, each with its own events, the chat in the first`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val chat = group(owner)
            val people = (1..85).map { UserId.new() }

            store.commit(ChatChanges(chats = listOf(chat), joins = people.map { join(chat, it) }))

            assertEquals(3, recording.batches.size, "40 + 40 + 5 people")
            assertEquals(85, chats.members(chat.chatId).size)
            val firstTables = recording.batches.first().mapNotNull { (it as? BoundStatement)?.preparedStatement?.query }
            assertTrue(tableOf(firstTables.first()) == "chats")
            assertEquals(85, bus.published.size)
        }

    @Test
    fun `a leave deletes both rows and a deleted chat is gone`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chat = group(amina)
            val owner = join(chat, amina, Role.OWNER, false)
            store.commit(ChatChanges(chats = listOf(chat), joins = listOf(owner)))

            store.commit(ChatChanges(deletedChats = listOf(chat.chatId), leaves = listOf(Leave(owner.member))))

            assertNull(chats.chat(chat.chatId))
            assertNull(chats.member(chat.chatId, amina))
            assertNull(chats.userChat(amina, chat.chatId))
        }

    @Test
    fun `of many removals of the same member at once, exactly one claims it`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val brian = UserId.new()
            val chat = group(amina)
            store.commit(
                ChatChanges(
                    chats = listOf(chat),
                    joins = listOf(join(chat, amina, Role.OWNER, false), join(chat, brian)),
                ),
            )

            val claims =
                (1..8)
                    .map { async(Dispatchers.IO) { chats.claimRemoval(chat.chatId, brian) } }
                    .awaitAll()

            assertEquals(1, claims.count { it })
            assertNull(chats.member(chat.chatId, brian))
            assertFalse(chats.claimRemoval(chat.chatId, UserId.new()), "nobody to claim")
        }

    @Test
    fun `the owner is kept on the chat row`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chat = group(amina).copy(ownerId = amina)
            store.commit(ChatChanges(chats = listOf(chat)))

            assertEquals(amina, chats.chat(chat.chatId)!!.ownerId)
            val direct = chat.copy(chatId = ChatId.new(), kind = ChatKind.DIRECT, ownerId = null)
            store.commit(ChatChanges(chats = listOf(direct)))
            assertNull(chats.chat(direct.chatId)!!.ownerId, "the nil UUID reads back as no owner")
        }

    @Test
    fun `a versioned update wins once and the stale one is refused`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chat = group(amina)
            store.commit(ChatChanges(chats = listOf(chat)))
            val stored = chats.chat(chat.chatId)!!

            val updated = chats.updateChat(stored, stored.copy(memberCount = 2))
            assertNotNull(updated)
            assertEquals(stored.version + 1, updated.version)

            assertNull(chats.updateChat(stored, stored.copy(sealedMeta = "BBEC")), "written from an old version")
            assertEquals(2, chats.chat(chat.chatId)!!.memberCount)
            assertEquals("AAEC", chats.chat(chat.chatId)!!.sealedMeta)
        }

    private fun tableOf(cql: String) = Regex("""(?:INTO|FROM|UPDATE)\s+(?:\w+\.)?(\w+)""").find(cql)!!.groupValues[1]

    private companion object {
        const val KEYSPACE = "circaso_messaging"
    }
}

/** A real session that remembers every batch sent through it. */
private class RecordingSession(private val inner: CqlSession) : CqlSession by inner {
    val batches = CopyOnWriteArrayList<BatchStatement>()

    override fun execute(statement: Statement<*>): ResultSet {
        if (statement is BatchStatement) batches += statement
        return inner.execute(statement)
    }

    override fun executeAsync(statement: Statement<*>): CompletionStage<AsyncResultSet> {
        if (statement is BatchStatement) batches += statement
        return inner.executeAsync(statement)
    }
}
