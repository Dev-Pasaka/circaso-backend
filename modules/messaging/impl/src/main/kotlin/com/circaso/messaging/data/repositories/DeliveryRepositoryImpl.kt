package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.data.tables.ClientMessagesTable
import com.circaso.messaging.data.tables.GroupMessagesTable
import com.circaso.messaging.data.tables.InboxTable
import com.circaso.messaging.data.tables.MessageRecordsTable
import com.circaso.messaging.domain.models.Fanout
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.inSubQuery
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteReturning
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** Delivery over `inbox`, `group_messages`, `message_records` and `client_messages`. */
class DeliveryRepositoryImpl(private val database: Database, private val clock: Clock) : DeliveryRepository {
    /**
     * `INSERT … ON CONFLICT (sender_id, client_message_id) DO UPDATE … WHERE expires_at <= :now`: a live claim is left
     * alone and its message id read back; an expired one the purge hasn't reached yet is taken over.
     */
    override suspend fun claimClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
        expiresAt: Instant,
    ): UUID? =
        database.tx("messages.claim-client-id") {
            val now = clock.instant()
            val claimed =
                ClientMessagesTable.upsert(
                    ClientMessagesTable.senderId,
                    ClientMessagesTable.clientMessageId,
                    where = { ClientMessagesTable.expiresAt lessEq now },
                ) {
                    it[this.senderId] = senderId.value
                    it[this.clientMessageId] = clientMessageId
                    it[this.messageId] = messageId
                    it[this.expiresAt] = expiresAt
                }
            if (claimed.insertedCount > 0) return@tx null
            ClientMessagesTable
                .select(ClientMessagesTable.messageId)
                .where {
                    (ClientMessagesTable.senderId eq senderId.value) and
                        (ClientMessagesTable.clientMessageId eq clientMessageId)
                }.map { it[ClientMessagesTable.messageId] }
                .singleOrNull()
        }

    /**
     * The body, the inbox rows (one multi-row `INSERT`, Messaging → Fan-out) and the record. The record goes last: its
     * insert moves the chat's `chat_heads` row (a trigger, no round trip of its own), and that row stays locked until
     * the commit, so the next send to the same chat waits only for this commit, not for this whole write.
     */
    override suspend fun writeMessage(fanout: Fanout) {
        database.tx("messages.write") {
            fanout.body?.let { body ->
                GroupMessagesTable.insert {
                    it[chatId] = body.chatId.value
                    it[messageId] = body.messageId
                    it[senderId] = body.senderId.value
                    it[ciphertext] = body.ciphertext
                    it[sentAt] = body.sentAt
                }
            }
            if (fanout.rows.isNotEmpty()) {
                InboxTable.batchInsert(fanout.rows, useMultiRowValues = true, shouldReturnGeneratedValues = false) {
                    this[InboxTable.userId] = it.userId.value
                    this[InboxTable.messageId] = it.messageId
                    this[InboxTable.chatId] = it.chatId.value
                    this[InboxTable.senderId] = it.senderId.value
                    this[InboxTable.kind] = it.kind.name
                    this[InboxTable.ciphertext] = it.ciphertext
                    this[InboxTable.mediaIds] = it.mediaIds
                    this[InboxTable.sentAt] = it.sentAt
                }
            }
            val record = fanout.record
            MessageRecordsTable.insert {
                it[messageId] = record.messageId
                it[chatId] = record.chatId.value
                it[senderId] = record.senderId.value
                it[kind] = record.kind.name
                it[sentAt] = record.sentAt
            }
        }
    }

    override suspend fun messageRecord(messageId: UUID): MessageRecord? =
        database.tx("messages.record") {
            MessageRecordsTable
                .selectAll()
                .where { MessageRecordsTable.messageId eq messageId }
                .map {
                    MessageRecord(
                        messageId = it[MessageRecordsTable.messageId],
                        chatId = ChatId(it[MessageRecordsTable.chatId]),
                        senderId = UserId(it[MessageRecordsTable.senderId]),
                        kind = MessageKind.valueOf(it[MessageRecordsTable.kind]),
                        sentAt = it[MessageRecordsTable.sentAt],
                    )
                }.singleOrNull()
        }

    override suspend fun deleteGroupBody(chatId: ChatId, messageId: UUID) {
        database.tx("messages.delete-body") {
            GroupMessagesTable.deleteWhere {
                (GroupMessagesTable.chatId eq chatId.value) and (GroupMessagesTable.messageId eq messageId)
            }
        }
    }

    /** One page with each group pointer's body joined in by `(chat_id, message_id)`. */
    override suspend fun inbox(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): List<InboxMessage> =
        database.tx("inbox.page") {
            InboxTable
                .join(GroupMessagesTable, JoinType.LEFT, additionalConstraint = {
                    InboxTable.ciphertext.isNull() and
                        (GroupMessagesTable.chatId eq InboxTable.chatId) and
                        (GroupMessagesTable.messageId eq InboxTable.messageId)
                })
                .select(InboxTable.columns + GroupMessagesTable.ciphertext)
                .where {
                    val mine: Op<Boolean> = InboxTable.userId eq userId.value
                    if (after == null) mine else mine and (InboxTable.messageId greater after)
                }.orderBy(InboxTable.messageId to SortOrder.ASC)
                .limit(limit)
                .map { it.toInboxMessage(body = it.getOrNull(GroupMessagesTable.ciphertext)) }
                .toList()
        }

    override suspend fun takeInboxRows(userId: UserId, messageIds: Collection<UUID>): List<InboxMessage> {
        if (messageIds.isEmpty()) return emptyList()
        return database.tx("inbox.take") {
            InboxTable
                .deleteReturning {
                    (InboxTable.userId eq userId.value) and (InboxTable.messageId inList messageIds)
                }.map { it.toInboxMessage() }
                .toList()
        }
    }

    override suspend fun takeInboxRowsSentBy(
        userId: UserId,
        sentBy: Instant,
        limit: Int,
    ): List<InboxMessage> =
        database.tx("inbox.take-sent-by") {
            val page =
                InboxTable
                    .select(InboxTable.messageId)
                    .where { (InboxTable.userId eq userId.value) and (InboxTable.sentAt lessEq sentBy) }
                    .orderBy(InboxTable.messageId to SortOrder.ASC)
                    .limit(limit)
            InboxTable
                .deleteReturning { (InboxTable.userId eq userId.value) and (InboxTable.messageId inSubQuery page) }
                .map { it.toInboxMessage() }
                .toList()
                .sortedBy { it.sentAt }
        }

    override suspend fun deleteInboxRows(userIds: Collection<UserId>, messageId: UUID): Int {
        if (userIds.isEmpty()) return 0
        return database.tx("inbox.delete-copies") {
            InboxTable.deleteWhere {
                (InboxTable.messageId eq messageId) and (InboxTable.userId inList userIds.map { it.value })
            }
        }
    }
}

/**
 * A group pointer is a row without ciphertext; [body] is its group ciphertext when it was joined in (null when the
 * body is gone, or not asked for).
 */
private fun ResultRow.toInboxMessage(body: String? = null): InboxMessage {
    val own = this[InboxTable.ciphertext]
    return InboxMessage(
        userId = UserId(this[InboxTable.userId]),
        messageId = this[InboxTable.messageId],
        chatId = ChatId(this[InboxTable.chatId]),
        senderId = UserId(this[InboxTable.senderId]),
        kind = MessageKind.valueOf(this[InboxTable.kind]),
        ciphertext = own ?: body,
        groupBody = own == null,
        mediaIds = this[InboxTable.mediaIds],
        sentAt = this[InboxTable.sentAt],
    )
}
