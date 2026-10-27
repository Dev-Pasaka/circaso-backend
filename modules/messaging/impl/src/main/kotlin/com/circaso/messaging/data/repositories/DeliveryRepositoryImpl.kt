package com.circaso.messaging.data.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.entities.ClientMessageRow
import com.circaso.messaging.data.entities.GroupMessageRow
import com.circaso.messaging.data.entities.InboxRow
import com.circaso.messaging.data.entities.MessageRecordRow
import com.circaso.messaging.data.mappers.toModel
import com.circaso.messaging.data.mappers.toRow
import com.circaso.messaging.data.repositories.UnloggedBatches.Write
import com.circaso.messaging.domain.models.ClientClaim
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.shared.UserId
import com.datastax.oss.driver.api.core.cql.Row
import com.datastax.oss.driver.api.core.servererrors.WriteTimeoutException
import io.kandra.core.exception.KandraQueryException
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.dsl.KandraRawQuery
import io.kandra.runtime.repository.KandraSuspendRepository
import java.util.UUID

// Column names Kandra derives from the entity properties (camelCase to snake_case).
private val USER_ID = KandraColumnRef<UUID>("user_id")
private val MESSAGE_ID = KandraColumnRef<UUID>("message_id")

/** 30 days, as the table's default; said again because a local table may have been made from the entity. */
private const val INSERT_INBOX_CQL =
    "INSERT INTO circaso_messaging.inbox_by_user (user_id, message_id, chat_id, sender_id, kind, ciphertext, " +
        "group_body, media_ids, sent_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) USING TTL 2592000"

private const val INSERT_RECORD_CQL =
    "INSERT INTO circaso_messaging.messages_by_id (message_id, chat_id, sender_id, kind, sent_at) VALUES " +
        "(?, ?, ?, ?, ?) USING TTL 2592000"

private const val DELETE_INBOX_CQL = "DELETE FROM circaso_messaging.inbox_by_user WHERE user_id = ? AND message_id = ?"

/** Kandra has no conditional delete: `IF EXISTS` lets only one of two acks of the same row count it. */
private const val CLAIM_INBOX_CQL = "$DELETE_INBOX_CQL IF EXISTS"

private const val DELETE_BODY_CQL = "DELETE FROM circaso_messaging.group_messages WHERE chat_id = ? AND message_id = ?"

/** Delivery on Kandra; the fan-out writes and deletes go through [UnloggedBatches]. */
class DeliveryRepositoryImpl(
    private val batches: UnloggedBatches,
    private val inbox: KandraSuspendRepository<InboxRow>,
    private val bodies: KandraSuspendRepository<GroupMessageRow>,
    private val records: KandraSuspendRepository<MessageRecordRow>,
    private val clientIds: KandraSuspendRepository<ClientMessageRow>,
    /** Runs a conditional delete; tests replace it to time out. */
    private val conditional: suspend (KandraRawQuery) -> List<Row> = { inbox.rawQuery(it) },
) : DeliveryRepository {
    override suspend fun claimClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
    ): ClientClaim? {
        if (clientIds.saveIfNotExists(ClientMessageRow(senderId.value, clientMessageId, messageId))) return null
        val earlier = clientIds.findById(senderId.value, clientMessageId) ?: return ClientClaim(messageId, false)
        return ClientClaim(earlier.messageId, earlier.complete)
    }

    /** A full-row save, so the row keeps its 24-hour expiry. */
    override suspend fun completeClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
    ) = clientIds.save(ClientMessageRow(senderId.value, clientMessageId, messageId, complete = true))

    override suspend fun writeMessage(
        record: MessageRecord,
        rows: List<InboxMessage>,
        batchSize: Int,
    ) {
        val writes = listOf(record.toWrite()) + rows.map { it.toWrite() }
        batches.runAll(writes, batchSize)
    }

    override suspend fun messageRecord(messageId: UUID): MessageRecord? = records.findById(messageId)?.toModel()

    private fun MessageRecord.toWrite() =
        toRow().let {
            Write(
                INSERT_RECORD_CQL,
                listOf(it.messageId, it.chatId, it.senderId, it.kind, it.sentAt),
            )
        }

    private fun InboxMessage.toWrite() =
        toRow().let {
            Write(
                INSERT_INBOX_CQL,
                listOf(
                    it.userId,
                    it.messageId,
                    it.chatId,
                    it.senderId,
                    it.kind,
                    it.ciphertext,
                    it.groupBody,
                    it.mediaIds,
                    it.sentAt,
                ),
            )
        }

    override suspend fun writeGroupBody(body: GroupBody) = bodies.save(body.toRow())

    override suspend fun groupBody(chatId: ChatId, messageId: UUID): GroupBody? =
        bodies.findById(chatId.value, messageId)?.toModel()

    override suspend fun deleteGroupBody(chatId: ChatId, messageId: UUID) =
        batches.run(DELETE_BODY_CQL, listOf(listOf(chatId.value, messageId)), batchSize = 1)

    override suspend fun inboxRow(userId: UserId, messageId: UUID): InboxMessage? =
        inbox.findById(userId.value, messageId)?.toModel()

    override suspend fun inbox(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): List<InboxMessage> =
        inbox
            .findAll(limit) {
                USER_ID eq userId.value
                if (after != null) MESSAGE_ID gt after
            }.map { it.toModel() }

    /**
     * A lightweight transaction that times out may or may not have applied; it counts as not claimed (decision
     * 2026-10-27), so at worst a row is deleted without its receipt or count-down, never counted twice.
     */
    override suspend fun claimInboxRow(userId: UserId, messageId: UUID): Boolean {
        val query =
            KandraRawQuery
                .cql(CLAIM_INBOX_CQL)
                .bind(userId.value, messageId)
                .build()
        return try {
            conditional(query).firstOrNull()?.getBoolean("[applied]") == true
        } catch (e: WriteTimeoutException) {
            false
        } catch (e: KandraQueryException) {
            if (e.cause !is WriteTimeoutException) throw e
            false
        }
    }

    override suspend fun deleteInboxRows(
        userIds: Collection<UserId>,
        messageId: UUID,
        batchSize: Int,
    ) = batches.run(DELETE_INBOX_CQL, userIds.map { listOf(it.value, messageId) }, batchSize)
}
