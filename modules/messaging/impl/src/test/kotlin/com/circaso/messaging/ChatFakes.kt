package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.MessagingFrame
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatLine
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.UserChat
import com.circaso.messaging.domain.ports.LoggedFrame
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.messaging.domain.ports.RealtimeLog
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One transaction at a time: every `run` and `attempt` holds one lock, the in-memory stand-in for the row locks the
 * real transactions take, so use cases that race in a test run one after the other, as they do on PostgreSQL. An
 * `attempt` that answers an error doesn't undo what it wrote: the use cases decide before they write, and the
 * rollback itself is tested on PostgreSQL.
 */
class MemoryTransactor : Transactor {
    private val lock = Mutex()
    val operations = CopyOnWriteArrayList<String>()

    override suspend fun <T> run(operation: String, block: suspend () -> T): T =
        lock.withLock {
            operations += operation
            block()
        }

    override suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T> =
        run(operation, block)
}

/** The outbox rows written, in order. */
class MemoryOutbox : MessagingOutbox {
    val written = CopyOnWriteArrayList<EventEnvelope>()

    override suspend fun write(events: List<EventEnvelope>) {
        written += events
    }

    fun types(): List<String> = written.map { it.type }
}

/**
 * Chats in memory, with the same rules as the tables: one direct chat per pair, a removal that one caller takes, a
 * versioned update, the conditional seat reservation, and members that go with their chat. [records] are the
 * message records the delivery fakes write, so the newest one answers `lastMessageId` as `message_records` does.
 */
class MemoryChats : ChatRepository {
    val chats = ConcurrentHashMap<ChatId, Chat>()
    val members = ConcurrentHashMap<ChatId, MutableMap<UserId, Member>>()
    val readMarks = ConcurrentHashMap<Pair<UserId, ChatId>, UUID>()
    val records = ConcurrentHashMap<UUID, MessageRecord>()

    override suspend fun chat(chatId: ChatId) = chats[chatId]

    override suspend fun lockChat(chatId: ChatId) = chats[chatId]

    override suspend fun insertChat(chat: Chat): Boolean =
        synchronized(this) {
            if (chats.containsKey(chat.chatId)) return false
            val pair = chat.directPair?.toList()?.toSet()
            if (pair != null && chats.values.any { it.directPair?.toList()?.toSet() == pair }) return false
            chats[chat.chatId] = chat
            true
        }

    override suspend fun directChat(
        a: UserId,
        b: UserId,
        viaSpaceId: java.util.UUID?,
    ): Chat? =
        chats.values.firstOrNull { it.directPair?.toList()?.toSet() == setOf(a, b) && it.viaSpaceId == viaSpaceId }

    override suspend fun updateChat(old: Chat, new: Chat): Chat? {
        synchronized(this) {
            val stored = chats[old.chatId] ?: return null
            if (stored.version != old.version) return null
            val written = new.copy(version = old.version + 1)
            chats[old.chatId] = written
            return written
        }
    }

    override suspend fun reserveSeats(
        chatId: ChatId,
        seats: Int,
        max: Int,
    ): Chat? =
        synchronized(this) {
            val stored = chats[chatId] ?: return null
            if (stored.memberCount + seats > max) return null
            val written = stored.copy(memberCount = stored.memberCount + seats, version = stored.version + 1)
            chats[chatId] = written
            written
        }

    override suspend fun deleteChat(chatId: ChatId) {
        synchronized(this) {
            chats.remove(chatId)
            members.remove(chatId)
        }
    }

    override suspend fun members(chatId: ChatId): List<Member> =
        synchronized(this) { members[chatId]?.values?.toList().orEmpty() }

    override suspend fun members(chatIds: Collection<ChatId>): Map<ChatId, List<Member>> =
        chatIds.associateWith { members(it) }.filterValues { it.isNotEmpty() }

    override suspend fun member(chatId: ChatId, userId: UserId): Member? =
        synchronized(this) { members[chatId]?.get(userId) }

    override suspend fun addMembers(members: List<Member>) {
        synchronized(this) {
            for (member in members) this.members.getOrPut(member.chatId) { mutableMapOf() }[member.userId] = member
        }
    }

    override suspend fun removeMember(chatId: ChatId, userId: UserId): Member? =
        synchronized(this) { members[chatId]?.remove(userId) }

