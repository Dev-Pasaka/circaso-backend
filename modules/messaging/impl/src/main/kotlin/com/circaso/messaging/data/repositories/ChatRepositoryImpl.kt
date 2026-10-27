package com.circaso.messaging.data.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.DirectChatClaimRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.NIL_UUID
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.data.mappers.toModel
import com.circaso.messaging.data.mappers.toRow
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.UserChat
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.UserId
import com.datastax.oss.driver.api.core.cql.Row
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.repository.KandraSuspendRepository
import java.util.UUID

// Column names Kandra derives from the entity properties (camelCase to snake_case).
private val CHAT_ID = KandraColumnRef<UUID>("chat_id")

/** A role is only ever set on a row that exists: `IF EXISTS` keeps a late write from putting a member back. */
private const val UPDATE_ROLE_CQL =
    "UPDATE circaso_messaging.members_by_chat SET role = ? WHERE chat_id = ? AND user_id = ? IF EXISTS"

/**
 * Kandra has no conditional delete, so the claim is written as CQL. `IF EXISTS` makes it a lightweight transaction:
 * of a remove and a leave of the same person racing, only one sees `[applied] = true`.
 */
private const val CLAIM_REMOVAL_CQL =
    "DELETE FROM circaso_messaging.members_by_chat WHERE chat_id = ? AND user_id = ? IF EXISTS"

private const val CHAT_LIST_CQL =
    "SELECT user_id, chat_id, kind, last_message_id, read_up_to, joined_at FROM circaso_messaging.chats_by_user " +
        "WHERE user_id = ?"

/** Chats and membership on Kandra. Writes of several rows go through `MessagingStoreImpl`, with their events. */
class ChatRepositoryImpl(
    private val chats: KandraSuspendRepository<ChatRow>,
    private val claims: KandraSuspendRepository<DirectChatClaimRow>,
    private val members: KandraSuspendRepository<MemberRow>,
    private val userChats: KandraSuspendRepository<UserChatRow>,
) : ChatRepository {
    override suspend fun claimPair(pair: String, chatId: ChatId): Boolean =
        claims.saveIfNotExists(DirectChatClaimRow(pair, chatId.value))

    override suspend fun pairChat(pair: String): ChatId? = claims.findById(pair)?.let { ChatId(it.chatId) }

    override suspend fun chat(chatId: ChatId): Chat? = chats.findById(chatId.value)?.toModel()

    /** Kandra's versioned update: `IF version = ?`, which answers a lost race with an exception. */
    override suspend fun updateChat(old: Chat, new: Chat): Chat? =
        try {
            chats.updateAndGet(old.toRow(), new.toRow()).toModel()
        } catch (e: KandraOptimisticLockException) {
            null
        }

    override suspend fun members(chatId: ChatId): List<Member> =
        members.findAll { CHAT_ID eq chatId.value }.map { it.toModel() }

    override suspend fun member(chatId: ChatId, userId: UserId): Member? =
        members.findById(chatId.value, userId.value)?.toModel()

    override suspend fun claimRemoval(chatId: ChatId, userId: UserId): Boolean {
        val query =
            KandraRawQuery
                .cql(CLAIM_REMOVAL_CQL)
                .bind(chatId.value, userId.value)
                .build()
        return members.rawQuery(query).firstOrNull()?.getBoolean("[applied]") == true
    }

    override suspend fun updateRole(member: Member): Boolean {
        val query =
            KandraRawQuery
                .cql(UPDATE_ROLE_CQL)
                .bind(
                    member.role.name,
                    member.chatId.value,
                    member.userId.value,
                ).build()
        return members.rawQuery(query).firstOrNull()?.getBoolean("[applied]") == true
    }

    override suspend fun chatsOf(userId: UserId): List<UserChat> {
        val query =
            KandraRawQuery
                .cql(CHAT_LIST_CQL)
                .bind(userId.value)
                .build()
        return userChats.rawQuery(query).mapNotNull { it.toUserChat() }
    }

    override suspend fun userChat(userId: UserId, chatId: ChatId): UserChat? {
        val query =
            KandraRawQuery
                .cql("$CHAT_LIST_CQL AND chat_id = ?")
                .bind(userId.value, chatId.value)
                .build()
        return userChats.rawQuery(query).firstOrNull()?.toUserChat()
    }

    /**
     * Read by hand rather than through Kandra, which refuses a row missing a non-null column: a message marker
     * written just after the person left leaves a line with only the marker (`ChatMarkersImpl`), and that line is
     * not a chat they are in.
     */
    private fun Row.toUserChat(): UserChat? {
        if (isNull("kind") || isNull("joined_at")) return null
        val row =
            UserChatRow(
                userId = getUuid("user_id")!!,
                chatId = getUuid("chat_id")!!,
                kind = getString("kind")!!,
                lastMessageId = getUuid("last_message_id") ?: NIL_UUID,
                readUpTo = getUuid("read_up_to") ?: NIL_UUID,
                joinedAt = getInstant("joined_at")!!,
            )
        return row.toModel()
    }
}
