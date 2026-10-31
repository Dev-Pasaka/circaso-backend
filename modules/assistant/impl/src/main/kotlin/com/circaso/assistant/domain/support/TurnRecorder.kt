package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Conversation
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.ports.Transactor
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.repositories.ConversationRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** `yyyy-MM-dd` in UTC: the day an allowance counts in. */
fun Clock.today(): String = LocalDate.now(this.withZone(ZoneOffset.UTC)).toString()

/**
 * What happens when the agent stops. An answer is saved with the question in its conversation, one audit record says
 * what was done (never what was said), and the day's usage is updated, all in one transaction; the conversation's
 * first exchange also titles it (decision 2026-10-31). A question for the phone is parked for 10 minutes instead.
 */
class TurnRecorder(
    private val store: AssistantStore,
    private val conversations: ConversationRepository,
    private val titler: ConversationTitler,
    private val pending: PendingTurns,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val ids: OrderedIds,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend fun conclude(
        userId: UserId,
        conversationId: UUID,
        turnId: UUID,
        userText: String,
        askedFrom: String?,
        requestId: String?,
        outcome: AgentOutcome,
        readNote: PendingReadNote,
        currency: String? = null,
    ): Resource<TurnResult> {
        val day = clock.today()
        quota.addTokens(userId, day, outcome.inputTokens, outcome.outputTokens)

        return when (outcome) {
            is AgentOutcome.WaitingForDevice -> {
                pending.save(
                    PendingTurn(
                        userId,
                        turnId,
                        askedFrom,
                        userText,
                        turnId,
                        outcome.call.id,
                        outcome.call.name,
                        outcome.conversation,
                        readNote,
                        outcome.steps,
                        currency,
                        conversationId,
                    ),
                )
                Resource.success(
                    TurnResult.NeedsDevice(
                        turnId,
                        outcome.call.id,
                        outcome.call.name,
                        outcome.call.argumentsJson,
                        conversationId,
                    ),
                )
            }

            is AgentOutcome.Answered -> {
                val now = clock.instant()
                val note =
                    ReadNote(readNote.chatIds, readNote.messageCount, readNote.chatNames).takeIf {
                        it.messageCount >
                            0
                    }
                val replyId = ids.next()
                val question =
                    ChatMessage(
                        userId,
                        turnId,
                        Role.USER,
                        userText,
                        null,
                        replyId,
                        askedFrom,
                        null,
                        now,
                        conversationId,
                    )
                val reply =
                    ChatMessage(
                        userId,
                        replyId,
                        Role.ASHA,
                        outcome.text.ifBlank {
                            "I don't have an answer for that."
                        },
                        turnId,
                        null,
                        askedFrom,
                        note,
                        now,
                        conversationId,
                    )
                val audit =
                    AuditEntry(
                        userId,
                        "TURN",
                        "prompt=${config.promptVersion} tools=${outcome.toolsUsed.joinToString(
                            ",",
                        ).ifEmpty { "none" }} " +
                            "chatMessagesRead=${readNote.messageCount} in=${outcome.inputTokens} " +
                            "out=${outcome.outputTokens}",
                        requestId,
                    )
                // The day's totals come from Redis before the transaction, which holds database work only.
                val totals = quota.totals(userId, day)
                val saved =
                    transactor.run("chat.record-turn") {
                        // The conversation first, locked for the rest of the transaction: one deleted while Asha
                        // answered keeps nothing of the exchange (lock order: the conversation, then its messages).
                        val conversation = conversations.touchConversation(userId, conversationId, now)
                        val title = conversation?.takeIf { it.title == null }?.let { titleOf(it, userText) }
                        val exchange = if (conversation == null) emptyList() else listOf(question, reply)
                        store.commit(AssistantChanges(messages = exchange, audit = listOf(audit)))
                        if (title != null) conversations.titleConversation(userId, conversationId, title)
                        store.saveUsage(userId, day, totals, now)
                        conversation?.copy(title = conversation.title ?: title)
                    }
                Resource.success(TurnResult.Answer(reply, saved))
            }
        }
    }

    /** The first exchange titles the conversation from its question (read before this exchange is written). */
    private suspend fun titleOf(conversation: Conversation, userText: String): String? =
        titler.firstTitle(conversation.userId, conversation.conversationId, asking = userText)
}
