package com.circaso.support.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError

/** Support's own codes (support.md → Error codes). The messages are the user's; the app may show them as they are. */
sealed class SupportError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    /** No such ticket, or someone else's: the two look the same on purpose. */
    data object TicketNotFound : SupportError(
        "support.ticket_not_found",
        "We couldn't find that ticket.",
        ErrorKind.NOT_FOUND,
    )

    data object SubjectInvalid : SupportError(
        "support.subject_invalid",
        "Give your ticket a subject of 3 to 120 characters.",
        ErrorKind.INVALID,
    )

    data object DescriptionInvalid : SupportError(
        "support.description_invalid",
        "Tell us what happened in 10 to 4,000 characters.",
        ErrorKind.INVALID,
    )

    data object BodyInvalid : SupportError(
        "support.body_invalid",
        "A reply is 1 to 4,000 characters.",
        ErrorKind.INVALID,
    )

    data object CategoryInvalid : SupportError(
        "support.category_invalid",
        "Choose what your ticket is about.",
        ErrorKind.INVALID,
    )

    /**
     * A status staff may not set by hand: `PENDING_USER` means "we replied and wait on you", so only a staff reply
     * sets it, and the queue's "waiting on" stays true.
     */
    data object StatusInvalid : SupportError(
        "support.status_invalid",
        "That status can't be set directly.",
        ErrorKind.INVALID,
    )

    /** A transfer the user neither sent, received nor signed, or a chat they aren't in (or neither exists). */
    data object ContextInvalid : SupportError(
        "support.context_invalid",
        "That payment or chat isn't one of yours.",
        ErrorKind.INVALID,
    )

    data object TicketClosed : SupportError(
        "support.ticket_closed",
        "This ticket is closed. Open a new one if you still need help.",
        ErrorKind.CONFLICT,
    )

    /** More new tickets in a day than the cap (`SUPPORT_TICKETS_PER_DAY`); [retryAfterSeconds] says when to try. */
    class RateLimited(retryAfterSeconds: Long) :
        SupportError(
            "support.rate_limited",
            "You've opened a lot of tickets today. Reply on one you have, or try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    /** A diagnostic log with a field missing or too long (SU5); [field] names it. */
    class LogInvalid(field: String, problem: String) :
        SupportError(
            "support.log_invalid",
            "That diagnostic log can't be sent.",
            ErrorKind.INVALID,
            ErrorDetails(fields = listOf(FieldError(field, problem))),
        )

    /** More diagnostic logs in an hour than the cap; [retryAfterSeconds] says when to try. */
    class LogRateLimited(retryAfterSeconds: Long) :
        SupportError(
            "support.log_rate_limited",
            "You've sent a few logs already. Try again in a little while.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    /** No such diagnostic log, or it expired. */
    data object LogNotFound : SupportError(
        "support.log_not_found",
        "There's no diagnostic log with this id.",
        ErrorKind.NOT_FOUND,
    )
}
