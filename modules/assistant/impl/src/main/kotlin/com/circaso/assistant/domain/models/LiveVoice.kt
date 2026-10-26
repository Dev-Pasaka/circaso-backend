package com.circaso.assistant.domain.models

import com.circaso.integrations.api.LiveSpeechSession
import com.circaso.integrations.api.LiveTranscriptionSession
import com.circaso.shared.DomainError
import com.circaso.shared.UserId
import java.time.Duration

/** What the phone's first frame on the live voice socket (A11) says. */
data class LiveVoiceStart(
    val accessToken: String,
    /** The user's read-only agent token, for Asha's tools, as `X-Agent-Token` on A2. */
    val agentToken: String?,
    /** The user's language setting; a language without a live voice is refused, and the phone uses clips (A3). */
    val language: String?,
    val chatReading: Boolean,
    val requestId: String?,
)

/** An accepted live session: who is talking, the vendor sessions, and the allowance it runs against. */
class LiveVoiceLine(
    val userId: UserId,
    val start: LiveVoiceStart,
    val transcription: LiveTranscriptionSession,
    val speech: LiveSpeechSession,
    val voiceSecondsLeft: Long,
    val voiceSecondsPerDay: Int,
    /** Quiet this long (nobody speaking, Asha included) and the socket closes. */
    val silence: Duration,
)

/** What the conversation tells the phone; the controller turns each into a frame. */
sealed interface LiveEvent {
    /** The words of the turn so far: [isFinal] false while they may still change. */
    data class Transcript(val text: String, val isFinal: Boolean) : LiveEvent

    /** The user finished; [text] is the question Asha is now answering. */
    data class TurnStarted(val text: String) : LiveEvent

    /** One sentence of Asha's reply, sent just before its audio. */
    data class ReplyText(val text: String) : LiveEvent

    /** A piece of Asha's spoken reply. Never stored. */
    class ReplyAudio(val bytes: ByteArray) : LiveEvent

    /** The reply as saved in the Asha chat. [spoken] is false when the user talked over it before it was heard. */
    data class ReplyDone(val message: ChatMessage, val spoken: Boolean) : LiveEvent

    /** Asha needs the phone to read its chats (a device tool); the phone answers on the socket. */
    data class DeviceToolAsked(val request: TurnResult.NeedsDevice) : LiveEvent

    /** The user talked over Asha: the phone stops playing what it has. */
    data object Interrupted : LiveEvent

    /** One turn failed (the model was down); the conversation goes on. */
    data class TurnFailed(val error: DomainError) : LiveEvent
}

/** Why a live conversation ended. */
sealed interface LiveEnd {
    /** The phone said goodbye (an `end` frame). */
    data object HungUp : LiveEnd

    /** The phone's socket went away. */
    data object PhoneGone : LiveEnd

    /** Nobody spoke for the silence timeout. */
    data object Silence : LiveEnd

    /** The day's voice (or text) allowance is used up. */
    data class OutOfAllowance(val error: DomainError) : LiveEnd

    /** The transcription or speech provider dropped the session. */
    data object ProviderFailed : LiveEnd
}
