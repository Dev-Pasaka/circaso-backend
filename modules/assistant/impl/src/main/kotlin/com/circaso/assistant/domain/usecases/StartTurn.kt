package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.repositories.ChatRepository
import com.circaso.assistant.domain.repositories.EntitlementRepository
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.OrderedIds
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.support.today
import com.circaso.integrations.api.ModelMessage
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/**
 * A2: one question to Asha. It counts against the day's allowance, gives the model the recent part of the chat as
 * context (a few exchanges on the free plan, many on Asha Plus) and runs the agent. The answer, or a question for
 * the phone, comes back.
 */
class StartTurn(
    private val chat: ChatRepository,
    private val entitlements: EntitlementRepository,
    private val quota: QuotaCounters,
    private val agent: AshaAgent,
    private val recorder: TurnRecorder,
    private val config: AssistantConfig,
    private val ids: OrderedIds,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: TurnCommand): Resource<TurnResult> {
        val text = command.text.trim()
        if (text.isEmpty() || text.length > config.maxMessageChars) {
            return Resource.error(
                SharedError.Malformed(
                    mapOf("text" to "must be 1 to ${config.maxMessageChars} characters"),
                ),
            )
        }
        val userId = command.userId
        val limits = config.limitsOf(entitlements.planOf(userId))
        if (quota.countTextTurn(userId, clock.today()) > limits.textTurnsPerDay) {
            return Resource.error(AssistantError.QuotaExceeded(secondsUntilReset()))
        }

        val context = chat.list(userId, null, limits.contextMessages).asReversed().map(::toModel)
        val asked = command.askedFrom?.let { "[Asked from the chat \"${it.chatName ?: it.chatId}\"] " }.orEmpty()
        val history = context + ModelMessage.User(asked + text)

        val outcome =
            when (val r = agent.run(history, command.agentToken, command.chatReading)) {
                is Resource.Success -> r.data
                is Resource.Error -> return r
            }
        return recorder.conclude(
            userId,
            ids.next(),
            text,
            command.askedFrom?.let { it.chatName ?: it.chatId },
            command.requestId,
            outcome,
            PendingReadNote(emptyList(), emptyList(), 0),
        )
    }

    private fun toModel(message: ChatMessage): ModelMessage =
        if (message.role == Role.USER) ModelMessage.User(message.text) else ModelMessage.Assistant(message.text)

    private fun secondsUntilReset(): Long {
        val now = clock.instant().atZone(ZoneOffset.UTC)
        return Duration
            .between(
                now,
                now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC),
            ).seconds
            .coerceAtLeast(1)
    }
}
