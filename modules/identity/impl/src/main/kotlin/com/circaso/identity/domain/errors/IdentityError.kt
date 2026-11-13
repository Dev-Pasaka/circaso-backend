package com.circaso.identity.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind

/** Identity's own codes (Identity → Error codes). The shared `request.*` and `auth.*` codes are in `SharedError`. */
sealed class IdentityError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    data object NonceInvalid : IdentityError(
        "identity.nonce_invalid",
        "That sign-in request expired. Try again.",
        ErrorKind.INVALID,
    )

    data object ProviderTokenInvalid : IdentityError(
        "identity.provider_token_invalid",
        "We couldn't verify that sign-in.",
        ErrorKind.UNAUTHORIZED,
    )

    data object AccountSuspended : IdentityError(
        "identity.account_suspended",
        "This account is suspended.",
        ErrorKind.FORBIDDEN,
    )

    data object AccountBlocked : IdentityError(
        "identity.account_blocked",
        "This account is blocked.",
        ErrorKind.FORBIDDEN,
    )

    data object AccountDeleted : IdentityError(
        "identity.account_deleted",
        "This account was deleted.",
        ErrorKind.NOT_FOUND,
    )

    data object RecoveryPending : IdentityError(
        "identity.recovery_pending",
        "This account is being recovered.",
        ErrorKind.CONFLICT,
    )

    data object PhoneInvalid : IdentityError(
        "identity.phone_invalid",
        "That isn't a valid phone number.",
        ErrorKind.INVALID,
    )

    data object PhoneTaken : IdentityError(
        "identity.phone_taken",
        "This number already has a Circaso account.",
        ErrorKind.CONFLICT,
    )

    data object PhoneMismatch : IdentityError(
        "identity.phone_mismatch",
        "That isn't the number on your account.",
        ErrorKind.CONFLICT,
    )

    class CodeInvalid(attemptsLeft: Int) :
        IdentityError(
            "identity.code_invalid",
            "That code is wrong. $attemptsLeft tries left.",
            ErrorKind.INVALID,
        )

    data object CodeExpired : IdentityError(
        "identity.code_expired",
        "That code has expired. Request a new one.",
        ErrorKind.INVALID,
    )

    class CodeLocked(retryAfterSeconds: Long) :
        IdentityError(
            "identity.code_locked",
            "Too many wrong codes. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object SmsUnavailable : IdentityError(
        "identity.sms_unavailable",
        "We couldn't send the code. Try again.",
        ErrorKind.UPSTREAM,
    )

    data object CarrierUnsupported : IdentityError(
        "identity.carrier_unsupported",
        "One-tap verification isn't available. Use a code instead.",
        ErrorKind.INVALID,
    )

    data object DeviceAttestationFailed : IdentityError(
        "identity.device_attestation_failed",
        "Circaso can't run on this phone.",
        ErrorKind.FORBIDDEN,
    )

    data object DeviceProofInvalid : IdentityError(
        "identity.device_proof_invalid",
        "This phone couldn't be verified.",
        ErrorKind.UNAUTHORIZED,
    )

    data object SessionRevoked : IdentityError(
        "identity.session_revoked",
        "You were signed out.",
        ErrorKind.UNAUTHORIZED,
    )

    data object ProviderLast : IdentityError(
        "identity.provider_last",
        "You need at least one sign-in account.",
        ErrorKind.CONFLICT,
    )

    data object ProviderInUse : IdentityError(
        "identity.provider_in_use",
        "That sign-in belongs to another account.",
        ErrorKind.CONFLICT,
    )

    data object RecoveryNotEligible : IdentityError(
        "identity.recovery_not_eligible",
        "This account can't be restored by support.",
        ErrorKind.FORBIDDEN,
    )

    data object ReleaseNotAllowed : IdentityError(
        "identity.release_not_allowed",
        "This number can't be released. Ask support to recover the account.",
        ErrorKind.FORBIDDEN,
    )

    /** Any way into an `app` session for a support account (decision 2026-10-31): sign-in, phone, device, refresh. */
    data object SupportAccountNotOnMobile : IdentityError(
        "identity.support_account_not_on_mobile",
        "This is a Circaso support account. It can't be used in the Circaso app.",
        ErrorKind.FORBIDDEN,
    )

    /** Staff asked about an account that doesn't exist (the ops gateway; decision 2026-11-08). */
    data object AccountNotFound : IdentityError(
        "identity.account_not_found",
        "There is no such account.",
        ErrorKind.NOT_FOUND,
    )

    /** Only an `ACTIVE` account can be suspended. */
    data object AccountNotActive : IdentityError(
        "identity.account_not_active",
        "This account isn't active, so it can't be suspended.",
        ErrorKind.CONFLICT,
    )

    /** Only a `SUSPENDED` account can be given back. */
    data object AccountNotSuspended : IdentityError(
        "identity.account_not_suspended",
        "This account isn't suspended.",
        ErrorKind.CONFLICT,
    )

    /** Saved names (2026-11-12): `baseVersion` isn't the stored version (0 when none): another phone saved first. */
    data object SavedNamesConflict : IdentityError(
        "identity.saved_names_conflict",
        "Your saved names changed on another phone. Read them again before you save.",
        ErrorKind.CONFLICT,
    )

    /** Saved names are sealed under the key backup root, so they can only be saved while key backup is on. */
    data object KeyBackupOff : IdentityError(
        "identity.key_backup_off",
        "Key backup is off. Turn it on first.",
        ErrorKind.CONFLICT,
    )

    data object SavedNamesCiphertextInvalid : IdentityError(
        "identity.saved_names_ciphertext_invalid",
        "Your saved names couldn't be backed up.",
        ErrorKind.INVALID,
    )

    data object SavedNamesBaseVersionInvalid : IdentityError(
        "identity.saved_names_base_version_invalid",
        "Your saved names couldn't be backed up.",
        ErrorKind.INVALID,
    )
}
