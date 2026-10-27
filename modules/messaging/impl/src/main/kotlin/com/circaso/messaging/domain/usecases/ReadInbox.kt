package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.InboxPage
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/**
 * M13 `GET /inbox`: what is waiting for the user, oldest first, a page at a time; for catching up after the socket
 * was closed and for push wakes. A group pointer comes with its body's ciphertext, read in the same query; one whose
 * body is gone (deleted for everyone a moment ago) is left out and deleted, since it can never be delivered.
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
        val (orphans, deliverable) = page.partition { it.groupBody && it.ciphertext == null }
        for (row in orphans) delivery.deleteInboxRows(listOf(userId), row.messageId)
        return Resource.success(InboxPage(deliverable, next))
    }
}
