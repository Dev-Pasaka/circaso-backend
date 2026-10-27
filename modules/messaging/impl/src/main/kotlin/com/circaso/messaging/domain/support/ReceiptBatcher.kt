package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.domain.models.Receipt
import com.circaso.messaging.domain.ports.ReceiptBuffer
import com.circaso.shared.UserId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Clock
import java.util.UUID

/**
 * Receipts to a message's sender. A direct chat's go at once. A group's collect in `msg:receipts:{messageId}` and go
 * as one frame every [MessagingConfig.receiptBatch] (decision 2026-10-25), so a message read by 999 members is a
 * handful of frames, not 999. The first receipt of a window claims the batch and this node sends it when the window
 * ends; every receipt collected until then rides along. A batch takes and clears what was collected, so the next
 * batch carries only newer receipts, and a person's read receipt is never replaced by a delivered one.
 * [scope] is messaging's background scope, whose handler logs a batch that fails.
 */
class ReceiptBatcher(
    private val buffer: ReceiptBuffer,
    private val notifier: DeliveryNotifier,
    private val scope: CoroutineScope,
    private val clock: Clock,
    private val config: MessagingConfig,
) {
    suspend fun direct(
        senderId: UserId,
        chatId: ChatId,
        receipts: List<Receipt>,
    ) = notifier.receipts(senderId, chatId, receipts)

    suspend fun group(
        senderId: UserId,
        chatId: ChatId,
        messageId: UUID,
        reader: UserId,
        status: ReceiptStatus,
    ) {
        buffer.add(messageId, reader, status)
        if (!buffer.claimBatch(messageId)) return
        scope.launch {
            delay(config.receiptBatch.toMillis())
            send(senderId, chatId, messageId)
        }
    }

    private suspend fun send(
        senderId: UserId,
        chatId: ChatId,
        messageId: UUID,
    ) {
        val at = clock.instant()
        val receipts = buffer.takeBatch(messageId).map { (userId, status) -> Receipt(messageId, userId, status, at) }
        notifier.receipts(senderId, chatId, receipts)
    }
}
