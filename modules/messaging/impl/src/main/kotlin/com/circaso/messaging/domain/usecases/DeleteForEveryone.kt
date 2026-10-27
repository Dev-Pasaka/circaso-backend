package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessageSenders
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import java.time.Clock
import java.util.UUID

/**
 * M14 `DELETE /chats/{chatId}/messages/{messageId}`: the sender, within 48 hours of sending (the time is in the
 * message id), drops every copy still waiting in an inbox and the group body, and the other members get
 * `message.deleted` so their phones remove what they already have. Anyone else, or after the window, gets
 * `messaging.delete_window_passed`; a chat the caller isn't in answers as if it didn't exist.
 */
class DeleteForEveryone(
    private val chats: ChatRepository,
    private val membership: Membership,
    private val delivery: DeliveryRepository,
    private val transactor: Transactor,
    private val pending: PendingCounts,
    private val senders: MessageSenders,
    private val notifier: DeliveryNotifier,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        chatId: ChatId,
        messageId: UUID,
    ): Resource<Unit> {
        val chat = chats.chat(chatId) ?: return Resource.error(MessagingError.ChatNotFound)
        val members = membership.members(chatId)
        if (caller !in members) return Resource.error(MessagingError.ChatNotFound)
        if (!withinWindow(messageId)) return Resource.error(MessagingError.DeleteWindowPassed)
        val others = members - caller
        if (senders.senderOf(chatId, messageId) != caller) {
            return Resource.error(MessagingError.DeleteWindowPassed)
        }

        transactor.run("messages.delete-for-everyone") {
            delivery.deleteInboxRows(others, messageId)
            if (chat.kind == ChatKind.GROUP) delivery.deleteGroupBody(chatId, messageId)
        }
        if (chat.kind == ChatKind.GROUP) pending.clear(messageId)
        notifier.messageDeleted(others, chatId, messageId)
        return Resource.success(Unit)
    }

    private fun withinWindow(messageId: UUID): Boolean {
        val sentAt = Uuid7.timestampOf(messageId) ?: return false
        return !sentAt.plus(config.deleteWindow).isBefore(clock.instant())
    }
}
