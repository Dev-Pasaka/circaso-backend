package com.circaso.assistant.domain.repositories

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ConversationCursor
import com.circaso.assistant.domain.models.Plan
import com.circaso.shared.UserId
import java.time.Instant
import java.util.UUID

interface ChatRepository {
    suspend fun find(userId: UserId, messageId: UUID): ChatMessage?

    /** Every conversation's messages (A1), newest first; [before] is the last message id of the previous page. */
    suspend fun list(
        userId: UserId,
        before: UUID?,
        limit: Int,
    ): List<ChatMessage>

    /** One conversation's messages, newest first; [before] is the last message id of the previous page. */
    suspend fun listIn(
        userId: UserId,
        conversationId: UUID,
        before: UUID?,
        limit: Int,
    ): List<ChatMessage>

    /** The conversation's first message, which its title is made from; null when it has none. */
    suspend fun oldestIn(userId: UserId, conversationId: UUID): ChatMessage?

    /** Deletes those of [messageIds] that are still in the user's chat; how many it deleted (0: someone was first). */
    suspend fun delete(userId: UserId, messageIds: Collection<UUID>): Int
}

/**
 * The user's conversations with Asha. Every read and write is scoped by the user: another user's conversation is
 * never found. The names say "conversation" so one in-memory fake can stand in for this and [ChatRepository].
 */
interface ConversationRepository {
    suspend fun conversation(userId: UserId, conversationId: UUID): Conversation?

    /** The one with the latest message (or the latest created, while empty); null when the user has none. */
    suspend fun latestConversation(userId: UserId): Conversation?

    /** Newest first by last message; [after] is where the previous page ended. */
    suspend fun conversations(
        userId: UserId,
        after: ConversationCursor?,
        limit: Int,
    ): List<Conversation>

    suspend fun addConversation(conversation: Conversation)

    /** Sets the title the user chose; the conversation as it now is, or null when it doesn't exist. */
    suspend fun renameConversation(
        userId: UserId,
        conversationId: UUID,
        title: String,
        at: Instant,
    ): Conversation?

    /**
     * Records a message at [at], locking the row for the rest of the transaction (a delete of the conversation waits
     * for it, or went first); the conversation as it now is, or null when it was deleted.
     */
    suspend fun touchConversation(
        userId: UserId,
        conversationId: UUID,
        at: Instant,
    ): Conversation?

    /** Titles a conversation that has no title yet; false when it had one (the user's, or a first title). */
    suspend fun titleConversation(
        userId: UserId,
        conversationId: UUID,
        title: String,
    ): Boolean

    /** Deletes the conversation, then its messages (parent first, as every path locks it); how many conversations. */
    suspend fun deleteConversation(userId: UserId, conversationId: UUID): Int
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
     * Deletes every conversation and message of the user's chat; how many went. The audit records stay: they back
     * actions, not conversation.
     */
    suspend fun deleteChat(userId: UserId): Int

    /**
     * A deleted account (`identity.AccountDeleted`): its conversations, its chat and its entitlement. What stays holds no conversation
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
        /** Added 2026-11-05; a writer that doesn't count them (older totals) stores 0, which never lowers a count. */
        webSearches: Int = 0,
    )
}
