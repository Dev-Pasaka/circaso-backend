package com.circaso.share.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind

/** Share's own codes (share.md → Error codes). */
sealed class ShareError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    /** A kind that isn't known, its id missing or malformed, a currency that isn't a code, days out of range. */
    data object RequestInvalid : ShareError(
        "share.request_invalid",
        "That can't be shared as a link.",
        ErrorKind.INVALID,
    )

    /** The payment, group or address isn't the caller's, or doesn't exist: they look the same. */
    data object SourceNotFound : ShareError(
        "share.source_not_found",
        "We couldn't find that to share.",
        ErrorKind.NOT_FOUND,
    )

    /** A payment that was never signed or expired, a "pay me" without a @handle, a group without a target. */
    data object NotShareable : ShareError(
        "share.not_shareable",
        "This can't be shared yet.",
        ErrorKind.INVALID,
    )

    /** Unknown, revoked, ended, or (for SH3) someone else's link: they all look the same. */
    data object LinkNotFound : ShareError(
        "share.link_not_found",
        "This link is no longer available.",
        ErrorKind.NOT_FOUND,
    )

    /** users couldn't make the invite the link carries. */
    data object Unavailable : ShareError(
        "share.unavailable",
        "Sharing isn't available right now. Try again in a moment.",
        ErrorKind.UNAVAILABLE,
    )
}
