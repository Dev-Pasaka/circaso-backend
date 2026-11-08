package com.circaso.ops.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind

/** The ops module's own codes. The two every staff route can answer (`ops.sign_in_required`, `ops.role_forbidden`) are shared-web's. */
sealed class OpsError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    /** Suspending or giving an account back needs a reason, 3 to 500 characters: it is what the audit log keeps. */
    data object ReasonRequired : OpsError(
        "ops.reason_required",
        "Say why, in a few words (3 to 500 characters).",
        ErrorKind.INVALID,
    )

    data object UserNotFound : OpsError("ops.user_not_found", "There is no such user.", ErrorKind.NOT_FOUND)

    data object TransferNotFound : OpsError(
        "ops.transfer_not_found",
        "There is no such transaction.",
        ErrorKind.NOT_FOUND,
    )

    data object GroupNotFound : OpsError("ops.group_not_found", "There is no such group.", ErrorKind.NOT_FOUND)

    data object StaffNotFound : OpsError("ops.staff_not_found", "There is no such staff member.", ErrorKind.NOT_FOUND)

    data object StaffExists : OpsError(
        "ops.staff_exists",
        "That email is already on the staff list.",
        ErrorKind.CONFLICT,
    )

    data object EmailInvalid : OpsError("ops.email_invalid", "That isn't an email address.", ErrorKind.INVALID)

    /** Nobody changes their own role or switches themselves off: another admin has to. */
    data object CannotChangeSelf : OpsError(
        "ops.cannot_change_self",
        "Ask another admin to change your own role or access.",
        ErrorKind.CONFLICT,
    )

    /** A bootstrap admin comes from `OPS_BOOTSTRAP_ADMINS` and is changed there, not in the portal. */
    data object StaffFromConfig : OpsError(
        "ops.staff_from_config",
        "This admin comes from the server's settings and can't be changed here.",
        ErrorKind.CONFLICT,
    )

    /** A ticket can only be assigned to active staff who work tickets (SUPPORT or ADMIN). */
    data object AssigneeInvalid : OpsError(
        "ops.assignee_invalid",
        "Tickets can only go to active support staff or admins.",
        ErrorKind.INVALID,
    )

    /** The staff list changed since it was read; reload and try again. */
    data object StaffChanged : OpsError(
        "ops.staff_changed",
        "Someone else changed this staff member just now. Reload and try again.",
        ErrorKind.CONFLICT,
    )
}
