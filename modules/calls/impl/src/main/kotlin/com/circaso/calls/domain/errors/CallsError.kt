package com.circaso.calls.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError

/** Calls' own codes (Calls → Error codes). */
sealed class CallsError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
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

    data object NotAGroup : CallsError(
        "calls.not_a_group",
        "Only a group call can ring more people.",
        ErrorKind.CONFLICT,
    )

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

    /** A call report with a field missing or too long, too many events or samples, or a body over 64 KiB. */
    class ReportInvalid(field: String, problem: String) :
        CallsError(
            "calls.report_invalid",
            "That call report isn't valid.",
            ErrorKind.INVALID,
            ErrorDetails(fields = listOf(FieldError(field, problem))),
        )

    /** More than 30 call reports from one person in an hour; [retryAfterSeconds] says when to try again. */
    class ReportRateLimited(retryAfterSeconds: Long) :
        CallsError(
            "calls.report_rate_limited",
            "Too many call reports. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )
}
