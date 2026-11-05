package com.circaso.messaging.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatLine
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.UserChat
import com.circaso.shared.DeviceId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/** The key directory: `key_bundles` and `one_time_prekeys`. */
interface KeyRepository {
    suspend fun bundle(userId: UserId): KeyBundle?

    /**
     * The bundle, its row locked until the transaction ends (`FOR UPDATE`), so a top-up and a `PUT /keys` of the same
     * account run one after the other. Inside a `Transactor` block only.
     */
    suspend fun lockBundle(userId: UserId): KeyBundle?

    /** The ids of [deviceId]'s one-time prekeys still waiting to be used. At most 100, so reading them is cheap. */
    suspend fun oneTimeKeyIds(userId: UserId, deviceId: DeviceId): Set<Int>

    /**
     * `PUT /keys`: writes [bundle] in place of the stored one and replaces every one-time prekey of the account with
     * [oneTimePreKeys], in one transaction.
     */
    suspend fun publish(
        bundle: KeyBundle,
        oneTimePreKeys: List<OneTimePreKey>,
        at: Instant,
    )

    /** Inserts a top-up's keys for [deviceId]; the caller checked, under the bundle's lock, that every id is new. */
    suspend fun addOneTimeKeys(
        userId: UserId,
        deviceId: DeviceId,
        keys: List<OneTimePreKey>,
        at: Instant,
    )

    /**
     * Takes [deviceId]'s lowest one-time prekey and deletes it in one statement (`DELETE … RETURNING` over a
     * `FOR UPDATE SKIP LOCKED` pick), so two fetches never get the same key. Null when none are left.
     */
    suspend fun claimOneTimeKey(userId: UserId, deviceId: DeviceId): OneTimePreKey?

    /**
     * `identity.DeviceReplaced`: deletes [userId]'s bundle only if [deviceId] published it, and [deviceId]'s one-time
     * prekeys uploaded before [replacedAt], so a bundle or keys the new phone sent since are kept. True when the
     * bundle was deleted.
     */
    suspend fun removeBundle(
        userId: UserId,
        deviceId: DeviceId,
        replacedAt: Instant,
    ): Boolean
}

/**
 * Chats and membership: `chats` and `members`. Every membership change locks the chat's row first ([lockChat], or
 * [reserveSeats] which locks it too) and only then touches `members`, so changes to one group run one after another
 * and decide on a settled member list (decision 2026-10-27, PG0 review).
 */
interface ChatRepository {
    suspend fun chat(chatId: ChatId): Chat?

    /** The chat, its row locked until the transaction ends (`FOR UPDATE`). Inside a `Transactor` block only. */
    suspend fun lockChat(chatId: ChatId): Chat?

    /** Inserts [chat] unless its id (or, for a direct chat, its pair) is taken; true when this call inserted it. */
    suspend fun insertChat(chat: Chat): Boolean

    /**
     * The direct chat between two people, whichever of them is named first; with [viaSpaceId], their private chat
     * via that space instead (Spaces phase 3).
     */
    suspend fun directChat(
        a: UserId,
        b: UserId,
        viaSpaceId: UUID? = null,
    ): Chat?

    /**
     * Writes [new] only if the stored chat is still at [old]'s version, moving the version on by one; the chat as
     * written, or null when someone changed it in between.
     */
    suspend fun updateChat(old: Chat, new: Chat): Chat?

    /**
     * `UPDATE chats SET member_count = member_count + :seats, version = version + 1 WHERE chat_id = ? AND
     * member_count + :seats <= :max RETURNING …`: the chat with its places taken, or null when it would pass [max]
     * (or is gone).
     */
    suspend fun reserveSeats(
        chatId: ChatId,
        seats: Int,
        max: Int,
    ): Chat?

    /** Deletes the chat; its member rows go with it (`ON DELETE CASCADE`). */
    suspend fun deleteChat(chatId: ChatId)

    /** Every member: at most 1,000. */
    suspend fun members(chatId: ChatId): List<Member>

    /** The members of each of [chatIds], in one read. */
    suspend fun members(chatIds: Collection<ChatId>): Map<ChatId, List<Member>>

    suspend fun member(chatId: ChatId, userId: UserId): Member?

    suspend fun addMembers(members: List<Member>)

    /** Takes [userId]'s member row with `DELETE … RETURNING`: the row for the one caller that deleted it, else null. */
    suspend fun removeMember(chatId: ChatId, userId: UserId): Member?

    /** Sets [member]'s role only if their row still exists, so a role write never puts back someone who left. */
    suspend fun setRole(member: Member): Boolean

    /** The user's chat list (M6) through `members_user_idx`, each chat with its newest `MESSAGE` id. */
    suspend fun chatList(userId: UserId): List<ChatLine>

    /** The ids of every chat the user is in. */
    suspend fun chatIdsOf(userId: UserId): List<ChatId>

    /** The user's place in one chat, or null when they aren't in it. */
    suspend fun userChat(userId: UserId, chatId: ChatId): UserChat?

    /** The newest `MESSAGE` id the server still holds a record of (30 days), or null. */
    suspend fun lastMessageId(chatId: ChatId): UUID?

    /**
     * Moves [userId]'s read marker in [chatId] to [upTo] only forward (`WHERE read_up_to IS NULL OR read_up_to <
     * :upTo`); true when it moved.
     */
    suspend fun moveReadMarker(
        userId: UserId,
        chatId: ChatId,
        upTo: UUID,
    ): Boolean

    /** Whether [a] and [b] are both in at least one group. */
    suspend fun shareGroup(a: UserId, b: UserId): Boolean
}

/** Messaging's outbox: rows written inside the operation's transaction, published by the caller after the commit. */
interface MessagingOutbox {
    suspend fun write(events: List<EventEnvelope>)
}
