package com.circaso.opsgateway

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind

/** The ops gateway's own refusals (the routes' `ops.sign_in_required` and `ops.role_forbidden` are shared-web's). */
sealed class OpsGatewayError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    /** A token was sent but isn't a good one: bad signature, wrong audience or issuer, expired, not yet valid. */
    data object AccessTokenInvalid : OpsGatewayError(
        "ops.access_token_invalid",
        "Your sign-in isn't valid any more. Sign in again.",
        ErrorKind.UNAUTHORIZED,
    )

    /** Signed in to Cloudflare Access, but not on the staff list (or switched off). */
    data object NotStaff : OpsGatewayError(
        "ops.not_staff",
        "You're signed in, but not on Circaso's staff list. Ask an admin to add you.",
        ErrorKind.FORBIDDEN,
    )

    data object DevLoginDisabled : OpsGatewayError(
        "ops.dev_login_disabled",
        "The dev login is off here.",
        ErrorKind.NOT_FOUND,
    )

    /** A wrong secret and an email that isn't staff look the same, so the form can't be used to find staff emails. */
    data object DevLoginRefused : OpsGatewayError(
        "ops.dev_login_refused",
        "That secret or email isn't right.",
        ErrorKind.UNAUTHORIZED,
    )

    data object ReturnUrlInvalid : OpsGatewayError(
        "ops.return_url_invalid",
        "That return address isn't one of the admin's.",
        ErrorKind.INVALID,
    )

    /** Access certificates couldn't be fetched: the caller may retry. */
    data object AccessUnavailable : OpsGatewayError(
        "ops.access_unavailable",
        "Sign-in can't be checked right now. Try again in a moment.",
        ErrorKind.UNAVAILABLE,
    )
}
