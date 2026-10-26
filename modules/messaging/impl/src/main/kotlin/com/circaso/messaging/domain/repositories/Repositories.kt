package com.circaso.messaging.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.UserChat
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId

/** The key directory: `key_bundles` and `one_time_prekeys`. */
interface KeyRepository {
    suspend fun bundle(userId: UserId): KeyBundle?

    /** The ids of the one-time prekeys still waiting to be used. At most 100, so reading them all is cheap. */
    suspend fun oneTimeKeyIds(userId: UserId): Set<Int>

    /**
     * Writes [bundle] and [oneTimePreKeys], and deletes the one-time prekeys in [staleKeyIds], in one batch. The
     * caller leaves out of [staleKeyIds] any id it is writing again: a delete and an insert in one batch share a
     * timestamp, and the delete would win.
     */
    suspend fun publish(
        bundle: KeyBundle,
        oneTimePreKeys: List<OneTimePreKey>,
        staleKeyIds: Set<Int>,
    )

    /**
     * Inserts each key only if its id is not stored, so a stored key is never overwritten. True when every key was
     * new; otherwise the keys this call did insert are deleted again and it answers false, so the top-up is refused
     * whole.
     */
    suspend fun addOneTimeKeys(userId: UserId, keys: List<OneTimePreKey>): Boolean

    /**
     * Takes one one-time prekey and deletes it with a lightweight transaction, so two callers never get the same
     * key. Null when none are left, or (rarely) when every key it tried was taken first by someone else.
     */
    suspend fun claimOneTimeKey(userId: UserId): OneTimePreKey?
}

/** Chats and membership: `chats`, `direct_chats_by_pair`, `members_by_chat` and `chats_by_user`. */
interface ChatRepository {
    /** Claims the pair for [chatId] with a lightweight transaction; false when the pair already has a chat. */
    suspend fun claimPair(pair: String, chatId: ChatId): Boolean

    /** The chat a pair claimed, or null. */
    suspend fun pairChat(pair: String): ChatId?

    suspend fun chat(chatId: ChatId): Chat?

    /**
     * Writes [new] only if the stored chat is still at [old]'s version, and returns it with its new version; null
     * when someone changed the chat in between. Every write to a chat after the first goes through here.
     */
    suspend fun updateChat(old: Chat, new: Chat): Chat?

    /** Every member: at most 1,000, in one partition. */
    suspend fun members(chatId: ChatId): List<Member>

    suspend fun member(chatId: ChatId, userId: UserId): Member?

    /**
     * Deletes [userId]'s member row with a lightweight transaction (`IF EXISTS`): true for the one caller whose delete
     * applied, false when the row was already gone. Of a remove and a leave racing, only one goes on.
     */
    suspend fun claimRemoval(chatId: ChatId, userId: UserId): Boolean

    /**
     * Sets [member]'s role only if their row still exists (`IF EXISTS`), so a role written after the person left
     * never puts their row back. False when it was gone; the chat's `ownerId` is what counts, so a skipped OWNER
     * row is harmless.
     */
    suspend fun updateRole(member: Member): Boolean

    /** The user's chat list. */
    suspend fun chatsOf(userId: UserId): List<UserChat>

    /** One line of the user's chat list, or null when they aren't in that chat. */
    suspend fun userChat(userId: UserId, chatId: ChatId): UserChat?
}

/** Someone joining a chat: their member row and chat-list line, with the event that says so. */
data class Join(val member: Member, val kind: ChatKind, val event: EventEnvelope? = null)

/**
 * Someone leaving or removed: their chat-list line goes, with the event that says so. The member row is not part of
 * it: [ChatRepository.claimRemoval] deletes that afterwards, so a failed batch leaves the person a member and a
 * retry completes the removal.
 */
data class Leave(val member: Member, val event: EventEnvelope? = null)

/**
 * One operation's rows and its outbox rows. [chats] are new chats (later changes go through
 * [ChatRepository.updateChat]); roles change through [ChatRepository.updateRole].
 */
data class ChatChanges(
    val chats: List<Chat> = emptyList(),
    val deletedChats: List<ChatId> = emptyList(),
    val joins: List<Join> = emptyList(),
    val leaves: List<Leave> = emptyList(),
    /** Chat-level events (`ChatCreated`, `GroupDeleted`); a member's own event rides with its [Join] or [Leave]. */
    val events: List<EventEnvelope> = emptyList(),
)

interface MessagingStore {
    /**
     * Writes the rows and their outbox rows in logged batches, each member's rows with its own event, then hands
     * every event to the bus. Many members go in chunks, so no batch grows past what the database allows; the
     * chat-level rows and events ride with the first chunk.
     */
    suspend fun commit(changes: ChatChanges)
}
