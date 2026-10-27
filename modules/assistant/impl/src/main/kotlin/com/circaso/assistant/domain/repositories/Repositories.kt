package com.circaso.assistant.domain.repositories

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Plan
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

    /** Deletes those of [messageIds] that are still in the user's chat; how many it deleted (0: someone was first). */
    suspend fun delete(userId: UserId, messageIds: Collection<UUID>): Int
}

interface EntitlementRepository {
    suspend fun planOf(userId: UserId): Plan
}

/**
 * What one operation adds, written in one transaction. Phase A publishes no events; Phase B adds them here, written to
 * the outbox in the same transaction and published by the caller after the commit.
 */
data class AssistantChanges(val messages: List<ChatMessage> = emptyList(), val audit: List<AuditEntry> = emptyList())

/** An audit record says what happened, never what was said. */
data class AuditEntry(
    val userId: UserId,
    val kind: String,
    val detail: String,
    val requestId: String?,
)

interface AssistantStore {
    suspend fun commit(changes: AssistantChanges)

    /**
     * Deletes every message of the user's chat; how many went. The audit records stay: they back actions, not
     * conversation.
     */
    suspend fun deleteChat(userId: UserId): Int

    /**
     * A deleted account (`identity.AccountDeleted`): its chat and its entitlement. What stays holds no conversation
     * text: the audit records and the usage totals for billing (assistant.md → Deleting).
     */
    suspend fun erase(userId: UserId)

    /**
     * A day's totals for cost reports, read from the live counters. Totals only grow within a day, so a slower writer
     * holding older totals never lowers what a faster one stored.
     */
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
