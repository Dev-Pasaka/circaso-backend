package com.circaso.messaging.domain.ports

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessagingFrame
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.shared.UserId
import java.util.UUID

/**
 * Frames that are not replayed (`message.new`, `typing`): one envelope to many users through `Realtime.deliverMany`,
 * one publish however many there are, and not kept. They carry `seq` 0 and take nothing from `msg:seq`, so the
 * replayed frames' `seq` stays gapless. Failures are logged and dropped.
 */
interface LiveFrames {
    suspend fun send(userIds: Collection<UserId>, frame: MessagingFrame)
}

/** `msg:pending:{messageId}`: how many members have yet to ack a group message, so its body goes with the last. */
interface PendingCounts {
    suspend fun start(messageId: UUID, count: Int)

    /** One fewer; the count left, or null when the counter is gone (Redis lost it): the 30-day TTL then cleans up. */
    suspend fun countDown(messageId: UUID): Long?

    suspend fun clear(messageId: UUID)
}

/**
 * `msg:receipts:{messageId}`: a group message's receipts collected for the batch to its sender (decision
 * 2026-10-25). A person's furthest status wins: a delivered receipt never replaces a read one (decision 2026-10-27).
 */
interface ReceiptBuffer {
    suspend fun add(
        messageId: UUID,
        userId: UserId,
        status: ReceiptStatus,
    )

    /** True for the one caller that should send this message's next batch; false while a batch is already due. */
    suspend fun claimBatch(messageId: UUID): Boolean

    /**
     * Ends the batch window, then takes what was collected and clears it in one step: a receipt that arrives after
     * the window ended claims the next batch, so none is sent twice or left behind.
     */
    suspend fun takeBatch(messageId: UUID): Map<UserId, ReceiptStatus>
}

/** `msg:typing:{chatId}:{userId}`: drops a repeated "typing" frame for a few seconds. */
interface TypingMarks {
    /** True when [userId] wasn't already marked typing in [chatId]; marks them. */
    suspend fun start(chatId: ChatId, userId: UserId): Boolean

    suspend fun stop(chatId: ChatId, userId: UserId)
}
