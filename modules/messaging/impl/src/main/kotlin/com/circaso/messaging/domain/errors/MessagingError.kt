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

    /** Leaving or removing someone only makes sense in a group; a direct chat stays two people. */
    data object NotAGroup : MessagingError(
        "messaging.not_a_group",
        "That only works in a group.",
        ErrorKind.INVALID,
    )

    data object MessageTooLarge : MessagingError(
        "messaging.message_too_large",
        "A message can be at most 64 KB. Send files as chat media.",
        ErrorKind.INVALID,
    )

    /** A direct or `SENDER_KEY` send whose recipients aren't (other) members of the chat. */
    data object RecipientsMismatch : MessagingError(
        "messaging.recipients_mismatch",
        "The recipients don't match the chat's members. Load the chat again and retry.",
        ErrorKind.INVALID,
    )

    /** After 48 hours, or by someone other than the sender: the two look the same. */
    data object DeleteWindowPassed : MessagingError(
        "messaging.delete_window_passed",
        "Only the sender can delete a message for everyone, within 48 hours.",
        ErrorKind.CONFLICT,
    )

    /** AT1: an attachment is 1 byte to 32 MiB of ciphertext (decision 2026-10-30). */
    data object AttachmentTooLarge : MessagingError(
        "messaging.attachment_too_large",
        "An attachment can be at most 32 MB.",
        ErrorKind.INVALID,
    )

    /**
     * No such attachment, not ready yet, expired, or the caller may not see it (not the uploader for AT2, not in the
     * chat for AT3): all look the same, so nobody learns which attachments exist.
     */
    data object AttachmentNotFound : MessagingError(
        "messaging.attachment_not_found",
        "That attachment doesn't exist or is no longer available.",
        ErrorKind.NOT_FOUND,
    )

    /** AT2: nothing in storage yet, or not the size AT1 promised. Upload again, then complete. */
    data object AttachmentIncomplete : MessagingError(
        "messaging.attachment_incomplete",
        "The upload isn't complete. Upload the file again, then retry.",
        ErrorKind.INVALID,
    )

    /** Users' code, returned as-is when reach fails (Messaging → Error codes); it never says why. */
    data object NotReachable : MessagingError(
        "users.not_reachable",
        "You can't reach that person.",
        ErrorKind.FORBIDDEN,
    )
}
