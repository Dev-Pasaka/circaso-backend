package com.circaso.assistant.domain.repositories

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Plan
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

interface ChatRepository {
    suspend fun find(userId: UserId, messageId: UUID): ChatMessage?

    /** Newest first; [before] is the last message id of the previous page. */
    suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ): List<ChatMessage>
}

interface EntitlementRepository {
    suspend fun planOf(userId: UserId): Plan
}

/** What one operation changes, written in one batch with its outbox rows. */
data class AssistantChanges(
    val messages: List<ChatMessage> = emptyList(),
    val deletedMessages: List<ChatMessage> = emptyList(),
    val audit: List<AuditEntry> = emptyList(),
    val events: List<EventEnvelope> = emptyList(),
)

/** An audit record says what happened, never what was said. */
data class AuditEntry(
    val userId: UserId,
    val kind: String,
    val detail: String,
    val requestId: String?,
)

interface AssistantStore {
    suspend fun commit(changes: AssistantChanges)

    /** Deletes every message of the user's chat. The audit records stay: they back actions, not conversation. */
    suspend fun deleteChat(userId: UserId)

    /** A day's totals for cost reports. */
    suspend fun saveUsage(
        userId: UserId,
        day: String,
        textTurns: Int,
        voiceSeconds: Int,
        inputTokens: Long,
        outputTokens: Long,
        at: Instant,
    )
}
