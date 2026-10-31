package com.circaso.assistant.data.repositories

import com.circaso.assistant.data.tables.AshaConversationsTable
import com.circaso.assistant.data.tables.AshaMessagesTable
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ConversationCursor
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant
import java.util.UUID

/**
 * The user's conversations, every statement keyed by the user first, so another user's conversation is never found.
 * The list is a keyset on `(last_message_at, conversation_id) DESC` over `asha_conversations_recent_idx`. Titles are
 * sealed like the messages, and opened only here.
 */
class ConversationRepositoryImpl(private val database: Database, private val vault: TextVault) :
    ConversationRepository {
    override suspend fun conversation(userId: UserId, conversationId: UUID): Conversation? =
        database.tx("conversations.find") {
            AshaConversationsTable
                .selectAll()
                .where { mine(userId, conversationId) }
                .singleOrNull()
                ?.toConversation()
        }

    override suspend fun latestConversation(userId: UserId): Conversation? =
        database.tx("conversations.latest") {
            newestFirst(userId, null, 1).singleOrNull()
        }

    override suspend fun conversations(
        userId: UserId,
        after: ConversationCursor?,
        limit: Int,
    ): List<Conversation> =
        database.tx("conversations.list") {
            newestFirst(userId, after, limit)
        }

    private suspend fun newestFirst(
        userId: UserId,
        after: ConversationCursor?,
        limit: Int,
    ): List<Conversation> =
        AshaConversationsTable
            .selectAll()
            .where {
                var condition: Op<Boolean> = AshaConversationsTable.userId eq userId.value
                if (after != null) condition = condition and before(after)
                condition
            }.orderBy(
                AshaConversationsTable.lastMessageAt to SortOrder.DESC,
                AshaConversationsTable.conversationId to SortOrder.DESC,
            ).limit(limit)
            .toList()
            .map { it.toConversation() }

    /** `(last_message_at, conversation_id) < (cursor)`, written out so the index's leading columns are used. */
    private fun before(after: ConversationCursor): Op<Boolean> =
        (AshaConversationsTable.lastMessageAt less after.lastMessageAt) or
            (
                (AshaConversationsTable.lastMessageAt eq after.lastMessageAt) and
                    (AshaConversationsTable.conversationId less after.conversationId)
            )

    override suspend fun addConversation(conversation: Conversation) {
        database.tx("conversations.add") {
            AshaConversationsTable.insert {
                it[userId] = conversation.userId.value
                it[conversationId] = conversation.conversationId
                it[title] = conversation.title?.let(vault::seal)
                it[createdAt] = conversation.createdAt
                it[updatedAt] = conversation.updatedAt
                it[lastMessageAt] = conversation.lastMessageAt
            }
        }
    }

    override suspend fun renameConversation(
        userId: UserId,
        conversationId: UUID,
        title: String,
        at: Instant,
    ): Conversation? =
        database.tx("conversations.rename") {
            AshaConversationsTable
                .updateReturning(where = { mine(userId, conversationId) }) {
                    it[this.title] = vault.seal(title)
                    it[updatedAt] = at
                }.singleOrNull()
                ?.toConversation()
        }

    override suspend fun touchConversation(
        userId: UserId,
        conversationId: UUID,
        at: Instant,
    ): Conversation? =
        database.tx("conversations.touch") {
            AshaConversationsTable
                .updateReturning(where = { mine(userId, conversationId) }) {
                    it[updatedAt] = at
                    it[lastMessageAt] = at
                }.singleOrNull()
                ?.toConversation()
        }

    override suspend fun titleConversation(
        userId: UserId,
        conversationId: UUID,
        title: String,
    ): Boolean =
        database.tx("conversations.title") {
            AshaConversationsTable.update(
                where = { mine(userId, conversationId) and AshaConversationsTable.title.isNull() },
            ) {
                it[this.title] = vault.seal(title)
            } > 0
        }

    override suspend fun deleteConversation(userId: UserId, conversationId: UUID): Int =
        database.tx("conversations.delete") {
            val deleted = AshaConversationsTable.deleteWhere { mine(userId, conversationId) }
            if (deleted > 0) {
                AshaMessagesTable.deleteWhere {
                    (this.userId eq userId.value) and (this.conversationId eq conversationId)
                }
            }
            deleted
        }

    private fun mine(userId: UserId, conversationId: UUID): Op<Boolean> =
        (AshaConversationsTable.userId eq userId.value) and
            (AshaConversationsTable.conversationId eq conversationId)

    private fun ResultRow.toConversation() =
        Conversation(
            UserId(this[AshaConversationsTable.userId]),
            this[AshaConversationsTable.conversationId],
            this[AshaConversationsTable.title]?.let(vault::open),
            this[AshaConversationsTable.createdAt],
            this[AshaConversationsTable.updatedAt],
            this[AshaConversationsTable.lastMessageAt],
        )
}
