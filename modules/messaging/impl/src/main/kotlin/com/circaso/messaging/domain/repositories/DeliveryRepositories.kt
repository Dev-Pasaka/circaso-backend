package com.circaso.messaging.domain.repositories

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Fanout
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/** Delivery: `inbox`, `group_messages`, `message_records` and `client_messages`. */
interface DeliveryRepository {
    /**
     * Claims [clientMessageId] for [messageId] until [expiresAt] (`insertIgnore`; an expired claim is taken over).
     * Null when this call claimed it; otherwise the message id the earlier send was given. Inside the send's
     * transaction, so a send that fails leaves no claim behind.
     */
    suspend fun claimClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
        expiresAt: Instant,
    ): UUID?

    /**
     * Writes one send: its record, its group body when it has one, and its inbox rows in one multi-row insert
     * (Messaging → Fan-out).
     */
    suspend fun writeMessage(fanout: Fanout)

    /** Who sent [messageId], for 30 days after sending; null after that, or for an id never sent. */
    suspend fun messageRecord(messageId: UUID): MessageRecord?

    suspend fun deleteGroupBody(chatId: ChatId, messageId: UUID)

    /**
     * The oldest [limit] rows after [after] (all when null), oldest first, each group pointer with its body's
     * ciphertext filled in. A pointer whose body is gone comes back with no ciphertext.
     */
    suspend fun inbox(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): List<InboxMessage>

    /**
     * The ack claim: `DELETE FROM inbox WHERE user_id = ? AND message_id IN (…) RETURNING …`. The rows this call
     * deleted; a row another ack took first is not among them, so each row is counted once however often it is acked.
     */
    suspend fun takeInboxRows(userId: UserId, messageIds: Collection<UUID>): List<InboxMessage>

    /** Takes up to [limit] of [userId]'s rows sent at or before [sentBy], oldest first (a replaced phone's). */
    suspend fun takeInboxRowsSentBy(
        userId: UserId,
        sentBy: Instant,
        limit: Int,
    ): List<InboxMessage>

    /** Deletes [messageId]'s row from each of [userIds]' inboxes; how many went. */
    suspend fun deleteInboxRows(userIds: Collection<UserId>, messageId: UUID): Int
}

/** M22 settings: `settings`. A user who never changed them has no row and gets the defaults. */
interface SettingsRepository {
    suspend fun settings(userId: UserId): MessagingSettings

    suspend fun save(userId: UserId, settings: MessagingSettings)
}
