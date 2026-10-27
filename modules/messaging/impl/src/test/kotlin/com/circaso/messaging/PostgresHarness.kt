package com.circaso.messaging

import com.circaso.identity.api.DeviceSignatures
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.data.repositories.ChatRepositoryImpl
import com.circaso.messaging.data.repositories.DatabaseTransactor
import com.circaso.messaging.data.repositories.DeliveryRepositoryImpl
import com.circaso.messaging.data.repositories.KeyRepositoryImpl
import com.circaso.messaging.data.repositories.MessagingOutboxImpl
import com.circaso.messaging.data.repositories.SettingsRepositoryImpl
import com.circaso.messaging.di.messagingDatabaseSpec
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.KeyUploadProof
import com.circaso.messaging.domain.models.RecipientCopy
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.InboxRemoval
import com.circaso.messaging.domain.support.KeyProofs
import com.circaso.messaging.domain.support.MemberRemoval
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessageSenders
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.messaging.domain.support.ReceiptBatcher
import com.circaso.messaging.domain.usecases.AckDelivered
import com.circaso.messaging.domain.usecases.AddMembers
import com.circaso.messaging.domain.usecases.AddOneTimeKeys
import com.circaso.messaging.domain.usecases.CreateGroup
import com.circaso.messaging.domain.usecases.DeleteForEveryone
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.LeaveGroup
import com.circaso.messaging.domain.usecases.ListChats
import com.circaso.messaging.domain.usecases.MarkRead
import com.circaso.messaging.domain.usecases.OpenDirectChat
import com.circaso.messaging.domain.usecases.PublishKeys
import com.circaso.messaging.domain.usecases.ReadInbox
import com.circaso.messaging.domain.usecases.RemoveMember
import com.circaso.messaging.domain.usecases.RetireReplacedPhone
import com.circaso.messaging.domain.usecases.SendMessage
import com.circaso.messaging.domain.usecases.SetRole
import com.circaso.messaging.domain.usecases.UpdateGroup
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ReachPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Messaging's use cases over the real data layer on PostgreSQL (infrastructure's test container, migrated by
 * messaging's own Flyway scripts), with Redis, users and the gateway faked in memory: the harness for the races only
 * the database settles. The clock is the real one, because message ids pick their daily partition by their time.
 * [outbox] and [delivery] can be swapped for wrappers that fail part-way, to see a transaction roll back.
 */
