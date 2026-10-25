package com.circaso.messaging.data.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.entities.ChatRow
import com.circaso.messaging.data.entities.DirectChatClaimRow
import com.circaso.messaging.data.entities.MemberRow
import com.circaso.messaging.data.entities.UserChatRow
import com.circaso.messaging.data.mappers.toModel
import com.circaso.messaging.data.mappers.toRow
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.UserChat
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.UserId
import io.kandra.core.exception.KandraOptimisticLockException
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.repository.KandraSuspendRepository
import java.util.UUID

// Column names Kandra derives from the entity properties (camelCase to snake_case).
private val CHAT_ID = KandraColumnRef<UUID>("chat_id")
private val USER_ID = KandraColumnRef<UUID>("user_id")

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

    override suspend fun chatsOf(userId: UserId): List<UserChat> =
        userChats.findAll { USER_ID eq userId.value }.map { it.toModel() }

    override suspend fun userChat(userId: UserId, chatId: ChatId): UserChat? =
        userChats.findById(userId.value, chatId.value)?.toModel()
}
