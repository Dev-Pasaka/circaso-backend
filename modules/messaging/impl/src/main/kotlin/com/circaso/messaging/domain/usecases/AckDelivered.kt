package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.Receipt
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.support.InboxRemoval
import com.circaso.messaging.domain.support.ReceiptBatcher
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.util.UUID

/**
 * The phone has these messages: `messaging.ack` over the socket, or M13 `POST /inbox/ack` after a push wake. Each
 * inbox row is deleted (a group's body with the last member's ack) and the sender gets `receipt delivered`: at once
 * in a direct chat, batched in a group. Ids that aren't in the inbox (already acked, or never there) are skipped.
 * Only a `MESSAGE` gets a receipt; sender keys and control messages aren't shown, so nobody waits for one.
 */
class AckDelivered(
    private val delivery: DeliveryRepository,
    private val removal: InboxRemoval,
    private val receipts: ReceiptBatcher,
    private val clock: Clock,
) {
    /** The number of messages this call took out of the inbox. */
    suspend operator fun invoke(userId: UserId, messageIds: Collection<UUID>): Resource<Int> {
        val removed = messageIds.distinct().mapNotNull { take(userId, it) }
        tell(userId, removed.filter { it.kind == MessageKind.MESSAGE })
        return Resource.success(removed.size)
    }

    private suspend fun take(userId: UserId, messageId: UUID): InboxMessage? {
        val row = delivery.inboxRow(userId, messageId) ?: return null
        return row.takeIf { removal.remove(it) }
    }

    /** One frame per sender and direct chat; group receipts join their message's batch. */
    private suspend fun tell(userId: UserId, rows: List<InboxMessage>) {
        val at = clock.instant()
        val (group, direct) = rows.partition { it.groupBody }
        for (row in group) {
            receipts.group(row.senderId, row.chatId, row.messageId, userId, ReceiptStatus.DELIVERED)
        }
        for ((key, batch) in direct.groupBy { it.senderId to it.chatId }) {
            val delivered = batch.map { Receipt(it.messageId, userId, ReceiptStatus.DELIVERED, at) }
            receipts.direct(key.first, key.second, delivered)
        }
    }
}