class PostgresHarness(
    val config: MessagingConfig = MessagingConfig(),
    outbox: (Database) -> MessagingOutbox = ::MessagingOutboxImpl,
    delivery: (DeliveryRepository) -> DeliveryRepository = { it },
) {
    val database: Database = TestPostgres.database(messagingDatabaseSpec)
    val clock: Clock = Clock.systemUTC()
    val transactor: Transactor = DatabaseTransactor(database)
    val chats: ChatRepository = ChatRepositoryImpl(database)
    val keys = KeyRepositoryImpl(database)
    val delivery: DeliveryRepository = delivery(DeliveryRepositoryImpl(database, clock))
    val settings = SettingsRepositoryImpl(database)
    private val outbox = outbox(database)

    val cache = MemoryMembersCache()
    val frames = RecordingFrames().apply { recording = false }
    val live = RecordingLiveFrames()
    val bus = MemoryBus()
    val reach = AnyoneReaches()
    val pending = MemoryPending()
    val receipts = MemoryReceipts()
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val membership = Membership(chats, cache)
    private val views = ChatViews(chats, FakeProfiles())
    private val chatNotifier = ChatNotifier(frames)
    private val notifier = DeliveryNotifier(live, frames, config)
    private val removal = MemberRemoval(chats, transactor, this.outbox, membership, chatNotifier, bus)
    private val batcher = ReceiptBatcher(receipts, notifier, background, clock, config)
    private val inboxRemoval = InboxRemoval(this.delivery, pending)
    private val senders = MessageSenders(this.delivery)
    private val signatures = AcceptingSignatures()
    private val proofs = KeyProofs(signatures, clock, config.proofWindow)

    val openDirectChat = OpenDirectChat(chats, transactor, this.outbox, reach, views, bus, clock)
    val createGroup = CreateGroup(chats, transactor, this.outbox, reach, views, chatNotifier, bus, config, clock)
    val addMembers =
        AddMembers(chats, transactor, this.outbox, reach, membership, views, chatNotifier, bus, config, clock)
    val removeMember = RemoveMember(removal)
    val leaveGroup = LeaveGroup(removal)
    val setRole = SetRole(chats, transactor, membership, views, chatNotifier)
    val updateGroup = UpdateGroup(chats, transactor, membership, views, chatNotifier, config)
    val listChats = ListChats(chats, views)
    val publishKeys = PublishKeys(keys, transactor, proofs, clock)
    val addOneTimeKeys = AddOneTimeKeys(keys, transactor, proofs, config, clock)
    val fetchKeyBundle = FetchKeyBundle(keys, reach, chats, MemoryRateLimit(), config)
    val sendMessage =
        SendMessage(
            chats,
            membership,
            reach,
            this.delivery,
            transactor,
            pending,
            notifier,
            OrderedIds(clock),
            background,
            config,
            clock,
        )
    val readInbox = ReadInbox(this.delivery, config)
    val ackDelivered = AckDelivered(this.delivery, inboxRemoval, batcher, clock)
    val markRead = MarkRead(chats, settings, senders, batcher, clock)
    val deleteForEveryone =
        DeleteForEveryone(chats, membership, this.delivery, transactor, pending, senders, notifier, config, clock)
    val retireReplacedPhone =
        RetireReplacedPhone(keys, transactor, this.delivery, inboxRemoval, chats, membership, notifier)

    val sealed: String = Base64.getEncoder().encodeToString(ByteArray(64) { 7 })

    /** A group of [owner] and [members], made in one create. */
    suspend fun group(owner: UserId, members: Collection<UserId>): ChatId =
        createGroup(CreateGroupCommand(owner, sealed, members.toSet(), UUID.randomUUID().toString()))
            .data()
            .chat.chatId

    suspend fun direct(a: UserId, b: UserId): ChatId =
        openDirectChat(a, b)
            .data()
            .chat.chatId

    fun directCommand(
        from: UserId,
        to: UserId,
        chatId: ChatId,
        clientMessageId: UUID = UUID.randomUUID(),
    ) = SendMessageCommand(
        from,
        chatId,
        clientMessageId,
        MessageKind.MESSAGE,
        null,
        listOf(RecipientCopy(to, cipher())),
        emptyList(),
        pushable = true,
    )

    fun groupCommand(
        from: UserId,
        chatId: ChatId,
        kind: MessageKind = MessageKind.MESSAGE,
        clientMessageId: UUID = UUID.randomUUID(),
    ) = SendMessageCommand(
        from,
        chatId,
        clientMessageId,
        kind,
        cipher(),
        emptyList(),
        emptyList(),
        pushable = true,
    )

    /** One number from a query run as the superuser, for checking what the code under test wrote. */
    fun count(sql: String, vararg values: Any): Int =
        TestPostgres.superuser { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    companion object {
        fun cipher(bytes: Int = 48): String = Base64.getEncoder().encodeToString(ByteArray(bytes) { (it * 7).toByte() })

        /** How often each "exactly one wins" race runs (the plan's done-when: 50 runs in a loop). */
        const val RACE_RUNS = 50

        /** The smallest UUIDv7 of [at]'s millisecond: a daily partition's bound, as infrastructure computes it. */
        fun lowestUuid7(at: Instant): String {
            val millis = at.toEpochMilli().toString(16).padStart(12, '0')
            return "${millis.take(8)}-${millis.drop(8)}-7000-8000-000000000000"
        }

        /** A key upload whose proof passes: the time is now and [AcceptingSignatures] takes any signature. */
        fun proof(clock: Clock) = KeyUploadProof(ByteArray(1), clock.instant(), ByteArray(1))

        /** Runs [block] [times] times at once on the default dispatcher, as concurrent requests would. */
        suspend fun <T> concurrently(times: Int, block: suspend (Int) -> T): List<T> =
            coroutineScope { (0 until times).map { async(Dispatchers.Default) { block(it) } }.awaitAll() }
    }
}

/** Identity's device signatures, all valid: the data tests drive the key use cases without signing. */
class AcceptingSignatures : DeviceSignatures {
    override suspend fun verify(
        userId: UserId,
        deviceId: DeviceId,
        payload: ByteArray,
        signature: ByteArray,
    ): Resource<Unit> = Resource.success(Unit)
}

/** Users' reach, answered yes for everyone: the data tests are about the database, not the reach rule. */
class AnyoneReaches : ReachPolicy {
    override suspend fun canReach(from: UserId, to: UserId) = true

    override suspend fun reachable(from: UserId, to: Set<UserId>) = to

    override suspend fun blockedEitherWay(a: UserId, b: UserId) = false
}
