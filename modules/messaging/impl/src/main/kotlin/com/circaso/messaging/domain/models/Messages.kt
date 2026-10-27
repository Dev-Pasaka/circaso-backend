package com.circaso.messaging.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

/** One recipient's own ciphertext: a direct message, or a `SENDER_KEY` message to some members of a group. */
data class RecipientCopy(val userId: UserId, val ciphertext: String)

/**
 * M12. [ciphertext] is the one group ciphertext (group `MESSAGE` and `CONTROL`); [recipients] are the per-recipient
 * copies (direct, and `SENDER_KEY`). [clientMessageId] is made on the phone, so a retry returns the first result.
 */
data class SendMessageCommand(
    val sender: UserId,
    val chatId: ChatId,
    val clientMessageId: UUID,
    val kind: MessageKind,
    val ciphertext: String?,
    val recipients: List<RecipientCopy>,
    val mediaIds: List<UUID>,
    val pushable: Boolean,
)

data class SentMessage(val messageId: UUID, val sentAt: Instant)

/**
 * A message waiting in one recipient's inbox. A group message's rows are pointers ([groupBody] true, no
 * [ciphertext]): its one ciphertext is a [GroupBody]. [mediaIds] are references to chat media (M15).
 */
data class InboxMessage(
    val userId: UserId,
    val messageId: UUID,
    val chatId: ChatId,
    val senderId: UserId,
    val kind: MessageKind,
    val ciphertext: String?,
    val groupBody: Boolean,
    val mediaIds: List<UUID>,
    val sentAt: Instant,
)

/** A group message's one ciphertext, shared by every member's pointer and deleted when the last member acks. */
data class GroupBody(
    val chatId: ChatId,
    val messageId: UUID,
    val senderId: UserId,
    val ciphertext: String,
    val sentAt: Instant,
)

/** Who sent a message, kept 30 days after sending whatever has been delivered (`message_records`). */
data class MessageRecord(
    val messageId: UUID,
    val chatId: ChatId,
    val senderId: UserId,
    val kind: MessageKind,
    val sentAt: Instant,
)

/**
 * What one send writes: its [record], the inbox rows (copies or pointers) and, for a group message, its one shared
 * body.
 */
data class Fanout(val record: MessageRecord, val rows: List<InboxMessage>, val body: GroupBody?)

/** M13: a page of the inbox, oldest first, with its ciphertext filled in; [next] is the `after` of the next page. */
data class InboxPage(val messages: List<InboxMessage>, val next: UUID?)

data class Receipt(
    val messageId: UUID,
    val userId: UserId,
    val status: ReceiptStatus,
    val at: Instant,
)

/** M22: both on until the user turns them off. */
data class MessagingSettings(val readReceipts: Boolean = true, val typing: Boolean = true)
