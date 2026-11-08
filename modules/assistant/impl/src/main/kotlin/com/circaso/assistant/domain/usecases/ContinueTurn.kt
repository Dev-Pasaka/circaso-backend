package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.DeviceToolResult
import com.circaso.assistant.domain.models.ToolStatus
import com.circaso.assistant.domain.models.TurnResult
import com.circaso.assistant.domain.ports.DeviceTools
import com.circaso.assistant.domain.ports.PendingReadNote
import com.circaso.assistant.domain.ports.PendingTurn
import com.circaso.assistant.domain.ports.PendingTurns
import com.circaso.assistant.domain.support.AgentTurn
import com.circaso.assistant.domain.support.AshaAgent
import com.circaso.assistant.domain.support.AshaOutage
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.ConversationResolver
import com.circaso.assistant.domain.support.ReplyListener
import com.circaso.assistant.domain.support.TurnRecorder
import com.circaso.assistant.domain.support.isOwnAgentToken
import com.circaso.assistant.domain.support.toModel
import com.circaso.assistant.domain.support.today
import com.circaso.identity.api.TokenVerifier
import com.circaso.integrations.api.ModelMessage
import com.circaso.integrations.api.ModelPurpose
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import java.time.Clock

/**
 * A9: the phone's answer to a device tool, and the turn carries on. The snippets are capped (30, each short), shown
 * to the model as other people's words, used for this turn and dropped: only a count and the chat ids are kept.
 */
class ContinueTurn(
    private val verifier: TokenVerifier,
    private val pending: PendingTurns,
    private val conversations: ConversationResolver,
    private val agent: AshaAgent,
    private val recorder: TurnRecorder,
    private val config: AssistantConfig,
    private val clock: Clock,
    private val outage: AshaOutage,
) {
    suspend operator fun invoke(
        result: DeviceToolResult,
        listener: ReplyListener = ReplyListener.None,
        /** [ModelPurpose.VOICE] when the turn belongs to a live voice conversation, as its start did. */
        purpose: ModelPurpose = ModelPurpose.CHAT,
    ): Resource<TurnResult> {
        // checked before the pending turn is taken, so a wrong token doesn't use it up
        if (!isOwnAgentToken(verifier, result.userId, result.agentToken)) {
            return Resource.error(SharedError.TokenInvalid)
        }
        // a wrong call id is the phone's mistake: the turn stays waiting for the right answer
        val waitingOn =
            pending.callIdOf(result.userId, result.turnId) ?: return Resource.error(AssistantError.TurnNotFound)
        if (waitingOn != result.callId) {
            return Resource.error(SharedError.Malformed("callId", "is not the call this turn waits on"))
        }
        val turn = pending.take(result.userId, result.turnId) ?: return Resource.error(AssistantError.TurnNotFound)
        if (turn.callId != result.callId) {
            // re-parked with a new call between the check and the take: that call stays waiting for its own answer
            pending.save(turn)
            return Resource.error(AssistantError.TurnNotFound)
        }

        // its `tool_done` goes first on the stream, with the mascot, from the agent
        val device = ToolStatus(turn.turnId, turn.callId, turn.toolName, DeviceTools.label(turn.toolName))

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
            when (val r = carryOn(turn, conversation, result.agentToken, listener, purpose, device)) {
                is Resource.Success -> r.data
                is Resource.Error -> return outage.failure(turn.userId, r)
            }
        // a turn parked before conversations existed goes where an A2 without one would
        val conversationId =
            turn.conversationId
                ?: when (val r = conversations.resolve(turn.userId, null)) {
                    is Resource.Success -> r.data.conversationId
                    is Resource.Error -> return r
                }
        return recorder.conclude(
            turn.userId,
            conversationId,
            turn.userMessageId,
            turn.userText,
            turn.askedFrom,
            result.requestId,
            outcome,
            PendingReadNote(note.chatIds, note.chatNames, note.messageCount),
            turn.currency,
        )
    }

    /** The saved answer starts with what the user was shown before the pause; only the rest streams now. */
    private suspend fun carryOn(
        turn: PendingTurn,
        conversation: List<ModelMessage>,
        agentToken: String?,
        listener: ReplyListener,
        purpose: ModelPurpose,
        device: ToolStatus,
    ) = agent.run(
        conversation,
        AgentTurn(
            turnId = turn.turnId,
            userId = turn.userId,
            agentToken = agentToken,
            currency = turn.currency,
            deviceTools = true,
            purpose = purpose,
            today = clock.today(),
            conversationId = turn.conversationId,
            question = turn.userText,
        ),
        startSteps = turn.steps,
        shownBefore = AshaAgent.shownSoFar(turn.conversation),
        listener = listener,
        deviceDone = device,
    )
}
