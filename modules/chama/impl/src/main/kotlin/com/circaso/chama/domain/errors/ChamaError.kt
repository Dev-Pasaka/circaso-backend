package com.circaso.chama.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError

/** Chama's own codes (Chama → Error codes). */
sealed class ChamaError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    data object NotAMember : ChamaError("chama.not_a_member", "You're not in this group.", ErrorKind.NOT_FOUND)

    data object NotAdmin : ChamaError("chama.not_admin", "Only the group's admins can do that.", ErrorKind.FORBIDDEN)

    data object NotSigner : ChamaError(
        "chama.not_signer",
        "Only the group wallet's owner can pay this out.",
        ErrorKind.FORBIDDEN,
    )

    data object NotOwner : ChamaError(
        "chama.not_owner",
        "Only the group's owner can change who approves payouts.",
        ErrorKind.FORBIDDEN,
    )

    data object NoWallet : ChamaError(
        "chama.no_wallet",
        "This group has no wallet yet. Set one up first.",
        ErrorKind.CONFLICT,
    )

    data object NoGoal : ChamaError("chama.no_goal", "This group has no shared goal yet.", ErrorKind.NOT_FOUND)

    class GoalInvalid(field: String, problem: String) :
        ChamaError(
            "chama.goal_invalid",
            "Check the goal: $field $problem.",
            ErrorKind.INVALID,
            ErrorDetails(fields = listOf(FieldError(field, problem))),
        )

    data object AmountInvalid : ChamaError("chama.amount_invalid", "That amount isn't right.", ErrorKind.INVALID)

    data object PayeeInvalid : ChamaError(
        "chama.payee_invalid",
        "Say who to pay: a person or an address, not both.",
        ErrorKind.INVALID,
    )

    data object RequestNotFound : ChamaError(
        "chama.request_not_found",
        "We couldn't find that request.",
        ErrorKind.NOT_FOUND,
    )

    data object RequestClosed : ChamaError(
        "chama.request_closed",
        "This request is no longer open.",
        ErrorKind.CONFLICT,
    )

    data object AlreadyDecided : ChamaError(
        "chama.already_decided",
        "You've already decided on this request.",
        ErrorKind.CONFLICT,
    )

    data object NotApproved : ChamaError("chama.not_approved", "This request isn't approved yet.", ErrorKind.CONFLICT)

    data object TransferMismatch : ChamaError(
        "chama.transfer_mismatch",
        "That payment doesn't match the request (who it's from, who it's to, the amount or the chat).",
        ErrorKind.INVALID,
    )
}
