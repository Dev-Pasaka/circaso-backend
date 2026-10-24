package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.support.toModel
import com.circaso.integrations.api.ModelMessage
import com.circaso.shared.Resource

/**
 * A9: the phone's answer to a device tool, and the turn carries on. The snippets are capped (30, each short), shown
 * to the model as other people's words, used for this turn and dropped: only a count and the chat ids are kept.
 */
class ContinueTurn(
    private val pending: PendingTurns,
    private val agent: AshaAgent,
    private val recorder: TurnRecorder,
    private val config: AssistantConfig,
) {
    suspend operator fun invoke(result: DeviceToolResult): Resource<TurnResult> {
        val turn =
            pending.take(result.userId, result.turnId)?.takeIf { it.callId == result.callId }
                ?: return Resource.error(AssistantError.TurnNotFound)

        val snippets =
            result.snippets
                .take(
                    config.maxSnippets,
                ).map { it.copy(text = it.text.take(config.maxSnippetChars)) }
        val note = AshaAgent.noteOf(snippets, turn.readNote)
        val conversation =
            turn.conversation.toModel() +
                ModelMessage.ToolResult(turn.callId, turn.toolName, AshaAgent.snippetsAsText(snippets))

        val outcome =
            when (val r = agent.run(conversation, result.agentToken, deviceTools = true, startSteps = turn.steps)) {
                is Resource.Success -> r.data
                is Resource.Error -> return r
            }
        return recorder.conclude(
            turn.userId,
            turn.userMessageId,
            turn.userText,
            turn.askedFrom,
            result.requestId,
            outcome,
            PendingReadNote(note.chatIds, note.chatNames, note.messageCount),
        )
    }
}
