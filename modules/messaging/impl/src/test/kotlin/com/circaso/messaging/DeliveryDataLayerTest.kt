package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.api.TypingFrame
import com.circaso.messaging.data.cache.LiveFramesImpl
import com.circaso.messaging.data.cache.RedisPendingCounts
import com.circaso.messaging.data.cache.RedisReceiptBuffer
import com.circaso.messaging.data.cache.RedisTypingMarks
import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.ClientMessageRow
import com.circaso.messaging.data.entities.DirectChatClaimRow
import com.circaso.messaging.data.entities.GroupMessageRow
import com.circaso.messaging.data.entities.InboxRow
import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.MessageRecordRow
import com.circaso.messaging.data.entities.MessagingSettingsRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.data.mappers.toUserChatRow
import com.circaso.messaging.data.migrations.V001_CreateMessagingTables
import com.circaso.messaging.data.migrations.V002_CreateKeyDirectoryTables
import com.circaso.messaging.data.migrations.V003_CreateChatTables
import com.circaso.messaging.data.migrations.V004_AddChatOwner
import com.circaso.messaging.data.migrations.V005_CreateDeliveryTables
import com.circaso.messaging.data.repositories.ChatMarkersImpl
import com.circaso.messaging.data.repositories.ChatRepositoryImpl
import com.circaso.messaging.data.repositories.DeliveryRepositoryImpl
import com.circaso.messaging.data.repositories.KeyRepositoryImpl
import com.circaso.messaging.data.repositories.SettingsRepositoryImpl
import com.circaso.messaging.data.repositories.UnloggedBatches
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ClientClaim
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.shared.DeviceId
import com.circaso.shared.Realtime
import com.circaso.shared.RealtimeEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import com.datastax.oss.driver.api.core.servererrors.WriteType
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.test.KandraRuntimeHandle
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Modifier
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Delivery against a real Cassandra-compatible container (V005, the inbox in unlogged batches, the conditional
 * deletes, group bodies, client-id claims, chat-list markers, settings, the replaced phone's bundle) and its Redis
 * keys in memory.
 */
class DeliveryDataLayerTest {
    private val db: KandraRuntimeHandle =
        run {
            KandraTestBackend.newSession().use { admin ->
                admin.execute(KandraTestBackend.createKeyspaceCql(KEYSPACE))
                KandraMigrationRunner(admin, keyspace = KEYSPACE).run(
                    V001_CreateMessagingTables,
                    V002_CreateKeyDirectoryTables,
                    V003_CreateChatTables,
                    V004_AddChatOwner,
                    V005_CreateDeliveryTables,
                )
            }
            KandraTestcontainers.freshKeyspace(
                InboxRow::class,
                GroupMessageRow::class,
                MessageRecordRow::class,
                ClientMessageRow::class,
                MessagingSettingsRow::class,
                ChatRow::class,
                DirectChatClaimRow::class,
                MemberRow::class,
                UserChatRow::class,
                KeyBundleRow::class,
                OneTimePreKeyRow::class,
            )
        }

    private val batches = UnloggedBatches(db.runtime)
    private val delivery =
        DeliveryRepositoryImpl(
            batches,
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
        )
    private val markers = ChatMarkersImpl(batches)
    private val settings = SettingsRepositoryImpl(db.suspendRepository())
    private val chats =
        ChatRepositoryImpl(
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
            db.suspendRepository(),
        )
    private val keys = KeyRepositoryImpl(db.runtime, db.suspendRepository(), db.suspendRepository())
    private val ids = OrderedIds(Clock.systemUTC())
    private val now = Instant.now().truncatedTo(ChronoUnit.MILLIS)

    @AfterTest
    fun close() {
        db.close()
        KandraTestUtils.reset()
    }

    private fun row(
        userId: UserId,
        chatId: ChatId = ChatId.new(),
        ciphertext: String? = "MwohB",
        messageId: UUID = ids.next(),
    ) = InboxMessage(
        userId = userId,
        messageId = messageId,
        chatId = chatId,
        senderId = UserId.new(),
        kind = MessageKind.MESSAGE,
        ciphertext = ciphertext,
        groupBody = ciphertext == null,
        mediaIds = listOf(UUID.randomUUID(), UUID.randomUUID()),
        sentAt = now,
    )

    @Test
    fun `V005 has exactly the columns the entities name`() {
        val tables =
            mapOf(
                "inbox_by_user" to InboxRow::class.java,
                "group_messages" to GroupMessageRow::class.java,
                "messages_by_id" to MessageRecordRow::class.java,
                "sent_by_client_id" to ClientMessageRow::class.java,
                "settings" to MessagingSettingsRow::class.java,
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
    fun `inbox rows written in unlogged batches read back oldest first, a page at a time`() =
        runBlocking<Unit> {
            val brian = UserId.new()
            val written = List(5) { row(brian) } + row(brian, ciphertext = null)

            delivery.writeMessage(record(), written.shuffled(), batchSize = 2)

            val first = delivery.inbox(brian, null, 4)
            val rest = delivery.inbox(brian, first.last().messageId, 4)
            assertEquals(written, first + rest, "every field, in sending order")
            assertNull(rest.last().ciphertext, "a pointer has none")
        }

    @Test
    fun `an inbox row is claimed by one delete only, and rows of many inboxes go together`() =
        runBlocking<Unit> {
            val (brian, chidi) = List(2) { UserId.new() }
            val messageId = ids.next()
            val rows = listOf(row(brian, messageId = messageId), row(chidi, messageId = messageId))
            delivery.writeMessage(record(messageId), rows, 100)

            assertTrue(delivery.claimInboxRow(brian, messageId))
            assertFalse(delivery.claimInboxRow(brian, messageId))
            delivery.deleteInboxRows(listOf(brian, chidi), messageId, batchSize = 100)
            assertNull(delivery.inboxRow(chidi, messageId))
        }

    private fun record(messageId: UUID = ids.next()) =
        MessageRecord(messageId, ChatId.new(), UserId.new(), MessageKind.MESSAGE, now)

    @Test
    fun `a message record goes in the first batch and reads back`() =
        runBlocking<Unit> {
            val record = record()

            delivery.writeMessage(record, emptyList(), batchSize = 100)

            assertEquals(record, delivery.messageRecord(record.messageId))
            assertNull(delivery.messageRecord(ids.next()))
        }

    @Test
    fun `a group body is written, read and deleted`() =
        runBlocking<Unit> {
            val body = GroupBody(ChatId.new(), ids.next(), UserId.new(), "MwohB", now)

            delivery.writeGroupBody(body)
            assertEquals(body, delivery.groupBody(body.chatId, body.messageId))
            delivery.deleteGroupBody(body.chatId, body.messageId)
            assertNull(delivery.groupBody(body.chatId, body.messageId))
        }

    @Test
    fun `a client message id is claimed once and keeps the first message id until it is complete`() =
        runBlocking<Unit> {
            val (sender, clientId, first) = Triple(UserId.new(), UUID.randomUUID(), ids.next())

            assertNull(delivery.claimClientMessage(sender, clientId, first))
            assertEquals(
                ClientClaim(first, complete = false),
                delivery.claimClientMessage(sender, clientId, ids.next()),
            )
            delivery.completeClientMessage(sender, clientId, first)
            assertEquals(ClientClaim(first, complete = true), delivery.claimClientMessage(sender, clientId, ids.next()))
        }

    @Test
    fun `of many parallel claims of one inbox row exactly one wins`() =
        runBlocking<Unit> {
            val brian = UserId.new()
            val messageId = ids.next()
            delivery.writeMessage(record(messageId), listOf(row(brian, messageId = messageId)), 100)

            val claims = (1..8).map { async(Dispatchers.IO) { delivery.claimInboxRow(brian, messageId) } }.awaitAll()

            assertEquals(1, claims.count { it })
            assertNull(delivery.inboxRow(brian, messageId))
        }

    @Test
    fun `a claim whose lightweight transaction timed out counts as not claimed`() =
        runBlocking<Unit> {
            val timingOut =
                DeliveryRepositoryImpl(
                    batches,
                    db.suspendRepository(),
                    db.suspendRepository(),
                    db.suspendRepository(),
                    db.suspendRepository(),
                    conditional = { throw casTimeout() },
                )

            assertFalse(timingOut.claimInboxRow(UserId.new(), ids.next()))
        }

    @Test
    fun `batches are cut by size as well as rows, and large sender-key copies are all written`() =
        runBlocking<Unit> {
            val big = Base64.getEncoder().encodeToString(ByteArray(64 * 1_024))
            val messageId = ids.next()
            val copies = List(30) { row(UserId.new(), ciphertext = big, messageId = messageId) }
            val writes = copies.map { UnloggedBatches.Write("INSERT", listOf(it.userId.value, it.ciphertext!!)) }

            val plan = UnloggedBatches.plan(writes, batchSize = 100, maxBytes = UnloggedBatches.MAX_BATCH_BYTES)
            assertEquals(writes, plan.flatten(), "every write, in order")
            assertTrue(plan.size > 1)
            assertTrue(plan.all { batch -> batch.sumOf { it.estimatedBytes } <= UnloggedBatches.MAX_BATCH_BYTES })

            // The test database refuses batches over 50 KB (Cassandra's default; ScyllaDB's is 1 MB).
            val small =
                DeliveryRepositoryImpl(
                    UnloggedBatches(db.runtime, maxBatchBytes = 40 * 1_024),
                    db.suspendRepository(),
                    db.suspendRepository(),
                    db.suspendRepository(),
                    db.suspendRepository(),
                )
            small.writeMessage(record(messageId), copies, batchSize = 100)
            for (copy in copies) assertEquals(big, delivery.inboxRow(copy.userId, messageId)!!.ciphertext)
        }

    @Test
    fun `the markers set one column, and a line left behind by a late marker is not a chat`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = ChatId.new()
            val line = Member(chatId, amina, Role.MEMBER, now).toUserChatRow(ChatKind.GROUP)
            db.suspendRepository<UserChatRow>().save(line)
            val (last, read) = List(2) { ids.next() }

            markers.lastMessage(listOf(amina, brian), chatId, last, batchSize = 100)
            markers.readUpTo(amina, chatId, read)

            val mine = chats.userChat(amina, chatId)!!
            assertEquals(last, mine.lastMessageId)
            assertEquals(read, mine.readUpTo)
            assertEquals(ChatKind.GROUP, mine.kind, "the rest of the line is untouched")
            assertNull(chats.userChat(brian, chatId), "Brian's line was only a marker")
            assertTrue(chats.chatsOf(brian).isEmpty())
        }

    @Test
    fun `settings default to on and are kept once changed`() =
        runBlocking<Unit> {
            val amina = UserId.new()

            assertEquals(MessagingSettings(), settings.settings(amina))
            settings.save(amina, MessagingSettings(readReceipts = false, typing = true))
            assertEquals(MessagingSettings(readReceipts = false, typing = true), settings.settings(amina))
        }

    @Test
    fun `a replaced phone's bundle and prekeys go`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val oldPhone = DeviceId.new()
            keys.publish(bundle(amina, oldPhone), listOf(OneTimePreKey(1, "BQm"), OneTimePreKey(2, "BQn")), emptySet())
            delay(5)

            assertTrue(keys.removeBundle(amina, oldPhone, Instant.now()))

            assertNull(keys.bundle(amina))
            assertTrue(keys.oneTimeKeyIds(amina).isEmpty())
        }

    @Test
    fun `a PUT keys written after the replacement survives the late removal of the old phone's keys`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val (oldPhone, newPhone) = List(2) { DeviceId.new() }
            keys.publish(bundle(amina, oldPhone), listOf(OneTimePreKey(1, "BQm")), emptySet())
            delay(5)
            val replacedAt = Instant.now()
            delay(5)
            keys.publish(bundle(amina, newPhone), listOf(OneTimePreKey(7, "BQx")), setOf(1))

            assertFalse(keys.removeBundle(amina, oldPhone, replacedAt), "the new phone's bundle is not the old one")

            assertEquals(newPhone, keys.bundle(amina)!!.deviceId)
            assertEquals(setOf(7), keys.oneTimeKeyIds(amina), "written after the event, so kept")
        }

    private fun bundle(userId: UserId, deviceId: DeviceId) =
        KeyBundle(userId, deviceId, "BcF2", SignedPreKey(7, "BXa", "kq3"), now)

    // ---- Redis ----

    @Test
    fun `the pending counter counts down, and a lost one is never re-created`() =
        runBlocking<Unit> {
            val redis = MemoryRedis()
            val pending = RedisPendingCounts(redis)
            val messageId = ids.next()

            pending.start(messageId, 2)
            assertEquals(Duration.ofDays(30), redis.ttls["pending:$messageId"])
            assertEquals(1L, pending.countDown(messageId))
            pending.clear(messageId)
            assertNull(pending.countDown(messageId))
            assertNull(redis.values["pending:$messageId"])
        }

    @Test
    fun `receipts are kept 10 seconds, a batch is claimed once per window, and a read is never downgraded`() =
        runBlocking<Unit> {
            val redis = MemoryRedis()
            val buffer = RedisReceiptBuffer(redis, Duration.ofSeconds(5))
            val (messageId, brian, chidi) = Triple(ids.next(), UserId.new(), UserId.new())

            buffer.add(messageId, brian, ReceiptStatus.READ)
            buffer.add(messageId, brian, ReceiptStatus.DELIVERED)
            buffer.add(messageId, chidi, ReceiptStatus.DELIVERED)
            assertEquals(Duration.ofSeconds(10), redis.ttls["receipts:$messageId"])
            assertTrue(buffer.claimBatch(messageId))
            assertFalse(buffer.claimBatch(messageId))
            assertEquals(Duration.ofSeconds(5), redis.ttls["receipts-due:$messageId"])

            val first = buffer.takeBatch(messageId)
            assertEquals(mapOf(brian to ReceiptStatus.READ, chidi to ReceiptStatus.DELIVERED), first)
            assertNull(redis.hashes["receipts:$messageId"], "the batch took and cleared the hash")
            assertTrue(buffer.claimBatch(messageId), "a receipt after the take claims the next batch")
            buffer.add(messageId, chidi, ReceiptStatus.READ)
            assertEquals(mapOf(chidi to ReceiptStatus.READ), buffer.takeBatch(messageId), "only what is new")
        }

    @Test
    fun `a typing mark lasts 6 seconds and stopping clears it`() =
        runBlocking<Unit> {
            val redis = MemoryRedis()
            val marks = RedisTypingMarks(redis, Duration.ofSeconds(6))
            val (chatId, amina) = ChatId.new() to UserId.new()

            assertTrue(marks.start(chatId, amina))
            assertFalse(marks.start(chatId, amina))
            assertEquals(Duration.ofSeconds(6), redis.ttls["typing:$chatId:$amina"])
            marks.stop(chatId, amina)
            assertTrue(marks.start(chatId, amina))
        }

    @Test
    fun `a live frame goes once to everyone through deliverMany, with seq 0, and a failure is swallowed`() =
        runBlocking<Unit> {
            val sent = CopyOnWriteArrayList<Pair<Collection<UserId>, RealtimeEnvelope>>()
            var fail = false
            val realtime =
                object : Realtime {
                    override suspend fun deliver(userId: UserId, envelope: RealtimeEnvelope) =
                        error("live frames go through deliverMany")

                    override suspend fun deliverMany(
                        userIds: Collection<UserId>,
                        envelope: RealtimeEnvelope,
                    ): Resource<Unit> {
                        if (fail) error("redis down")
                        sent += userIds to envelope
                        return Resource.success(Unit)
                    }
                }
            val live = LiveFramesImpl(realtime, Clock.systemUTC())
            val users = List(3) { UserId.new() }

            val chatId = ChatId.new()
            live.send(users, TypingFrame(chatId, users[0], true))
            fail = true
            live.send(users, TypingFrame(chatId, users[0], false))

            val (to, envelope) = sent.single()
            assertEquals(users, to.toList())
            assertEquals(0L, envelope.seq)
            assertEquals("messaging", envelope.module)
            assertEquals("typing", envelope.type)
        }

    /** What the driver throws when a lightweight transaction's Paxos round times out, outcome unknown. */
    private fun casTimeout(): WriteTimeoutException {
        val node =
            db.runtime.session.metadata.nodes.values
                .first()
        return WriteTimeoutException(node, DefaultConsistencyLevel.SERIAL, 0, 1, WriteType.CAS)
    }

    private companion object {
        const val KEYSPACE = "circaso_messaging"
    }
}
