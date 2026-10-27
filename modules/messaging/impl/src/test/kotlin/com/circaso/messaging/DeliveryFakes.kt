package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessagingFrame
import com.circaso.messaging.api.ReceiptStatus
import com.circaso.messaging.domain.models.ClientClaim
import com.circaso.messaging.domain.models.GroupBody
import com.circaso.messaging.domain.models.InboxMessage
import com.circaso.messaging.domain.models.MessageRecord
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.ports.LiveFrames
import com.circaso.messaging.domain.ports.PendingCounts
import com.circaso.messaging.domain.ports.ReceiptBuffer
import com.circaso.messaging.domain.ports.TypingMarks
import com.circaso.messaging.domain.repositories.ChatMarkers
import com.circaso.messaging.domain.repositories.DeliveryRepository
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.CompletableDeferred
import java.util.TreeMap
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Inboxes, group bodies, client-id claims, chat-list markers and settings in memory, with the tables' rules: a claim
 * and a conditional delete are atomic. [failInboxWrites] makes the next sends fail after [writtenBeforeFailing]
 * batches (a later batch failing); [writeGate], when set, holds every write until it is completed; [batches] records
 * the size of each batch; [inboxRead] runs on each `inboxRow`, so a test can interleave two acks.
 */
class MemoryDelivery :
    DeliveryRepository,
    ChatMarkers,
    SettingsRepository {
    val inboxes = ConcurrentHashMap<UserId, TreeMap<UUID, InboxMessage>>()
    val bodies = ConcurrentHashMap<Pair<ChatId, UUID>, GroupBody>()
    val clientIds = ConcurrentHashMap<Pair<UserId, UUID>, ClientClaim>()
    val records = ConcurrentHashMap<UUID, MessageRecord>()
    val lastMessages = ConcurrentHashMap<Pair<UserId, ChatId>, UUID>()
    val readMarks = ConcurrentHashMap<Pair<UserId, ChatId>, UUID>()
    val stored = ConcurrentHashMap<UserId, MessagingSettings>()
    val batches = CopyOnWriteArrayList<Int>()
    var failInboxWrites = 0
    var writtenBeforeFailing = 0
    var writeGate: CompletableDeferred<Unit>? = null
    val writesStarted = CompletableDeferred<Unit>()
    var inboxRead: suspend () -> Unit = {}

    private fun of(userId: UserId) = inboxes.getOrPut(userId) { TreeMap() }

    fun inboxOf(userId: UserId): List<InboxMessage> = synchronized(this) { of(userId).values.toList() }

    override suspend fun claimClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
    ): ClientClaim? = clientIds.putIfAbsent(senderId to clientMessageId, ClientClaim(messageId, complete = false))

    override suspend fun completeClientMessage(
        senderId: UserId,
        clientMessageId: UUID,
        messageId: UUID,
    ) {
        clientIds[senderId to clientMessageId] = ClientClaim(messageId, complete = true)
    }

    /** [batches] counts statements per batch, the record in the first, as the unlogged batches do. */
    override suspend fun writeMessage(
        record: MessageRecord,
        rows: List<InboxMessage>,
        batchSize: Int,
    ) {
        writesStarted.complete(Unit)
        writeGate?.await()
        val chunks = (listOf<Any>(record) + rows).chunked(batchSize)
        for ((index, chunk) in chunks.withIndex()) {
            if (failInboxWrites > 0 && index == writtenBeforeFailing) {
                failInboxWrites--
                error("batch $index failed")
            }
            batches += chunk.size
            write(chunk)
        }
    }

    private fun write(chunk: List<Any>) =
        synchronized(this) {
            for (item in chunk) {
                if (item is MessageRecord) records[item.messageId] = item
                if (item is InboxMessage) of(item.userId)[item.messageId] = item
            }
        }

    override suspend fun messageRecord(messageId: UUID) = records[messageId]

    override suspend fun writeGroupBody(body: GroupBody) {
        bodies[body.chatId to body.messageId] = body
    }

    override suspend fun groupBody(chatId: ChatId, messageId: UUID) = bodies[chatId to messageId]

    override suspend fun deleteGroupBody(chatId: ChatId, messageId: UUID) {
        bodies.remove(chatId to messageId)
    }

    override suspend fun inboxRow(userId: UserId, messageId: UUID): InboxMessage? {
        inboxRead()
        return synchronized(this) { of(userId)[messageId] }
    }

    override suspend fun inbox(
        userId: UserId,
        after: UUID?,
        limit: Int,
    ): List<InboxMessage> =
        synchronized(this) {
            val rows = if (after == null) of(userId) else of(userId).tailMap(after, false)
            rows.values.take(limit)
        }

    override suspend fun claimInboxRow(userId: UserId, messageId: UUID): Boolean =
        synchronized(this) { of(userId).remove(messageId) != null }

    override suspend fun deleteInboxRows(
        userIds: Collection<UserId>,
        messageId: UUID,
        batchSize: Int,
    ) = synchronized(this) { userIds.forEach { of(it).remove(messageId) } }

    override suspend fun lastMessage(
        userIds: Collection<UserId>,
        chatId: ChatId,
        messageId: UUID,
        batchSize: Int,
    ) = userIds.forEach { lastMessages[it to chatId] = messageId }

    override suspend fun readUpTo(
        userId: UserId,
        chatId: ChatId,
        messageId: UUID,
    ) {
        readMarks[userId to chatId] = messageId
    }

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
