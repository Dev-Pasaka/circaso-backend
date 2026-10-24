package com.circaso.users.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind

/** Users' own codes (Users → Error codes). */
sealed class UsersError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    class ProfileInvalid(field: String, why: String) :
        UsersError(
            "users.profile_invalid",
            "That profile isn't valid.",
            ErrorKind.INVALID,
            ErrorDetails(
                fields =
                    mapOf(field to why),
            ),
        )

    data object VersionConflict : UsersError(
        "users.version_conflict",
        "Your profile changed. Reload it and try again.",
        ErrorKind.CONFLICT,
    )

    data object AvatarInvalid : UsersError(
        "users.avatar_invalid",
        "That picture can't be used. Use a JPEG, PNG or WebP up to 5 MB.",
        ErrorKind.INVALID,
    )

    data object HandleInvalid : UsersError("users.handle_invalid", "That @handle isn't allowed.", ErrorKind.INVALID)

    data object HandleTaken : UsersError("users.handle_taken", "That @handle is taken.", ErrorKind.CONFLICT)

    data object HandleCooldown : UsersError(
        "users.handle_cooldown",
        "You can change your @handle once every 30 days.",
        ErrorKind.CONFLICT,
    )

    data object HandleNotFound : UsersError("users.handle_not_found", "No one has that @handle.", ErrorKind.NOT_FOUND)

    data object NotReachable : UsersError("users.not_reachable", "You can't reach that person.", ErrorKind.FORBIDDEN)

    data object ContactNotFound : UsersError(
        "users.contact_not_found",
        "That person isn't in your contacts.",
        ErrorKind.NOT_FOUND,
    )

    data object InviteInvalid : UsersError("users.invite_invalid", "That invite has expired.", ErrorKind.NOT_FOUND)

    data object SyncTooLarge : UsersError(
        "users.sync_too_large",
        "Send at most 1,000 numbers at a time.",
        ErrorKind.INVALID,
    )

    data object CannotBlockSelf : UsersError("users.cannot_block_self", "You can't block yourself.", ErrorKind.INVALID)
}
