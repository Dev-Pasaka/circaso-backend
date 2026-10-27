package com.circaso.messaging.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.ClientClaim
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.shared.UserId
import java.util.UUID

/** Delivery: `inbox_by_user`, `group_messages`, `messages_by_id` and `sent_by_client_id`. */
interface DeliveryRepository {
    /**
     * Claims [clientMessageId] for [messageId] with a lightweight transaction; null when this call claimed it,
     * otherwise the earlier send's claim: its message id, and whether it finished writing.
     */
    suspend fun claimClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
    ): ClientClaim?

    /** Marks the claim complete: every row of [messageId] is written. */
    suspend fun completeClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
    )

    /**
     * Writes a send's [record] and its inbox [rows] in unlogged batches of [batchSize] statements, the record in the
     * first: rows of many partitions, nothing to keep atomic.
     */
    suspend fun writeMessage(
        record: MessageRecord,
        rows: List<InboxMessage>,
        batchSize: Int,
    )

    /** Who sent [messageId], for 30 days after sending; null after that, or for an id never sent. */
    suspend fun messageRecord(messageId: UUID): MessageRecord?

    suspend fun writeGroupBody(body: GroupBody)

    suspend fun groupBody(chatId: ChatId, messageId: UUID): GroupBody?

    suspend fun deleteGroupBody(chatId: ChatId, messageId: UUID)

    suspend fun inboxRow(userId: UserId, messageId: UUID): InboxMessage?

    /** The oldest [limit] rows after [after] (all when null), oldest first. */
    suspend fun inbox(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): List<InboxMessage>

    /**
     * Deletes one inbox row with a lightweight transaction (`IF EXISTS`): true only for the caller whose delete
     * applied, so a group body is counted down once per member however often the phone acks.
     */
    suspend fun claimInboxRow(userId: UserId, messageId: UUID): Boolean

    /** Deletes [messageId]'s row from each of [userIds]' inboxes, in unlogged batches of [batchSize]. */
    suspend fun deleteInboxRows(
        userIds: Collection<UserId>,
        messageId: UUID,
        batchSize: Int,
    )
}

/** M22 settings: `settings`. A user who never changed them has no row and gets the defaults. */
interface SettingsRepository {
    suspend fun settings(userId: UserId): MessagingSettings

    suspend fun save(userId: UserId, settings: MessagingSettings)
}

/** The chat-list markers messages move: `chats_by_user.last_message_id` and `read_up_to`. */
interface ChatMarkers {
    /** Sets the last message on each of [userIds]' lines for [chatId], in unlogged batches of [batchSize]. */
    suspend fun lastMessage(
        userIds: Collection<UserId>,
        chatId: ChatId,
        messageId: UUID,
        batchSize: Int,
    )

    suspend fun readUpTo(
        userId: UserId,
        chatId: ChatId,
        messageId: UUID,
    )
}
