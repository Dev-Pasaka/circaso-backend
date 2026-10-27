package com.circaso.assistant.presentation.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The live voice socket's text frames (A11). Every frame carries `type`; binary frames are audio both ways.

/** The phone's text frames, one class per `type` (decision 2026-10-27), read with `type` as the discriminator. */
@Serializable
sealed interface LivePhoneFrame {
    /**
     * The first frame (`{"type":"auth","accessToken":…,"agentToken":…,"language":"en","chatReading":true}`), and
     * later fresh tokens before the access token's 15 minutes are up.
     */
    @Serializable
    @SerialName("auth")
    data class Auth(
        val accessToken: String = "",
        val agentToken: String? = null,
        val language: String? = null,
        val chatReading: Boolean = true,
    ) : LivePhoneFrame

    /** The answer to a `tool_request`. */
    @Serializable
    @SerialName("tool_results")
    data class ToolResults(
        val turnId: String? = null,
        val callId: String = "",
        val snippets: List<SnippetDto> = emptyList(),
    ) : LivePhoneFrame

    /** Hang up. */
    @Serializable
    @SerialName("end")
    data object End : LivePhoneFrame
}

/** Only a frame's `type`: what a frame that didn't decode meant to be, so a bad `tool_results` can be answered. */
@Serializable
data class LivePhoneFrameType(val type: String? = null)

@Serializable
data class LiveAudioDto(val encoding: String, val sampleRate: Int)

@Serializable
data class LiveReadyFrame(val voiceSecondsLeft: Long, val audio: LiveAudioDto, val type: String = "ready")

@Serializable
data class LiveTranscriptFrame(val text: String, val final: Boolean, val type: String = "transcript")

@Serializable
data class LiveTurnFrame(val text: String, val type: String = "turn")

@Serializable
data class LiveReplyFrame(val text: String, val type: String = "reply")

@Serializable
data class LiveReplyDoneFrame(val message: ChatMessageDto, val spoken: Boolean, val type: String = "reply_done")

@Serializable
data class LiveToolRequestFrame(
    val turnId: String,
    val callId: String,
    val name: String,
    val arguments: String,
    val type: String = "tool_request",
)

@Serializable
data class LiveInterruptedFrame(val type: String = "interrupted")

@Serializable
data class LiveErrorFrame(val code: String, val message: String, val type: String = "error")

/**
 * Sent just before the server closes the socket. [reason] is also the close reason: `silence`, `ended`, or an error
 * code (`assistant.quota_exceeded`, `assistant.language_not_supported`, `assistant.model_unavailable`).
 */
@Serializable
data class LiveClosingFrame(
    val reason: String,
    val message: String? = null,
    val retryAfterSeconds: Long? = null,
    val type: String = "closing",
)
