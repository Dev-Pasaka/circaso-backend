package com.circaso.messaging.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind

/** Messaging's own codes (Messaging → Error codes), added as their slices arrive. */
sealed class MessagingError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    data object KeysNotFound : MessagingError(
        "messaging.keys_not_found",
        "That person hasn't set up encrypted chat yet.",
        ErrorKind.NOT_FOUND,
    )

    data object KeysProofInvalid : MessagingError(
        "messaging.keys_proof_invalid",
        "This phone couldn't prove it's yours. Check the date and time, then try again.",
        ErrorKind.FORBIDDEN,
    )

    class RateLimited(retryAfterSeconds: Long) :
        MessagingError(
            "messaging.rate_limited",
            "Too many requests. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    /** Users' code, returned as-is when reach fails (Messaging → Error codes); it never says why. */
    data object NotReachable : MessagingError(
        "users.not_reachable",
        "You can't reach that person.",
        ErrorKind.FORBIDDEN,
    )
}
