package com.circaso.spaces.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError

/** Spaces' own codes (Spaces API → Errors). The app keys its copy on [code]. */
sealed class SpacesError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    /** Missing, or not visible to the caller: they look the same. */
    data object SpaceNotFound : SpacesError(
        "spaces.space_not_found",
        "We couldn't find that space.",
        ErrorKind.NOT_FOUND,
    )

    /** Deleted by its owner, or removed by Circaso (SS6d). */
    class SpaceUnavailable(reason: String, name: String) :
        SpacesError(
            "spaces.space_unavailable",
            "This space isn't available.",
            ErrorKind.NOT_FOUND,
            ErrorDetails(fields = listOf(FieldError("reason", reason), FieldError("name", name))),
        )

    data object HandleInvalid : SpacesError(
        "spaces.handle_invalid",
        "A handle is 3 to 30 letters, numbers, dots or underscores, starting with a letter.",
        ErrorKind.INVALID,
    )

    class HandleTaken(suggestions: List<String>) :
        SpacesError(
            "spaces.handle_taken",
            "That handle is taken.",
            ErrorKind.CONFLICT,
            ErrorDetails(fields = listOf(FieldError("suggestions", suggestions.joinToString(",")))),
        )

    class DetailsInvalid(field: String, problem: String) :
        SpacesError(
            "spaces.details_invalid",
            "Check the space's details.",
            ErrorKind.INVALID,
            ErrorDetails(fields = listOf(FieldError(field, problem))),
        )

    data object PriceInvalid : SpacesError(
        "spaces.price_invalid",
        "A members-only space needs a price from 0.10 to 10,000 USDC; other spaces have none.",
        ErrorKind.INVALID,
    )

    data object TeamInvalid : SpacesError(
        "spaces.team_invalid",
        "Check the people you added to the team.",
        ErrorKind.INVALID,
    )

    class AccountTooNew(availableAt: String) :
        SpacesError(
            "spaces.account_too_new",
            "New Circaso accounts wait 7 days before starting a space.",
            ErrorKind.FORBIDDEN,
            ErrorDetails(fields = listOf(FieldError("availableAt", availableAt))),
        )

    class TooManySpaces(retryAfterSeconds: Long) :
        SpacesError(
            "spaces.too_many_spaces",
            "You can start at most 3 spaces a day.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object AccountRestricted : SpacesError(
        "spaces.account_restricted",
        "Your account can't do this right now.",
        ErrorKind.FORBIDDEN,
    )

    data object NotOwner : SpacesError(
        "spaces.not_owner",
        "Only the space's owner can do that.",
        ErrorKind.FORBIDDEN,
    )

    data object NotAdmin : SpacesError(
        "spaces.not_admin",
        "Only the space's admins can do that.",
        ErrorKind.FORBIDDEN,
    )

    data object NotModerator : SpacesError(
        "spaces.not_moderator",
        "Only the space's moderators and admins can do that.",
        ErrorKind.FORBIDDEN,
    )

    data object CannotModerate : SpacesError(
        "spaces.cannot_moderate",
        "You can't do that to this person.",
        ErrorKind.FORBIDDEN,
    )

    data object NotMember : SpacesError(
        "spaces.not_member",
        "Join this space first.",
        ErrorKind.FORBIDDEN,
    )

    data object MembersOnly : SpacesError(
        "spaces.members_only",
        "This is for the space's members.",
        ErrorKind.FORBIDDEN,
    )

    data object MembershipEnded : SpacesError(
        "spaces.membership_ended",
        "Your membership has ended. Rejoin to take part again.",
        ErrorKind.FORBIDDEN,
    )

    data object Banned : SpacesError(
        "spaces.banned",
        "You can't take part in this space.",
        ErrorKind.FORBIDDEN,
    )

    data object PaymentRequired : SpacesError(
        "spaces.payment_required",
        "This space is for paying members.",
        ErrorKind.FORBIDDEN,
    )

    data object InviteRequired : SpacesError(
        "spaces.invite_required",
        "This space is invite only. Ask to join instead.",
        ErrorKind.FORBIDDEN,
    )

    data object NotInviteOnly : SpacesError(
        "spaces.not_invite_only",
        "Anyone can join this space.",
        ErrorKind.CONFLICT,
    )

    data object NotPaidSpace : SpacesError(
        "spaces.not_paid_space",
        "This space has no price to pay.",
        ErrorKind.CONFLICT,
    )

    class AlreadyMember(renewFrom: String? = null) :
        SpacesError(
            "spaces.already_member",
            "You're already in this space.",
            ErrorKind.CONFLICT,
            renewFrom?.let { ErrorDetails(fields = listOf(FieldError("renewFrom", it))) },
        )

    data object WalletNotReady : SpacesError(
        "spaces.wallet_not_ready",
        "This space's wallet isn't ready yet.",
        ErrorKind.CONFLICT,
    )

    data object WalletNotEmpty : SpacesError(
        "spaces.wallet_not_empty",
        "Empty the space wallet before deleting the space.",
        ErrorKind.CONFLICT,
    )

    data object OwnerCannotLeave : SpacesError(
        "spaces.owner_cannot_leave",
        "The owner can't leave their own space.",
        ErrorKind.CONFLICT,
    )

    class RequestTooSoon(canAskAgainAt: String) :
        SpacesError(
            "spaces.request_too_soon",
            "You can ask to join this space again later.",
            ErrorKind.CONFLICT,
            ErrorDetails(fields = listOf(FieldError("canAskAgainAt", canAskAgainAt))),
        )

    data object RequestNotFound : SpacesError(
        "spaces.request_not_found",
        "That request is no longer waiting.",
        ErrorKind.NOT_FOUND,
    )

    data object InviteNotFound : SpacesError(
        "spaces.invite_not_found",
        "That invite is no longer available.",
        ErrorKind.NOT_FOUND,
    )

    data object InviteInvalid : SpacesError(
        "spaces.invite_invalid",
        "Invite 1 to 50 people to a public or invite-only space.",
        ErrorKind.INVALID,
    )

    data object PostingRestricted : SpacesError(
        "spaces.posting_restricted",
        "Only admins post in this space.",
        ErrorKind.FORBIDDEN,
    )

    class Muted(mutedUntil: String) :
        SpacesError(
            "spaces.muted",
            "An admin muted you in this space.",
            ErrorKind.FORBIDDEN,
            ErrorDetails(fields = listOf(FieldError("mutedUntil", mutedUntil))),
        )

    class PostInvalid(problem: String) :
        SpacesError(
            "spaces.post_invalid",
            "This post can't be sent.",
            ErrorKind.INVALID,
            ErrorDetails(fields = listOf(FieldError("post", problem))),
        )

    data object PostNotFound : SpacesError(
        "spaces.post_not_found",
        "That post is no longer available.",
        ErrorKind.NOT_FOUND,
    )

    class TooManyPosts(retryAfterSeconds: Long) :
        SpacesError(
            "spaces.too_many_posts",
            "You're posting a lot. Try again in a moment.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object ReactionInvalid : SpacesError(
        "spaces.reaction_invalid",
        "Choose one emoji to react with.",
        ErrorKind.INVALID,
    )

    data object MediaInvalid : SpacesError(
        "spaces.media_invalid",
        "This file can't be used here.",
        ErrorKind.INVALID,
    )

    data object MediaNotFound : SpacesError(
        "spaces.media_not_found",
        "That file is no longer available.",
        ErrorKind.NOT_FOUND,
    )

    data object ReportInvalid : SpacesError(
        "spaces.report_invalid",
        "Choose what's wrong with it.",
        ErrorKind.INVALID,
    )

    data object ReportNotFound : SpacesError(
        "spaces.report_not_found",
        "There's no open report on that post.",
        ErrorKind.NOT_FOUND,
    )

    class TooManyReports(retryAfterSeconds: Long) :
        SpacesError(
            "spaces.too_many_reports",
            "You've sent a lot of reports today. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )
}
