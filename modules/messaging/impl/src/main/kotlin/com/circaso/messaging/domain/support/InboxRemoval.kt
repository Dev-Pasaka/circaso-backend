package com.circaso.messaging.domain.support

import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.repositories.DeliveryRepository

/**
 * Takes one message out of one inbox, on an ack or when a replaced phone's messages are dropped (Messaging → Deleting
 * what's been delivered). Every row is deleted with a lightweight transaction, so of two removals of the same row
 * (two acks racing) only one counts: one receipt, and for a group pointer one count-down of
 * `msg:pending:{messageId}`, the last of which deletes the shared body. If Redis lost the counter the body stays until
 * its 30-day TTL.
 */
class InboxRemoval(private val delivery: DeliveryRepository, private val pending: PendingCounts) {
    /** True when this call removed the row; false when it was already gone. */
    suspend fun remove(row: InboxMessage): Boolean {
        if (!delivery.claimInboxRow(row.userId, row.messageId)) return false
        if (!row.groupBody) return true
        val left = pending.countDown(row.messageId) ?: return true
        if (left <= 0) {
            delivery.deleteGroupBody(row.chatId, row.messageId)
            pending.clear(row.messageId)
        }
        return true
    }
}
