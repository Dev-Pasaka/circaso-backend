package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.UpdateGroupCommand
import com.circaso.messaging.domain.support.ChatNotifier
import com.circaso.messaging.domain.support.ChatViews
import com.circaso.messaging.domain.support.MemberRemoval
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.usecases.AddMembers
import com.circaso.messaging.domain.usecases.CreateGroup
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.GetAdmins
import com.circaso.messaging.domain.usecases.GetChat
import com.circaso.messaging.domain.usecases.GetMembers
import com.circaso.messaging.domain.usecases.GetOwner
import com.circaso.messaging.domain.usecases.IsGroup
import com.circaso.messaging.domain.usecases.LeaveGroup
import com.circaso.messaging.domain.usecases.ListChats
import com.circaso.messaging.domain.usecases.ListGroupIds
import com.circaso.messaging.domain.usecases.OpenDirectChat
import com.circaso.messaging.domain.usecases.RemoveMember
import com.circaso.messaging.domain.usecases.ReplayFrames
import com.circaso.messaging.domain.usecases.SetRole
import com.circaso.messaging.domain.usecases.UpdateGroup
import com.circaso.messaging.presentation.api.ChatDirectoryImpl
import com.circaso.messaging.presentation.services.ChatService
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64
import java.util.UUID

/** Chats and membership wired over the in-memory fakes, as `di` wires them over PostgreSQL and Redis. */
class ChatHarness(val config: MessagingConfig = MessagingConfig()) {
    val chats = MemoryChats()
    val transactor = MemoryTransactor()
    val outbox = MemoryOutbox()
    val cache = MemoryMembersCache()
    val frames = RecordingFrames()
    val bus = MemoryBus()
    val reach = FakeReach()
    val keys = MemoryKeys()
    val clock = TestClock()
    private val profiles = FakeProfiles()

    val membership = Membership(chats, cache)
    private val views = ChatViews(chats, profiles)
    private val notifier = ChatNotifier(frames)
    private val removal = MemberRemoval(chats, transactor, outbox, membership, notifier, bus)

    val openDirectChat = OpenDirectChat(chats, transactor, outbox, reach, views, bus, clock)
    val createGroup =
        CreateGroup(
            chats,
            transactor,
            outbox,
            reach,
            views,
            notifier,
            bus,
            config,
            clock,
        )
    val listChats = ListChats(chats, views)
    val getChat = GetChat(chats, views)
    val updateGroup = UpdateGroup(chats, transactor, membership, views, notifier, config)
    val addMembers = AddMembers(chats, transactor, outbox, reach, membership, views, notifier, bus, config, clock)
    val removeMember = RemoveMember(removal)
    val leaveGroup = LeaveGroup(removal)
    val setRole = SetRole(chats, transactor, membership, views, notifier)
    val replayFrames = ReplayFrames(frames)
    val fetchKeyBundle = FetchKeyBundle(keys, reach, chats, MemoryRateLimit(), config)
    val service =
        ChatService(
            openDirectChat,
            createGroup,
            listChats,
            getChat,
            updateGroup,
            addMembers,
            removeMember,
            leaveGroup,
            setRole,
            GetMembers(membership),
            GetAdmins(membership),
            IsGroup(chats),
            ListGroupIds(chats),
            GetOwner(chats),
        )
    val directory = ChatDirectoryImpl(service)

    val sealed: String = Base64.getEncoder().encodeToString(ByteArray(64) { 7 })

    /** Lets [from] reach each of [to], one way. */
    fun connect(from: UserId, to: Collection<UserId>) {
        for (person in to) reach.pairs += from to person
    }

    fun connect(from: UserId, to: UserId) = connect(from, listOf(to))

    suspend fun group(owner: UserId, members: List<UserId> = emptyList()): ChatView {
        connect(owner, members)
        return createGroup(command(owner, members.toSet())).data()
    }

    /** A create with its own `Idempotency-Key`, unless [key] repeats one. */
    fun command(
        owner: UserId,
        members: Set<UserId>,
        sealedMeta: String = sealed,
        key: String = UUID.randomUUID().toString(),
    ) = CreateGroupCommand(
        owner,
        sealedMeta,
        members,
        key,
    )

    suspend fun update(
        caller: UserId,
        chatId: ChatId,
        version: Long,
        sealedMeta: String? = null,
        membersCanAdd: Boolean? = null,
    ) = updateGroup(UpdateGroupCommand(caller, chatId, sealedMeta, membersCanAdd, version))

    /** [userId]'s role as the chat sees it (the owner is the chat's `ownerId`), or null when not a member. */
    fun role(chatId: ChatId, userId: UserId): Role? {
        val member = chats.members[chatId]?.get(userId) ?: return null
        return chats.chats[chatId]?.roleOf(member)
    }
}

fun <T> Resource<T>.data(): T = (this as? Resource.Success)?.data ?: error("expected success, got $this")

fun Resource<*>.errorCode(): String = (this as? Resource.Error)?.error?.code ?: error("expected an error, got $this")

/** A clock a test moves on by hand, so "who joined first" never depends on two calls landing in one millisecond. */
class TestClock(private var now: Instant = Instant.parse("2026-10-26T09:00:00Z")) : Clock() {
    fun advance(by: Duration = Duration.ofMinutes(1)) {
        now = now.plus(by)
    }

    override fun instant(): Instant = now

    override fun getZone(): ZoneId = ZoneOffset.UTC

    override fun withZone(zone: ZoneId): Clock = this
}
