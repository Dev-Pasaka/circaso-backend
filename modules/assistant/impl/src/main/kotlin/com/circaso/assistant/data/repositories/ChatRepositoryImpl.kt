package com.circaso.assistant.data.repositories

import com.circaso.assistant.data.mappers.readNoteOf
import com.circaso.assistant.data.tables.AshaMessagesTable
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.util.UUID

/**
 * The Asha chat, read through its primary key `(user_id, message_id)` (the whole history) or
 * `asha_messages_conversation_idx` (one conversation): ids are UUIDv7, so a page is a keyset on `message_id DESC`. The text is opened from its sealed form only here, inside the transaction that read it.
 */
class ChatRepositoryImpl(private val database: Database, private val vault: TextVault) : ChatRepository {
    override suspend fun find(userId: UserId, messageId: UUID): ChatMessage? =
        database.tx("chat.find") {
            AshaMessagesTable
                .selectAll()
                .where { (AshaMessagesTable.userId eq userId.value) and (AshaMessagesTable.messageId eq messageId) }
                .singleOrNull()
                ?.toMessage()
        }

    override suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ): List<ChatMessage> =
        database.tx("chat.list") {
            AshaMessagesTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> = AshaMessagesTable.userId eq userId.value
                    if (before != null) condition = condition and (AshaMessagesTable.messageId less before)
                    condition
                }.orderBy(AshaMessagesTable.messageId to SortOrder.DESC)
                .limit(limit)
                .toList()
                .map { it.toMessage() }
        }

    override suspend fun listIn(
        userId: UserId,
        conversationId: UUID,
        before: UUID?,
        limit: Int,
    ): List<ChatMessage> =
        database.tx("chat.list-conversation") {
            AshaMessagesTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> =
                        (AshaMessagesTable.userId eq userId.value) and
                            (AshaMessagesTable.conversationId eq conversationId)
                    if (before != null) condition = condition and (AshaMessagesTable.messageId less before)
                    condition
                }.orderBy(AshaMessagesTable.messageId to SortOrder.DESC)
                .limit(limit)
                .toList()
                .map { it.toMessage() }
        }

    override suspend fun oldestIn(userId: UserId, conversationId: UUID): ChatMessage? =
        database.tx("chat.oldest-in-conversation") {
            AshaMessagesTable
                .selectAll()
                .where {
                    (AshaMessagesTable.userId eq userId.value) and
                        (AshaMessagesTable.conversationId eq conversationId)
                }.orderBy(AshaMessagesTable.messageId to SortOrder.ASC)
                .limit(1)
                .singleOrNull()
                ?.toMessage()
        }

    override suspend fun delete(userId: UserId, messageIds: Collection<UUID>): Int {
        if (messageIds.isEmpty()) return 0
        return database.tx("chat.delete-messages") {
            AshaMessagesTable.deleteWhere { (this.userId eq userId.value) and (messageId inList messageIds) }
        }
    }

    private fun ResultRow.toMessage() =
        ChatMessage(
            UserId(this[AshaMessagesTable.userId]),
            this[AshaMessagesTable.messageId],
            Role.valueOf(this[AshaMessagesTable.role]),
            vault.open(this[AshaMessagesTable.body]),
            this[AshaMessagesTable.replyTo],
            this[AshaMessagesTable.answeredBy],
            this[AshaMessagesTable.askedFrom],
            readNoteOf(this[AshaMessagesTable.readNote]),
            this[AshaMessagesTable.createdAt],
            this[AshaMessagesTable.conversationId],
        )
}
