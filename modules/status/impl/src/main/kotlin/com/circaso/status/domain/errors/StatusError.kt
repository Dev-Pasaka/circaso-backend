package com.circaso.status.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind

/** Status' own codes (Moments API → Errors). */
sealed class StatusError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    /** 1 to 1,024 distinct people, never the poster; key copies only for people in the audience, one each. */
    data object AudienceInvalid : StatusError(
        "status.audience_invalid",
        "Choose between 1 and 1,024 people to share this moment with.",
        ErrorKind.INVALID,
    )

    data object MomentTooLarge : StatusError(
        "status.moment_too_large",
        "This moment is too large to share.",
        ErrorKind.INVALID,
    )

    /** ST1's size or kind, ST2's object at another size than promised, or more than one file on a moment. */
    data object MediaInvalid : StatusError(
        "status.media_invalid",
        "This file can't be shared as a moment.",
        ErrorKind.INVALID,
    )

    /** Also when the caller may not have it, so nobody learns that it exists. */
    data object MediaNotFound : StatusError(
        "status.media_not_found",
        "That file is no longer available.",
        ErrorKind.NOT_FOUND,
    )

    /** Missing, expired, deleted or not shown to the caller: they all look the same. */
    data object MomentNotFound : StatusError(
        "status.moment_not_found",
        "That moment is no longer available.",
        ErrorKind.NOT_FOUND,
    )

    data object NotPoster : StatusError(
        "status.not_poster",
        "Only the person who shared this moment can do that.",
        ErrorKind.FORBIDDEN,
    )

    /** ST5's `reaction`: one emoji, 1 to 16 UTF-8 bytes, no letters, digits or spaces. */
    data object ReactionInvalid : StatusError(
        "status.reaction_invalid",
        "Choose one emoji to react with.",
        ErrorKind.INVALID,
    )

    class TooManyMoments(retryAfterSeconds: Long) :
        StatusError(
            "status.too_many_moments",
            "You've shared a lot of moments today. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )
}
