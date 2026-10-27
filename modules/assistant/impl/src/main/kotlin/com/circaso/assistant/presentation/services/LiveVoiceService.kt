package com.circaso.assistant.presentation.services

import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.LiveVoiceStart
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.support.LiveConversation
import com.circaso.assistant.domain.usecases.CheckLiveSession
import com.circaso.assistant.domain.usecases.ContinueTurn
import com.circaso.assistant.domain.usecases.MeterLiveVoice
import com.circaso.assistant.domain.usecases.OpenLiveVoice
import com.circaso.assistant.domain.usecases.StartTurn
import com.circaso.shared.Resource

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
) {
    suspend fun open(start: LiveVoiceStart): Resource<LiveConversation> =
        when (val opened = openLiveVoice(start)) {
            is Resource.Success -> Resource.success(conversation(opened.data))
            is Resource.Error -> opened
        }

    private fun conversation(line: LiveVoiceLine) =
        LiveConversation(
            line,
            ask = { text, agentToken, reply -> startTurn(turn(line, text, agentToken), reply) },
            answerDevice = { result, reply -> continueTurn(result, reply) },
            meter = { seconds -> meterLiveVoice(line.userId, seconds, line.voiceSecondsPerDay) },
            checkSession = { access, agent -> checkLiveSession(line.userId, access, agent) is Resource.Success },
        )

    private fun turn(
        line: LiveVoiceLine,
        text: String,
        agentToken: String?,
    ) = TurnCommand(
        line.userId,
        text,
        askedFrom = null,
        chatReading = line.start.chatReading,
        agentToken = agentToken,
        requestId = line.start.requestId,
    )
}
