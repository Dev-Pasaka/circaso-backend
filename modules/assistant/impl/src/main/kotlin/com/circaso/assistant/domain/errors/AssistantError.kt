package com.circaso.assistant.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind

/** Asha's own codes (Assistant → Error codes). */
sealed class AssistantError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    class QuotaExceeded(retryAfterSeconds: Long) :
        AssistantError(
            "assistant.quota_exceeded",
            "You've used today's Asha allowance. Asha Plus gives you more.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object PlusRequired : AssistantError("assistant.plus_required", "That needs Asha Plus.", ErrorKind.FORBIDDEN)

    data object VoiceTooLong : AssistantError(
        "assistant.voice_too_long",
        "Voice clips can be up to 60 seconds.",
        ErrorKind.INVALID,
    )

    data object ProposalExpired : AssistantError(
        "assistant.proposal_expired",
        "That proposal has expired.",
        ErrorKind.CONFLICT,
    )

    data object ModelUnavailable : AssistantError(
        "assistant.model_unavailable",
        "Asha can't answer right now. Try again.",
        ErrorKind.UPSTREAM,
    )

    /**
     * Asha's model is down (decision 2026-11-07: Claude only, no fallback model): the turn failed after its one quick
     * retry. The app shows Asha as down for maintenance, keeps the question, and clears it when A14 (or the socket's
     * `assistant` `status` frame) says she is available again; the user also gets one "Asha is back" push.
     */
    data object Unavailable : AssistantError(
        "assistant.unavailable",
        "Asha is down for maintenance at the moment. We'll let you know as soon as she's back.",
        ErrorKind.UPSTREAM,
    )

    /** A4 asked for speech in a language the server voice doesn't speak; the phone speaks it with its own voice. */
    data object LanguageNotSupported : AssistantError(
        "assistant.language_not_supported",
        "Asha's voice doesn't speak that language yet. Your phone can read the reply aloud.",
        ErrorKind.INVALID,
    )

    /** A13 asked for the preview of a voice that isn't on Asha's list (the app's list is stale: fetch A12 again). */
    data object VoiceNotFound : AssistantError(
        "assistant.voice_not_found",
        "That voice isn't available.",
        ErrorKind.NOT_FOUND,
    )

    /** The question to the phone was already answered or went stale; the app asks again. */
    data object TurnNotFound : AssistantError(
        "assistant.turn_not_found",
        "That question is no longer waiting.",
        ErrorKind.NOT_FOUND,
    )

    /** No conversation of this user has that id: never theirs, or deleted. */
    data object ConversationNotFound : AssistantError(
        "assistant.conversation_not_found",
        "That conversation doesn't exist.",
        ErrorKind.NOT_FOUND,
    )
}
