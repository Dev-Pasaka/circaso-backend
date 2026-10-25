package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.UserChat
import com.circaso.messaging.domain.ports.LoggedFrame
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.messaging.domain.ports.RealtimeLog
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.shared.DomainEvent
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventSubscription
import com.circaso.shared.UserId
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import kotlinx.coroutines.yield
import kotlinx.serialization.KSerializer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Chats in memory, with the same rules as the tables: the pair claim and the versioned update are atomic, and a
 * commit applies one [ChatChanges] as a unit (what the logged batch does). [beforeUpdate] runs just before a
 * versioned update, so a test can change the chat in between.
 */
class MemoryChats :
    ChatRepository,
    MessagingStore {
    val claims = ConcurrentHashMap<String, ChatId>()
    val chats = ConcurrentHashMap<ChatId, Chat>()
    val members = ConcurrentHashMap<ChatId, MutableMap<UserId, Member>>()
    val lines = ConcurrentHashMap<UserId, MutableMap<ChatId, UserChat>>()
    val commits = CopyOnWriteArrayList<ChatChanges>()
    var beforeUpdate: suspend () -> Unit = {}

    override suspend fun claimPair(pair: String, chatId: ChatId): Boolean {
        yield()
        return claims.putIfAbsent(pair, chatId) == null
    }

    override suspend fun pairChat(pair: String) = claims[pair]

    override suspend fun chat(chatId: ChatId) = chats[chatId]

    override suspend fun updateChat(old: Chat, new: Chat): Chat? {
        beforeUpdate()
        synchronized(this) {
            val stored = chats[old.chatId] ?: return null
            if (stored.version != old.version) return null
            val written = new.copy(version = old.version + 1)
            chats[old.chatId] = written
            return written
        }
    }

    override suspend fun members(chatId: ChatId) = synchronized(this) { members[chatId]?.values?.toList().orEmpty() }

    override suspend fun member(chatId: ChatId, userId: UserId) = synchronized(this) { members[chatId]?.get(userId) }

    override suspend fun chatsOf(userId: UserId) = synchronized(this) { lines[userId]?.values?.toList().orEmpty() }

    override suspend fun userChat(userId: UserId, chatId: ChatId) = synchronized(this) { lines[userId]?.get(chatId) }

    override suspend fun commit(changes: ChatChanges) {
        yield()
        synchronized(this) {
            commits += changes
            for (chat in changes.chats) chats[chat.chatId] = chat
            for (chatId in changes.deletedChats) chats.remove(chatId)
            for (join in changes.joins) {
                val member = join.member
                members.getOrPut(member.chatId) { mutableMapOf() }[member.userId] = member
                lines.getOrPut(member.userId) { mutableMapOf() }[member.chatId] =
                    UserChat(member.userId, member.chatId, join.kind, null, null, member.joinedAt)
            }
            for (member in changes.roles) members.getOrPut(member.chatId) { mutableMapOf() }[member.userId] = member
            for (leave in changes.leaves) {
                members[leave.member.chatId]?.remove(leave.member.userId)
                lines[leave.member.userId]?.remove(leave.member.chatId)
            }
        }
    }

    /** Every event committed, in order: chat-level ones and those riding with a join or leave. */
    fun eventTypes(): List<String> =
        commits.flatMap { c -> c.events + c.joins.mapNotNull { it.event } + c.leaves.mapNotNull { it.event } }.map {
            it.type
        }
}

class MemoryMembersCache : MembersCache {
    val entries = ConcurrentHashMap<ChatId, Set<UserId>>()
    var reads = 0

    override suspend fun get(chatId: ChatId): Set<UserId>? {
        reads++
        return entries[chatId]
    }

    override suspend fun put(chatId: ChatId, members: Set<UserId>) {
        entries[chatId] = members
    }

    override suspend fun clear(chatId: ChatId) {
        entries.remove(chatId)
    }
}

/** Records each replayed frame per recipient, numbering them per user as `msg:seq:{userId}` does. */
class RecordingFrames :
    RealtimeFrames,
    RealtimeLog {
    data class Sent(
        val to: UserId,
        val seq: Long,
        val type: String,
        val data: Map<String, Any>,
    )

    val sent = CopyOnWriteArrayList<Sent>()
    private val seqs = ConcurrentHashMap<UserId, Long>()

    /** False only counts: a full group's creation is a million frames, too many to keep in a list. */
    var recording = true
    var count = 0L

    override suspend fun send(
        userIds: Collection<UserId>,
        type: String,
        data: Map<String, Any>,
    ) {
        val recipients = userIds.distinct()
        count += recipients.size
        if (!recording) return
        sent.addAll(recipients.map { Sent(it, seqs.merge(it, 1L, Long::plus)!!, type, data) })
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
