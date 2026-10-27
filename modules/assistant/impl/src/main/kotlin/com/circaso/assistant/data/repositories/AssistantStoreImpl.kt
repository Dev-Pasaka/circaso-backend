package com.circaso.assistant.data.repositories

import com.circaso.assistant.data.mappers.toColumn
import com.circaso.assistant.data.tables.AshaMessagesTable
import com.circaso.assistant.data.tables.AuditTable
import com.circaso.assistant.data.tables.EntitlementsTable
import com.circaso.assistant.data.tables.UsageDailyTable
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.ports.TextVault
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.CustomFunction
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.statements.UpsertBuilder
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Writes one operation's messages and audit records in one transaction: the use case's own when it has one open,
 * otherwise a transaction of its own. The outbox (`outbox = true`) waits for Phase B's events.
 */
class AssistantStoreImpl(private val database: Database, private val vault: TextVault, private val clock: Clock) :
    AssistantStore {
    override suspend fun commit(changes: AssistantChanges) {
        if (changes == AssistantChanges()) return
        database.tx("assistant.commit") {
            addMessages(changes.messages)
            addAudit(changes.audit)
        }
    }

    private suspend fun addMessages(messages: List<ChatMessage>) {
        if (messages.isEmpty()) return
        AshaMessagesTable.batchInsert(messages, shouldReturnGeneratedValues = false) {
            this[AshaMessagesTable.userId] = it.userId.value
            this[AshaMessagesTable.messageId] = it.messageId
            this[AshaMessagesTable.role] = it.role.name
            this[AshaMessagesTable.body] = vault.seal(it.text)
            this[AshaMessagesTable.replyTo] = it.replyTo
            this[AshaMessagesTable.answeredBy] = it.answeredBy
            this[AshaMessagesTable.askedFrom] = it.askedFrom
            this[AshaMessagesTable.readNote] = it.readNote?.toColumn()
            this[AshaMessagesTable.createdAt] = it.createdAt
        }
    }

    /** Each record gets a UUIDv7 made here, so the trail reads in the order things happened. */
    private suspend fun addAudit(entries: List<AuditEntry>) {
        if (entries.isEmpty()) return
        AuditTable.batchInsert(entries, shouldReturnGeneratedValues = false) {
            this[AuditTable.userId] = it.userId.value
            this[AuditTable.eventId] = Uuid7.generate(clock.instant())
            this[AuditTable.kind] = it.kind
            this[AuditTable.detail] = it.detail
            this[AuditTable.requestId] = it.requestId
        }
    }

    override suspend fun deleteChat(userId: UserId): Int =
        database.tx("chat.delete-all") {
            AshaMessagesTable.deleteWhere { this.userId eq userId.value }
        }

    override suspend fun erase(userId: UserId) {
        database.tx("assistant.erase-user") {
            AshaMessagesTable.deleteWhere { this.userId eq userId.value }
            EntitlementsTable.deleteWhere { this.userId eq userId.value }
        }
    }

    /**
     * `INSERT … ON CONFLICT (user_id, usage_day) DO UPDATE SET text_turns = GREATEST(text_turns, EXCLUDED.text_turns),
     * …`: the totals come from the live counters, which only grow within a day, so two turns ending together store the
     * larger totals whichever commits last.
     */
    override suspend fun saveUsage(
        userId: UserId,
        day: String,
        textTurns: Int,
        voiceSeconds: Int,
        inputTokens: Long,
        outputTokens: Long,
        at: Instant,
    ) {
        database.tx("usage.save") {
            UsageDailyTable.upsert(
                UsageDailyTable.userId,
                UsageDailyTable.usageDay,
                onUpdate = { keepLarger(it) },
            ) {
                it[this.userId] = userId.value
                it[usageDay] = LocalDate.parse(day)
                it[this.textTurns] = textTurns
                it[this.voiceSeconds] = voiceSeconds
                it[this.inputTokens] = inputTokens
                it[this.outputTokens] = outputTokens
                it[updatedAt] = at
            }
        }
    }

    private fun UpsertBuilder.keepLarger(update: UpdateStatement) {
        update[UsageDailyTable.textTurns] = larger(UsageDailyTable.textTurns)
        update[UsageDailyTable.voiceSeconds] = larger(UsageDailyTable.voiceSeconds)
        update[UsageDailyTable.inputTokens] = larger(UsageDailyTable.inputTokens)
        update[UsageDailyTable.outputTokens] = larger(UsageDailyTable.outputTokens)
        update[UsageDailyTable.updatedAt] = insertValue(UsageDailyTable.updatedAt)
    }

    /** `GREATEST(<stored>, EXCLUDED.<column>)`. */
    private fun <T : Any> UpsertBuilder.larger(column: Column<T>) =
        CustomFunction("GREATEST", column.columnType, column, insertValue(column))
}
