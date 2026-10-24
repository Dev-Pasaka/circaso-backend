package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AssistantStore
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/** `yyyy-MM-dd` in UTC: the day an allowance counts in. */
fun Clock.today(): String = LocalDate.now(this.withZone(ZoneOffset.UTC)).toString()

/**
 * What happens when the agent stops. An answer is saved with the question, one audit record says what was done (never
 * what was said), and the day's usage is updated. A question for the phone is parked for 10 minutes instead.
 */
class TurnRecorder(
    private val store: AssistantStore,
    private val pending: PendingTurns,
    private val quota: QuotaCounters,
    private val config: AssistantConfig,
    private val ids: OrderedIds,
    private val clock: Clock,
) {
    suspend fun conclude(
        userId: UserId,
        turnId: UUID,
        userText: String,
        askedFrom: String?,
        requestId: String?,
        outcome: AgentOutcome,
        readNote: PendingReadNote,
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
                    ),
                )
                Resource.success(
                    TurnResult.NeedsDevice(turnId, outcome.call.id, outcome.call.name, outcome.call.argumentsJson),
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
                val question = ChatMessage(userId, turnId, Role.USER, userText, null, replyId, askedFrom, null, now)
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
                    )
                store.commit(
                    AssistantChanges(
                        messages = listOf(question, reply),
                        audit =
                            listOf(
                                AuditEntry(
                                    userId,
                                    "TURN",
                                    "prompt=${config.promptVersion} tools=${outcome.toolsUsed.joinToString(
                                        ",",
                                    ).ifEmpty { "none" }} " +
                                        "chatMessagesRead=${readNote.messageCount} in=${outcome.inputTokens} " +
                                        "out=${outcome.outputTokens}",
                                    requestId,
                                ),
                            ),
                    ),
                )
                val (input, output) = quota.tokens(userId, day)
                store.saveUsage(
                    userId,
                    day,
                    quota.textTurns(userId, day).toInt(),
                    quota.voiceSeconds(userId, day).toInt(),
                    input,
                    output,
                    now,
                )
                Resource.success(TurnResult.Answer(reply))
            }
        }
    }
}
