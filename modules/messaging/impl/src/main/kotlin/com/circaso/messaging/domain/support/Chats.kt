package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupDeleted
import com.circaso.messaging.api.MemberRemoved
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatView
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.MemberView
import com.circaso.messaging.domain.ports.MembersCache
import com.circaso.messaging.domain.ports.RealtimeFrames
import com.circaso.messaging.domain.repositories.ChatChanges
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.Leave
import com.circaso.messaging.domain.repositories.MessagingStore
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import java.util.Base64
import java.util.UUID

/** Shown for a member whose profile users can't give (a deleted account), as users names an empty profile. */
private const val FALLBACK_DISPLAY_NAME = "Circaso user"

/** The `direct_chats_by_pair` key: `<smaller userId>:<larger userId>`, the same whoever opens the chat. */
fun pairKey(a: UserId, b: UserId): String {
    val (first, second) = listOf(a.toString(), b.toString()).sorted()
    return "$first:$second"
}

/** A group's sealed name and avatar: base64 the phone made, at most [maxBytes] once decoded. */
fun isValidSealedMeta(raw: String, maxBytes: Int): Boolean {
    val bytes = runCatching { Base64.getDecoder().decode(raw) }.getOrNull() ?: return false
    return bytes.size in 1..maxBytes
}

/**
 * Who is in a chat, read through `msg:members:{chatId}` (10 minutes) so sends and fan-out don't read
 * `members_by_chat` each time. Every membership change clears the entry after it is written.
 */
class Membership(private val chats: ChatRepository, private val cache: MembersCache) {
    suspend fun members(chatId: ChatId): Set<UserId> {
        cache.get(chatId)?.let { return it }
        val members = chats.members(chatId).map { it.userId }.toSet()
        if (members.isNotEmpty()) cache.put(chatId, members)
        return members
    }

    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = userId in members(chatId)

    suspend fun changed(chatId: ChatId) = cache.clear(chatId)
}

/** Builds a chat as the API answers it: members with their public profiles from users, and roles. */
class ChatViews(private val chats: ChatRepository, private val profiles: Profiles) {
    suspend fun view(
        chat: Chat,
        lastMessageId: UUID?,
        created: Boolean? = null,
        notAdded: List<UserId>? = null,
    ): ChatView = views(listOf(chat to lastMessageId)).single().copy(created = created, notAdded = notAdded)

    /** Many chats with one profile lookup for every member of all of them. */
    suspend fun views(chatsWithLast: List<Pair<Chat, UUID?>>): List<ChatView> {
        val membersByChat = chatsWithLast.associate { (chat, _) -> chat.chatId to chats.members(chat.chatId) }
        val ids =
            membersByChat.values
                .flatten()
                .map { it.userId }
                .toSet()
        val found = if (ids.isEmpty()) emptyMap() else profiles.get(ids)
        return chatsWithLast.map { (chat, last) ->
            val members = membersByChat[chat.chatId].orEmpty().sortedBy { it.joinedAt }
            ChatView(chat, members.map { it.toView(found) }, last)
        }
    }

    private fun Member.toView(found: Map<UserId, PublicProfile>) =
        MemberView(found[userId] ?: PublicProfile(userId, FALLBACK_DISPLAY_NAME, null, null, null), role, joinedAt)
}

/**
 * The membership frames (Messaging → Socket frames). `member.added` and `member.removed` go to the members and the
 * person themselves, one frame per person; they tell phones to send or rotate sender keys.
 */
class ChatNotifier(private val frames: RealtimeFrames) {
    suspend fun memberAdded(
        recipients: Collection<UserId>,
        chatId: ChatId,
        added: UserId,
        by: UserId,
    ) = frames.send(recipients, "member.added", memberData(chatId, added, by))

    suspend fun memberRemoved(
        recipients: Collection<UserId>,
        chatId: ChatId,
        removed: UserId,
        by: UserId,
    ) = frames.send(recipients, "member.removed", memberData(chatId, removed, by))

