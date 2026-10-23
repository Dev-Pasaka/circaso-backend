package com.circaso.assistant.data.repositories

import com.circaso.assistant.data.entities.AshaMessageRow
import com.circaso.assistant.data.entities.AssistantAuditRow
import com.circaso.assistant.data.entities.AssistantOutboxRow
import com.circaso.assistant.data.entities.EntitlementRow
import com.circaso.assistant.data.entities.UsageDailyRow
import com.circaso.assistant.data.mappers.toModel
import com.circaso.assistant.data.mappers.toRow
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.infrastructure.api.OutboxStore
import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import io.kandra.core.ExperimentalKandraApi
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.dsl.KandraColumnRef
import io.kandra.runtime.repository.KandraSuspendRepository
import java.time.Instant
import java.util.UUID

// Column names Kandra derives from the entity properties (camelCase to snake_case).
private val USER_ID = KandraColumnRef<UUID>("user_id")
private val MESSAGE_ID = KandraColumnRef<UUID>("message_id")
private val BUCKET = KandraColumnRef<String>("bucket")

class ChatRepositoryImpl(private val messages: KandraSuspendRepository<AshaMessageRow>, private val vault: TextVault) : ChatRepository {
    override suspend fun find(userId: UserId, messageId: UUID): ChatMessage? = messages.findById(userId.value, messageId)?.toModel(vault)

    override suspend fun list(userId: UserId, before: UUID?, limit: Int): List<ChatMessage> =
        messages.findAll(limit) {
            USER_ID eq userId.value
            if (before != null) MESSAGE_ID lt before
        }.map { it.toModel(vault) }
}

class EntitlementRepositoryImpl(private val entitlements: KandraSuspendRepository<EntitlementRow>) : EntitlementRepository {
    override suspend fun planOf(userId: UserId): Plan {
        val row = entitlements.findById(userId.value) ?: return Plan.FREE
        val live = row.expiresAt == null || row.expiresAt.isAfter(Instant.now())
        return if (live) runCatching { Plan.valueOf(row.plan) }.getOrDefault(Plan.FREE) else Plan.FREE
    }
}

/**
 * Writes an operation's messages, deletions and audit records in one LOGGED batch with its outbox rows, then hands
 * the events to the bus. Deleting a whole chat goes in chunks, so no batch grows past what the database allows.
 */
@OptIn(ExperimentalKandraApi::class)
class AssistantStoreImpl(
    private val runtime: KandraRuntime,
    private val messages: KandraSuspendRepository<AshaMessageRow>,
    private val usage: KandraSuspendRepository<UsageDailyRow>,
    private val audit: KandraSuspendRepository<AssistantAuditRow>,
    private val outbox: KandraSuspendRepository<AssistantOutboxRow>,
    private val vault: TextVault,
    private val bus: EventBus,
) : AssistantStore {

    override suspend fun commit(changes: AssistantChanges) {
        if (changes == AssistantChanges()) return
        runtime.batch {
            changes.messages.forEach { messages.saveInBatch(it.toRow(vault)) }
            changes.deletedMessages.forEach { messages.deleteInBatch(it.toRow(vault)) }
            changes.audit.forEach { audit.saveInBatch(AssistantAuditRow(it.userId.value, null, it.kind, it.detail, it.requestId)) }
            changes.events.forEach { outbox.saveInBatch(AssistantOutboxRow(it.bucket, it.eventId.value, it.type, it.toJson())) }
        }
        changes.events.forEach { bus.publish(it) }
    }

    override suspend fun deleteChat(userId: UserId) {
        while (true) {
            val rows = messages.findAll(PAGE) { USER_ID eq userId.value }
            if (rows.isEmpty()) return
            rows.chunked(CHUNK).forEach { chunk -> runtime.batch { chunk.forEach { messages.deleteInBatch(it) } } }
        }
    }

    override suspend fun saveUsage(userId: UserId, day: String, textTurns: Int, voiceSeconds: Int, inputTokens: Long, outputTokens: Long, at: Instant) {
        usage.save(UsageDailyRow(userId.value, day, textTurns, voiceSeconds, inputTokens, outputTokens, at))
    }

    private companion object {
        const val PAGE = 200
        const val CHUNK = 40
    }
}

/** The relay's view of the assistant's outbox: events written but not yet on the stream. */
class AssistantOutboxStore(private val outbox: KandraSuspendRepository<AssistantOutboxRow>) : OutboxStore {
    override val producer = "assistant"

    override suspend fun unpublished(bucket: String): List<EventEnvelope> =
        outbox.findAll { BUCKET eq bucket }.filter { it.publishedAt == null }.map { EventEnvelope.fromJson(it.payload) }

    override suspend fun markPublished(envelope: EventEnvelope, at: Instant) {
        val row = outbox.findById(envelope.bucket, envelope.eventId.value) ?: return
        outbox.save(row.copy(publishedAt = at))
    }
}
