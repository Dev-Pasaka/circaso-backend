package com.circaso.messaging.domain.support

import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.repositories.DeliveryRepository

/**
 * What follows taking rows out of an inbox, on an ack or when a replaced phone's messages are dropped (Messaging →
 * Deleting what's been delivered). The rows were taken with `DELETE … RETURNING`, so each one reaches here once
 * however often it was acked; each group pointer counts `msg:pending:{messageId}` down once, and the count that
 * reaches zero deletes the shared body. If Redis lost the counter, the body stays until its day's partition is
 * dropped after 30 days.
 */
class InboxRemoval(private val delivery: DeliveryRepository, private val pending: PendingCounts) {
    suspend fun settle(removed: List<InboxMessage>) {
        for (row in removed.filter { it.groupBody }) {
            val left = pending.countDown(row.messageId) ?: continue
            if (left <= 0) {
                delivery.deleteGroupBody(row.chatId, row.messageId)
                pending.clear(row.messageId)
            }
        }
    }
}
