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

    data object TooManyPrekeys : MessagingError(
        "messaging.too_many_prekeys",
        "At most 100 one-time prekeys can be kept at once.",
        ErrorKind.INVALID,
    )

    data object PrekeyIdExists : MessagingError(
        "messaging.prekey_id_exists",
        "One of those one-time prekey ids is already stored. Send new ids.",
        ErrorKind.CONFLICT,
    )

    class RateLimited(retryAfterSeconds: Long) :
        MessagingError(
            "messaging.rate_limited",
            "Too many requests. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    /** No such chat, or the caller isn't in it: the two look the same, so nobody learns which chats exist. */
    data object ChatNotFound : MessagingError(
        "messaging.chat_not_found",
        "That chat doesn't exist, or you're not in it.",
        ErrorKind.NOT_FOUND,
    )

    data object NotAdmin : MessagingError(
        "messaging.not_admin",
        "Only a group admin can do that.",
        ErrorKind.FORBIDDEN,
    )

    data object GroupFull : MessagingError(
        "messaging.group_full",
        "A group holds at most 1,000 people.",
        ErrorKind.CONFLICT,
    )

    data object GroupInvalid : MessagingError(
        "messaging.group_invalid",
        "The group's sealed name and avatar are missing or larger than 4 KB.",
        ErrorKind.INVALID,
    )

    data object VersionConflict : MessagingError(
        "messaging.version_conflict",
        "The group changed since you last loaded it. Load it again and retry.",
        ErrorKind.CONFLICT,
    )

    /**
     * The owner tried to leave a group that still has other members. Provisional: the spec doesn't say what happens
     * to a group when its owner leaves, so the leave is refused until ownership is handed over (M11); see the
     * `TODO(decision)` in `LeaveGroup`.
     */
    data object OwnerMustHandOver : MessagingError(
        "messaging.owner_must_hand_over",
        "Make someone else the group owner before you leave.",
        ErrorKind.CONFLICT,
    )

    /** Users' code, returned as-is when reach fails (Messaging → Error codes); it never says why. */
    data object NotReachable : MessagingError(
        "users.not_reachable",
        "You can't reach that person.",
        ErrorKind.FORBIDDEN,
    )
}
