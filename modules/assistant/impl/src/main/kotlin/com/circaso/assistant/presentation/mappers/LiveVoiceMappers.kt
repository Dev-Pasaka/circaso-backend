package com.circaso.assistant.presentation.mappers

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.LiveEnd
import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.LiveVoiceStart
import com.circaso.assistant.presentation.dto.LiveAudioDto
import com.circaso.assistant.presentation.dto.LiveAuthFrame
import com.circaso.assistant.presentation.dto.LiveClosingFrame
import com.circaso.assistant.presentation.dto.LiveErrorFrame
import com.circaso.assistant.presentation.dto.LiveInterruptedFrame
import com.circaso.assistant.presentation.dto.LiveReadyFrame
import com.circaso.assistant.presentation.dto.LiveReplyDoneFrame
import com.circaso.assistant.presentation.dto.LiveReplyFrame
import com.circaso.assistant.presentation.dto.LiveToolRequestFrame
import com.circaso.assistant.presentation.dto.LiveTranscriptFrame
import com.circaso.assistant.presentation.dto.LiveTurnFrame
import com.circaso.shared.CircasoJson
import com.circaso.shared.DomainError

fun LiveAuthFrame.toRequest(requestId: String?) =
    LiveVoiceStart(accessToken, agentToken?.takeIf { it.isNotBlank() }, language, chatReading, requestId)

fun LiveVoiceLine.toReadyJson(): String =
    CircasoJson.encodeToString(
        LiveReadyFrame.serializer(),
        LiveReadyFrame(
            voiceSecondsLeft,
            LiveAudioDto(
                speech.format.encoding.name
                    .lowercase(),
                speech.format.sampleRate,
            ),
        ),
    )

/** The JSON text frame for [this]; null for audio, which goes as a binary frame. */
fun LiveEvent.toJson(): String? =
    when (this) {
        is LiveEvent.Transcript -> {
            CircasoJson.encodeToString(LiveTranscriptFrame.serializer(), LiveTranscriptFrame(text, isFinal))
        }

        is LiveEvent.TurnStarted -> {
            CircasoJson.encodeToString(LiveTurnFrame.serializer(), LiveTurnFrame(text))
        }

        is LiveEvent.ReplyText -> {
            CircasoJson.encodeToString(LiveReplyFrame.serializer(), LiveReplyFrame(text))
        }

        is LiveEvent.ReplyAudio -> {
            null
        }

        is LiveEvent.ReplyDone -> {
            CircasoJson.encodeToString(LiveReplyDoneFrame.serializer(), LiveReplyDoneFrame(message.toDto(), spoken))
        }

        is LiveEvent.DeviceToolAsked -> {
            CircasoJson.encodeToString(
                LiveToolRequestFrame.serializer(),
                LiveToolRequestFrame(
                    request.turnId.toString(),
                    request.callId,
                    request.toolName,
                    request.argumentsJson,
                ),
            )
        }

        is LiveEvent.Interrupted -> {
            CircasoJson.encodeToString(LiveInterruptedFrame.serializer(), LiveInterruptedFrame())
        }

        is LiveEvent.TurnFailed -> {
            CircasoJson.encodeToString(LiveErrorFrame.serializer(), LiveErrorFrame(error.code, error.message))
        }
    }

/** The closing frame for an end the server decides; null when the phone left on its own. */
fun LiveEnd.toClosing(): LiveClosingFrame? =
    when (this) {
        is LiveEnd.PhoneGone -> null
        is LiveEnd.HungUp -> LiveClosingFrame("ended")
        is LiveEnd.Silence -> LiveClosingFrame("silence")
        is LiveEnd.OutOfAllowance -> error.toClosing()
        is LiveEnd.ProviderFailed -> AssistantError.ModelUnavailable.toClosing()
    }

fun DomainError.toClosing() = LiveClosingFrame(code, message, details?.retryAfterSeconds)

fun LiveClosingFrame.toJson(): String = CircasoJson.encodeToString(LiveClosingFrame.serializer(), this)
