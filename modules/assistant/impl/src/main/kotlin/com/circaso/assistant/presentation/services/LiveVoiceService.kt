package com.circaso.assistant.presentation.services

import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.LiveVoiceStart
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.support.LiveConversation
import com.circaso.assistant.domain.usecases.CheckLiveSession
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.MeterLiveVoice
import com.circaso.assistant.domain.usecases.OpenLiveVoice
import com.circaso.assistant.domain.usecases.RecordLiveTiming
import com.circaso.assistant.domain.usecases.SaveLiveVoiceUsage
import com.circaso.assistant.domain.usecases.StartTurn
import com.circaso.integrations.api.ModelPurpose
import com.circaso.shared.Resource
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Live voice (A11). Opening checks the user and the allowance and opens the providers; each turn of the conversation
 * then goes through [StartTurn] and [ContinueTurn], the use cases behind A2 and A9, so a spoken turn is a typed one.
 */
class LiveVoiceService(
    private val openLiveVoice: OpenLiveVoice,
    private val meterLiveVoice: MeterLiveVoice,
    private val startTurn: StartTurn,
    private val continueTurn: ContinueTurn,
    private val checkLiveSession: CheckLiveSession,
    private val saveLiveVoiceUsage: SaveLiveVoiceUsage,
    private val recordLiveTiming: RecordLiveTiming,
) {
    suspend fun open(start: LiveVoiceStart): Resource<LiveConversation> =
        when (val opened = openLiveVoice(start)) {
            is Resource.Success -> Resource.success(conversation(opened.data))
            is Resource.Error -> opened
        }

    /**
     * Every turn of one call goes to one conversation: the one the `auth` frame named, or else the one its first turn
     * went to (the most recent, or a new one), even if the user starts another meanwhile.
     */
    private fun conversation(line: LiveVoiceLine): LiveConversation {
        val conversationId = AtomicReference(line.start.conversationId)
        return LiveConversation(
            line,
            ask = { text, agentToken, reply ->
                startTurn(turn(line, text, agentToken, conversationId.get()), reply).also { asked ->
                    if (asked is Resource.Success) conversationId.compareAndSet(null, asked.data.conversationId)
                }
            },
            answerDevice = { result, reply -> continueTurn(result, reply, ModelPurpose.VOICE) },
            meter = { seconds -> meterLiveVoice(line.userId, seconds, line.voiceSecondsPerDay) },
            checkSession = { access, agent -> checkLiveSession(line.userId, access, agent) is Resource.Success },
            saveUsage = { saveLiveVoiceUsage(line.userId) },
            timed = { recordLiveTiming(it) },
        )
    }

    private fun turn(
        line: LiveVoiceLine,
        text: String,
        agentToken: String?,
        conversationId: UUID?,
    ) = TurnCommand(
        line.userId,
        text,
        askedFrom = null,
        chatReading = line.start.chatReading,
        agentToken = agentToken,
        requestId = line.start.requestId,
        purpose = ModelPurpose.VOICE,
        currency = line.start.currency,
        conversationId = conversationId,
    )
}
