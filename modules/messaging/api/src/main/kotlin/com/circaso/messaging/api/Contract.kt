package com.circaso.messaging.api

import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/** Identifies one chat, direct or group. Ids come from `OrderedIds`, so chats and messages list in order. */
@Serializable(with = ChatId.Serializer::class)
@JvmInline
value class ChatId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ChatId = ChatId(Uuid7.generate())

        fun parse(raw: String): ChatId? = Uuid7.parse(raw)?.let(::ChatId)
    }

    object Serializer : UuidIdSerializer<ChatId>("ChatId", ::ChatId, ChatId::value)
}

/**
 * Who is in a chat, for the modules that act inside one: wallet (pay-in-chat) and calls (group calls). A chat the
 * user isn't in looks the same as one that doesn't exist.
 */
interface ChatDirectory {
    suspend fun isMember(chatId: ChatId, userId: UserId): Boolean

    suspend fun members(chatId: ChatId): Set<UserId>

    /** The owner and admins of a group, for the modules whose actions need an admin (chama approvals). */
    suspend fun admins(chatId: ChatId): Set<UserId>
}
