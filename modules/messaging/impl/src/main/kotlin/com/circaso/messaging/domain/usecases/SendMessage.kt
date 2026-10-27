package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.Fanout
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.SendMessageCommand
import com.circaso.messaging.domain.models.SentMessage
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.repositories.ChatMarkers
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.messaging.domain.support.OrderedIds
import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.users.api.ReachPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * M12 `POST /chats/{chatId}/messages`. A direct copy goes into the recipient's inbox with its ciphertext; a group
 * message is one `GroupMessageRow` and a pointer per member, written in unlogged batches of 100, with
 * `msg:pending:{messageId}` counting the members still to ack (Messaging → Fan-out). The send answers once the rows
 * are written; `message.new` frames and pushes go out after, in the background.
 *
 * The `clientMessageId` is claimed with the message id before anything is written, and the claim is marked complete
 * once every row is (decision 2026-10-27). A retry of a complete send returns its id and writes nothing; a retry that
 * finds the claim incomplete (the first attempt failed part-way, or is still writing) writes the same rows under the
 * same id, which is harmless twice, and completes it. So a message keeps one id, and no send answers before its rows
 * exist. The writes and the completion run under `NonCancellable`: a caller that goes away mid-write doesn't leave
 * half a fan-out behind.
 */
class SendMessage(
    private val chats: ChatRepository,
    private val membership: Membership,
    private val reach: ReachPolicy,
    private val delivery: DeliveryRepository,
    private val markers: ChatMarkers,
    private val pending: PendingCounts,
    private val notifier: DeliveryNotifier,
    private val ids: OrderedIds,
    private val background: CoroutineScope,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SendMessageCommand): Resource<SentMessage> {
        if (tooLarge(command)) return Resource.error(MessagingError.MessageTooLarge)
        val chat = chats.chat(command.chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        val members = membership.members(command.chatId)
        if (command.sender !in members) return Resource.error(MessagingError.ChatNotFound)
        val others = members - command.sender
        val problem = check(chat, command, others)
        if (problem != null) return Resource.error(problem)

        val fresh = ids.next()
        val earlier = delivery.claimClientMessage(command.sender, command.clientMessageId, fresh)
        if (earlier != null && earlier.complete) {
            return Resource.success(SentMessage(earlier.messageId, sentAt(earlier.messageId)))
        }
        val messageId = earlier?.messageId ?: fresh
        val sentAt = sentAt(messageId)
        val fanout = fanout(chat, command, others, messageId, sentAt)
        withContext(NonCancellable) {
            write(command, fanout, members, messageId)
            delivery.completeClientMessage(command.sender, command.clientMessageId, messageId)
        }
        background.launch { notify(command, fanout) }
        return Resource.success(SentMessage(messageId, sentAt))
    }

    private fun tooLarge(command: SendMessageCommand): Boolean {
        val texts = listOfNotNull(command.ciphertext) + command.recipients.map { it.ciphertext }
        return texts.any { decodedSize(it) > config.maxCiphertextBytes }
    }

    /** What the chat's kind asks of the request: who the copies are for, and where the ciphertext goes. */
    private suspend fun check(
        chat: Chat,
        command: SendMessageCommand,
        others: Set<UserId>,
    ): DomainError? {
        val perRecipient = chat.kind == ChatKind.DIRECT || command.kind == MessageKind.SENDER_KEY
        if (!perRecipient) return groupCheck(command)
        if (command.ciphertext != null) return malformed("ciphertext", "goes in recipients for this message")
        if (!recipientsMatch(chat, command, others)) return MessagingError.RecipientsMismatch
        if (chat.kind == ChatKind.DIRECT && !reachableBothWays(command.sender, others.single())) {
            return MessagingError.NotReachable
        }
        return null
    }

    /** Direct: exactly the other person. `SENDER_KEY` in a group: some other members, each once. */
    private fun recipientsMatch(
        chat: Chat,
        command: SendMessageCommand,
        others: Set<UserId>,
    ): Boolean {
        val recipients = command.recipients.map { it.userId }
        val distinct = recipients.toSet()
        if (recipients.isEmpty() || recipients.size != distinct.size) return false
        if (chat.kind == ChatKind.DIRECT) return distinct == others
        return others.containsAll(distinct)
    }

    private fun groupCheck(command: SendMessageCommand) =
        when {
            command.ciphertext == null -> malformed("ciphertext", "is required for a group message")
            command.recipients.isNotEmpty() -> malformed("recipients", "only for SENDER_KEY in a group")
            else -> null
        }

    private suspend fun reachableBothWays(sender: UserId, other: UserId) =
        reach.canReach(sender, other) && reach.canReach(other, sender)

    private fun fanout(
        chat: Chat,
        command: SendMessageCommand,
        others: Set<UserId>,
        messageId: UUID,
        sentAt: Instant,
    ): Fanout {
        fun row(to: UserId, ciphertext: String?) =
            InboxMessage(
                userId = to,
                messageId = messageId,
                chatId = chat.chatId,
                senderId = command.sender,
                kind = command.kind,
                ciphertext = ciphertext,
                groupBody = ciphertext == null,
                mediaIds = command.mediaIds,
                sentAt = sentAt,
            )
        val record = MessageRecord(messageId, chat.chatId, command.sender, command.kind, sentAt)
        val groupText = command.ciphertext
        if (groupText == null) return Fanout(record, command.recipients.map { row(it.userId, it.ciphertext) }, null)
        val body = GroupBody(chat.chatId, messageId, command.sender, groupText, sentAt)
        return Fanout(record, others.map { row(it, null) }, body)
    }

    /**
     * The body before its pointers, and the counter before either, so an ack never finds a pointer without them. The
     * message record rides in the first batch of rows.
     * Only a `MESSAGE` moves the chat list's last message: sender keys and control messages aren't shown.
     */
    private suspend fun write(
        command: SendMessageCommand,
        fanout: Fanout,
        members: Set<UserId>,
        messageId: UUID,
    ) {
        val body = fanout.body
        if (body != null && fanout.rows.isNotEmpty()) {
            pending.start(messageId, fanout.rows.size)
            delivery.writeGroupBody(body)
        }
        delivery.writeMessage(fanout.record, fanout.rows, config.fanOutBatch)
        if (command.kind == MessageKind.MESSAGE) {
            markers.lastMessage(members, command.chatId, messageId, config.fanOutBatch)
        }
    }

    /** Sender keys and control messages never wake anyone, whatever the phone asked. */
    private suspend fun notify(command: SendMessageCommand, fanout: Fanout) {
        val pushable = command.pushable && command.kind == MessageKind.MESSAGE
        val body = fanout.body
        if (body == null) {
            for (row in fanout.rows) notifier.newMessage(listOf(row.userId), row, pushable)
            return
        }
        val shared = fanout.rows.firstOrNull()?.copy(ciphertext = body.ciphertext) ?: return
        notifier.newMessage(fanout.rows.map { it.userId }, shared, pushable)
    }

    private fun sentAt(messageId: UUID): Instant = Uuid7.timestampOf(messageId) ?: clock.instant()

    private fun malformed(field: String, why: String) = SharedError.Malformed(field, why)
}

/** The bytes a base64 text decodes to, without decoding it. */
private fun decodedSize(base64: String): Int {
    val padding = base64.takeLast(2).count { it == '=' }
    return base64.length / 4 * 3 - padding
}
