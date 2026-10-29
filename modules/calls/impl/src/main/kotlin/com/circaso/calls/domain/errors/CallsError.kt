package com.circaso.calls.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind

/** Calls' own codes (Calls → Error codes). */
sealed class CallsError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    data object NotFound : CallsError("calls.not_found", "We couldn't find that call.", ErrorKind.NOT_FOUND)

    data object NotAMember : CallsError("calls.not_a_member", "You're not in this chat.", ErrorKind.NOT_FOUND)

    data object AlreadyInCall : CallsError(
        "calls.already_in_call",
        "This chat already has a call going on. Join it instead.",
        ErrorKind.CONFLICT,
    )

    data object CallEnded : CallsError("calls.call_ended", "This call has ended.", ErrorKind.CONFLICT)

    data object CallFull : CallsError(
        "calls.call_full",
        "This call is full. At most 8 people can be in a call.",
        ErrorKind.CONFLICT,
    )

    data object NotInCall : CallsError("calls.not_in_call", "You're not in this call.", ErrorKind.CONFLICT)

    data object SessionMismatch : CallsError(
        "calls.session_mismatch",
        "That isn't your current session in this call. Join again.",
        ErrorKind.CONFLICT,
    )

    data object MediaUnavailable : CallsError(
        "calls.media_unavailable",
        "We couldn't connect the call right now. Try again.",
        ErrorKind.UPSTREAM,
    )
}
