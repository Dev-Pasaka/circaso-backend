package com.circaso.assistant.presentation.mappers

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.models.LiveEnd
import com.circaso.assistant.domain.models.LiveEvent
import com.circaso.assistant.domain.models.LiveVoiceLine
import com.circaso.assistant.domain.models.LiveVoiceStart
import com.circaso.assistant.presentation.dto.LiveAudioDto
import com.circaso.assistant.presentation.dto.LiveClosingFrame
import com.circaso.assistant.presentation.dto.LiveErrorFrame
import com.circaso.assistant.presentation.dto.LiveInterruptedFrame
import com.circaso.assistant.presentation.dto.LiveMascotFrame
import com.circaso.assistant.presentation.dto.LivePhoneFrame
import com.circaso.assistant.presentation.dto.LiveReadyFrame
import com.circaso.assistant.presentation.dto.LiveReplyDoneFrame
import com.circaso.assistant.presentation.dto.LiveReplyFrame
import com.circaso.assistant.presentation.dto.LiveTimingFrame
import com.circaso.assistant.presentation.dto.LiveToolDoneFrame
import com.circaso.assistant.presentation.dto.LiveToolRequestFrame
import com.circaso.assistant.presentation.dto.LiveToolStatusFrame
import com.circaso.assistant.presentation.dto.LiveTranscriptFrame
import com.circaso.assistant.presentation.dto.LiveTurnFrame
import com.circaso.shared.CircasoJson
import com.circaso.shared.DomainError
import java.util.UUID

fun LivePhoneFrame.Auth.toRequest(requestId: String?) =
    LiveVoiceStart(
        accessToken,
        agentToken?.takeIf { it.isNotBlank() },
        language,
        chatReading,
        requestId,
        displayCurrencyOf(currency),
        voice = voice,
        conversationId = conversationId?.let { parseUuid(it) ?: UNKNOWN_CONVERSATION },
    )

/** What an `auth` frame's conversation id that isn't a UUID becomes: an id no conversation has, so turns are refused. */
private val UNKNOWN_CONVERSATION = UUID(0, 0)

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
            voice,
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

        is LiveEvent.ToolStarted -> {
            CircasoJson.encodeToString(
                LiveToolStatusFrame.serializer(),
                status.toStatusFrame(),
            )
        }

        is LiveEvent.ToolFinished -> {
            CircasoJson.encodeToString(
                LiveToolDoneFrame.serializer(),
                status.toDoneFrame(isOk),
            )
        }

        is LiveEvent.Mascot -> {
            CircasoJson.encodeToString(LiveMascotFrame.serializer(), hints.toFrame())
        }

        is LiveEvent.Interrupted -> {
            CircasoJson.encodeToString(LiveInterruptedFrame.serializer(), LiveInterruptedFrame())
        }

        is LiveEvent.TurnFailed -> {
            CircasoJson.encodeToString(LiveErrorFrame.serializer(), LiveErrorFrame(error.code, error.message))
        }

        is LiveEvent.Timing -> {
            CircasoJson.encodeToString(
                LiveTimingFrame.serializer(),
                LiveTimingFrame(turn, modelFirstTextMs, firstSentenceMs, firstAudioMs),
            )
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
        is LiveEnd.AssistantUnavailable -> AssistantError.Unavailable.toClosing()
        is LiveEnd.SessionRevoked -> null
        is LiveEnd.FrameTooBig -> null
    }

fun DomainError.toClosing() = LiveClosingFrame(code, message, details?.retryAfterSeconds)

fun LiveClosingFrame.toJson(): String = CircasoJson.encodeToString(LiveClosingFrame.serializer(), this)