    suspend fun chatUpdated(recipients: Collection<UserId>, chat: Chat) =
        frames.send(
            recipients,
            "chat.updated",
            mapOf(
                "chatId" to chat.chatId.toString(),
                "metaVersion" to chat.metaVersion,
                "version" to chat.version,
            ),
        )

    private fun memberData(
        chatId: ChatId,
        userId: UserId,
        by: UserId,
    ) = mapOf(
        "chatId" to chatId.toString(),
        "userId" to userId.toString(),
        "byUserId" to by.toString(),
    )
}

/**
 * Changes a chat through its version, re-reading it when another change won in between, a few times at most.
 * [change] sees the current chat and answers the new one, or an error that stops the attempt (the chat is full,
 * the caller isn't an admin any more).
 */
class ChatWriter(private val chats: ChatRepository, private val config: MessagingConfig) {
    suspend fun update(chatId: ChatId, change: suspend (Chat) -> Resource<Chat>): Resource<Chat> {
        repeat(config.chatWriteAttempts) {
            val current = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
            val next =
                when (val decided = change(current)) {
                    is Resource.Error -> return decided
                    is Resource.Success -> decided.data
                }
            chats.updateChat(current, next)?.let { return Resource.success(it) }
        }
        return Resource.error(MessagingError.VersionConflict)
    }
}

/**
 * Takes someone out of a group, for a leave and for a removal alike: the member count through the chat's version,
 * then their rows with `MemberRemoved` in one batch, then `member.removed` to everyone who was in it, the person
 * included, so the remaining phones rotate their sender keys. The last member leaving deletes the group.
 */
class MemberRemoval(
    private val chats: ChatRepository,
    private val writer: ChatWriter,
    private val store: MessagingStore,
    private val membership: Membership,
    private val notifier: ChatNotifier,
    private val bus: EventBus,
) {
    suspend fun remove(member: Member, by: UserId): Resource<Unit> {
        val chatId = member.chatId
        val before = chats.members(chatId).map { it.userId }.toSet()
        if (before == setOf(member.userId)) return deleteGroup(member)

        val counted =
            writer.update(chatId) { latest ->
                Resource.success(latest.copy(memberCount = (latest.memberCount - 1).coerceAtLeast(0)))
            }
        if (counted is Resource.Error) return counted
        val event = bus.envelope(MemberRemoved(chatId, member.userId, by))
        store.commit(ChatChanges(leaves = listOf(Leave(member, event))))
        membership.changed(chatId)
        notifier.memberRemoved(before, chatId, member.userId, by)
        return Resource.success(Unit)
    }

    private suspend fun deleteGroup(last: Member): Resource<Unit> {
        val chatId = last.chatId
        val removed = bus.envelope(MemberRemoved(chatId, last.userId, last.userId))
        store.commit(
            ChatChanges(
                deletedChats = listOf(chatId),
                leaves = listOf(Leave(last, removed)),
                events = listOf(bus.envelope(GroupDeleted(chatId))),
            ),
        )
        membership.changed(chatId)
        notifier.memberRemoved(listOf(last.userId), chatId, last.userId, last.userId)
        return Resource.success(Unit)
    }
}

/**
 * Whether two people are in a group together: inside a group, members start sessions with each other without a
 * contact connection (Messaging → Depends on). Two chat-list reads, intersected, whatever the number of groups.
 */
class SharedGroups(private val chats: ChatRepository) {
    suspend fun share(a: UserId, b: UserId): Boolean {
        val mine = groupsOf(a)
        if (mine.isEmpty()) return false
        return groupsOf(b).any { it in mine }
    }

    private suspend fun groupsOf(userId: UserId): Set<ChatId> =
        chats
            .chatsOf(userId)
            .filter { it.kind == ChatKind.GROUP }
            .map { it.chatId }
            .toSet()
}
