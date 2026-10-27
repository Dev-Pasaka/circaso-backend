package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessagingFrame
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.domain.models.Fanout
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.ports.LiveFrames
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.ports.ReceiptBuffer
import com.circaso.messaging.domain.ports.TypingMarks
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.shared.UserId
import java.time.Instant
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Inboxes, group bodies, client-id claims and settings in memory, with the tables' rules: a claim and a take are
 * atomic, and a send writes its record, body and rows together. [records] is shared with [MemoryChats], whose
 * `lastMessageId` reads it. [writes] counts the sends written; [failWrites] makes the next sends throw before writing
 * anything, as a failed transaction leaves nothing.
 */
class MemoryDelivery(val records: ConcurrentHashMap<UUID, MessageRecord> = ConcurrentHashMap()) :
    DeliveryRepository,
    SettingsRepository {
    val inboxes = ConcurrentHashMap<UserId, TreeMap<String, InboxMessage>>()
    val bodies = ConcurrentHashMap<Pair<ChatId, UUID>, GroupBody>()
    val clientIds = ConcurrentHashMap<Pair<UserId, UUID>, UUID>()
    val stored = ConcurrentHashMap<UserId, MessagingSettings>()
    var writes = 0
    var failWrites = 0

    private fun of(userId: UserId) = inboxes.getOrPut(userId) { TreeMap() }

    fun inboxOf(userId: UserId): List<InboxMessage> = synchronized(this) { of(userId).values.toList() }

    override suspend fun claimClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
        expiresAt: Instant,
    ): UUID? = clientIds.putIfAbsent(senderId to clientMessageId, messageId)

    override suspend fun writeMessage(fanout: Fanout) {
        if (failWrites > 0) {
            failWrites--
            error("the send failed")
        }
        synchronized(this) {
            writes++
            records[fanout.record.messageId] = fanout.record
            fanout.body?.let { bodies[it.chatId to it.messageId] = it }
            for (row in fanout.rows) of(row.userId)[row.messageId.toString()] = row
        }
    }

    override suspend fun messageRecord(messageId: UUID) = records[messageId]

    override suspend fun deleteGroupBody(chatId: ChatId, messageId: UUID) {
        bodies.remove(chatId to messageId)
    }

    override suspend fun inbox(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): List<InboxMessage> =
        synchronized(this) {
            val rows = if (after == null) of(userId) else of(userId).tailMap(after.toString(), false)
            rows.values.take(limit).map { row ->
                if (!row.groupBody) row else row.copy(ciphertext = bodies[row.chatId to row.messageId]?.ciphertext)
            }
        }

    override suspend fun takeInboxRows(userId: UserId, messageIds: Collection<UUID>): List<InboxMessage> =
        synchronized(this) { messageIds.mapNotNull { of(userId).remove(it.toString()) } }

    override suspend fun takeInboxRowsSentBy(
        userId: UserId,
        sentBy: Instant,
        limit: Int,
    ): List<InboxMessage> =
        synchronized(this) {
            val due = of(userId).values.filter { !it.sentAt.isAfter(sentBy) }.take(limit)
            due.forEach { of(userId).remove(it.messageId.toString()) }
            due
        }

    override suspend fun deleteInboxRows(userIds: Collection<UserId>, messageId: UUID): Int =
        synchronized(this) { userIds.count { of(it).remove(messageId.toString()) != null } }

    override suspend fun settings(userId: UserId) = stored[userId] ?: MessagingSettings()

    override suspend fun save(userId: UserId, settings: MessagingSettings) {
        stored[userId] = settings
    }
}

/** `msg:pending` in memory; [lose] forgets a counter, as a Redis restart would. */
class MemoryPending : PendingCounts {
    val counts = ConcurrentHashMap<UUID, Long>()

    override suspend fun start(messageId: UUID, count: Int) {
        counts[messageId] = count.toLong()
    }

    override suspend fun countDown(messageId: UUID): Long? = counts.computeIfPresent(messageId) { _, n -> n - 1 }

    override suspend fun clear(messageId: UUID) {
        counts.remove(messageId)
    }

    fun lose(messageId: UUID) = counts.remove(messageId)
}

/** `msg:receipts` in memory: the furthest status per person; a batch stays claimed until taken or [expireClaims]. */
class MemoryReceipts : ReceiptBuffer {
    val collected = ConcurrentHashMap<UUID, MutableMap<UserId, ReceiptStatus>>()
    private val claimed = ConcurrentHashMap.newKeySet<UUID>()

    override suspend fun add(
        messageId: UUID,
        userId: UserId,
        status: ReceiptStatus,
    ) {
        collected.getOrPut(messageId) { ConcurrentHashMap() }.merge(userId, status) { old, new -> maxOf(old, new) }
    }

    override suspend fun claimBatch(messageId: UUID) = claimed.add(messageId)

    override suspend fun takeBatch(messageId: UUID): Map<UserId, ReceiptStatus> {
        claimed.remove(messageId)
        return collected.remove(messageId).orEmpty()
    }

    fun expireClaims() = claimed.clear()
}

/** `msg:typing` in memory; a mark lasts until [expire]. */
class MemoryTyping : TypingMarks {
    private val marks = ConcurrentHashMap.newKeySet<Pair<ChatId, UserId>>()

    override suspend fun start(chatId: ChatId, userId: UserId) = marks.add(chatId to userId)

    override suspend fun stop(chatId: ChatId, userId: UserId) {
        marks.remove(chatId to userId)
    }

    fun expire() = marks.clear()
}

/** Records each live frame once, with everyone it went to, as one `deliverMany` does. */
class RecordingLiveFrames : LiveFrames {
    data class Sent(val to: Set<UserId>, val type: String, val frame: MessagingFrame) {
        inline fun <reified T : MessagingFrame> data(): T = frame as T
    }

    val sent = CopyOnWriteArrayList<Sent>()

    override suspend fun send(userIds: Collection<UserId>, frame: MessagingFrame) {
        sent += Sent(userIds.toSet(), frame.type, frame)
    }

    fun of(type: String) = sent.filter { it.type == type }
}
