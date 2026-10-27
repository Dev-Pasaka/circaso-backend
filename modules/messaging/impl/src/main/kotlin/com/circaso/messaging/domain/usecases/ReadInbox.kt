package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.InboxPage
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/**
 * M13 `GET /inbox`: what is waiting for the user, oldest first, a page at a time; for catching up after the socket
 * was closed and for push wakes. A group pointer gets its body's ciphertext; one whose body is gone (deleted for
 * everyone a moment ago, or past its 30 days) is left out and deleted, since it can never be delivered.
 */
class ReadInbox(private val delivery: DeliveryRepository, private val config: MessagingConfig) {
    suspend operator fun invoke(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): Resource<InboxPage> {
        val size = limit.coerceIn(1, config.inboxPageMax)
        val rows = delivery.inbox(userId, after, size + 1)
        val page = rows.take(size)
        val next = if (rows.size > size) page.last().messageId else null
        return Resource.success(InboxPage(page.mapNotNull { withCiphertext(it) }, next))
    }

    private suspend fun withCiphertext(row: InboxMessage): InboxMessage? {
        if (!row.groupBody) return row
        val body = delivery.groupBody(row.chatId, row.messageId)
        if (body == null) {
            delivery.deleteInboxRows(listOf(row.userId), row.messageId, batchSize = 1)
            return null
        }
        return row.copy(ciphertext = body.ciphertext)
    }
}