    override suspend fun setRole(member: Member): Boolean =
        synchronized(this) {
            val rows = members[member.chatId] ?: return false
            if (member.userId !in rows) return false
            rows[member.userId] = member
            true
        }

    override suspend fun chatList(userId: UserId): List<ChatLine> =
        chatIdsOf(userId).mapNotNull { chatId -> chats[chatId]?.let { ChatLine(it, lastMessageId(chatId)) } }

    override suspend fun chatIdsOf(userId: UserId): List<ChatId> =
        synchronized(this) { members.filterValues { userId in it }.keys.toList() }

    override suspend fun userChat(userId: UserId, chatId: ChatId): UserChat? {
        val member = member(chatId, userId) ?: return null
        val chat = chats[chatId] ?: return null
        return UserChat(userId, chatId, chat.kind, readMarks[userId to chatId], member.joinedAt)
    }

    override suspend fun lastMessageId(chatId: ChatId): UUID? =
        records.values
            .filter { it.chatId == chatId && it.kind == MessageKind.MESSAGE }
            .maxByOrNull { it.messageId.toString() }
            ?.messageId

    override suspend fun moveReadMarker(
        userId: UserId,
        chatId: ChatId,
        upTo: UUID,
    ): Boolean =
        synchronized(this) {
            if (members[chatId]?.containsKey(userId) != true) return false
            val previous = readMarks[userId to chatId]
            if (previous != null && previous.toString() >= upTo.toString()) return false
            readMarks[userId to chatId] = upTo
            true
        }

    override suspend fun shareGroup(a: UserId, b: UserId): Boolean =
        synchronized(this) {
            members.any { (chatId, rows) -> chats[chatId]?.kind == ChatKind.GROUP && a in rows && b in rows }
        }
}

/** `msg:members` in memory: a marked chat answers nothing and isn't refilled until [expireMarkers]. */
class MemoryMembersCache : MembersCache {
    val entries = ConcurrentHashMap<ChatId, Set<UserId>>()
    val marked = ConcurrentHashMap.newKeySet<ChatId>()

    override suspend fun get(chatId: ChatId): Set<UserId>? = if (chatId in marked) null else entries[chatId]

    override suspend fun fillIfAbsent(chatId: ChatId, members: Set<UserId>) {
        if (chatId !in marked) entries.putIfAbsent(chatId, members)
    }

    override suspend fun markChanged(chatId: ChatId) {
        entries.remove(chatId)
        marked += chatId
    }

    fun expireMarkers() = marked.clear()
}

/** Records each replayed frame per recipient, numbering them per user as `msg:seq:{userId}` does. */
class RecordingFrames :
    RealtimeFrames,
    RealtimeLog {
    data class Sent(
        val to: UserId,
        val seq: Long,
        val type: String,
        val frame: MessagingFrame,
    ) {
        inline fun <reified T : MessagingFrame> data(): T = frame as T
    }

    val sent = CopyOnWriteArrayList<Sent>()
    private val seqs = ConcurrentHashMap<UserId, Long>()

    /** False only counts: a full group's creation is a million frames, too many to keep in a list. */
    var recording = true
    var count = 0L

    override suspend fun send(userIds: Collection<UserId>, frame: MessagingFrame) {
        val recipients = userIds.distinct()
        count += recipients.size
        if (!recording) return
        sent.addAll(recipients.map { Sent(it, seqs.merge(it, 1L, Long::plus)!!, frame.type, frame) })
    }

    override suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ) = sent.filter { it.to == userId && it.seq > seq }.take(limit).map { LoggedFrame(it.seq, it.type) }

    override suspend fun latest(userId: UserId) = seqs[userId]

    fun to(userId: UserId) = sent.filter { it.to == userId }

    /** Forgets frames up to [seq] for [userId], as the 7-day TTL would. */
    fun expire(userId: UserId, seq: Long) = sent.removeIf { it.to == userId && it.seq <= seq }
}

class FakeProfiles : Profiles {
    override suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile> =
        ids.associateWith { PublicProfile(it, "Person ${it.toString().takeLast(4)}", null, null, null) }
}

class MemoryBus : EventBus {
    val published = CopyOnWriteArrayList<EventEnvelope>()

    override fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>) =
        EventEnvelope.of(event, eventSerializer)

    override suspend fun publish(envelope: EventEnvelope) {
        published += envelope
    }

    override fun subscribe(subscription: EventSubscription) = Unit
}
