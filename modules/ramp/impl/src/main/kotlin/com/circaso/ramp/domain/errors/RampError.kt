package com.circaso.ramp.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError

/** The ramp's own codes (Ramp → Error codes). */
sealed class RampError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    data object Unavailable : RampError(
        "ramp.unavailable",
        "Buying with M-Pesa isn't open to you yet.",
        ErrorKind.FORBIDDEN,
    )

    data object KycRequired : RampError(
        "ramp.kyc_required",
        "Verify your identity before you buy or sell.",
        ErrorKind.FORBIDDEN,
    )

    data object AccountRestricted : RampError(
        "ramp.account_restricted",
        "Your account can't trade right now.",
        ErrorKind.FORBIDDEN,
    )

    data object WalletRequired : RampError(
        "ramp.wallet_required",
        "Set up your wallet on this chain first.",
        ErrorKind.CONFLICT,
    )

    data object ChainUnsupported : RampError(
        "ramp.chain_unsupported",
        "Buying isn't offered on that chain.",
        ErrorKind.INVALID,
    )

    data object OffRampUnavailable : RampError(
        "ramp.offramp_unavailable",
        "Selling isn't open yet.",
        ErrorKind.CONFLICT,
    )

    data object AmountInvalid : RampError("ramp.amount_invalid", "That amount isn't right.", ErrorKind.INVALID)

    data object LimitExceeded : RampError(
        "ramp.limit_exceeded",
        "That amount is outside your limits.",
        ErrorKind.INVALID,
    )

    data object QuoteNotFound : RampError("ramp.quote_not_found", "We couldn't find that quote.", ErrorKind.NOT_FOUND)

    data object QuoteExpired : RampError(
        "ramp.quote_expired",
        "That price has expired. Ask for a new one.",
        ErrorKind.CONFLICT,
    )

    data object PhoneNotYours : RampError(
        "ramp.phone_not_yours",
        "Pay from the number your account is verified with.",
        ErrorKind.INVALID,
    )

    data object OrderInProgress : RampError(
        "ramp.order_in_progress",
        "Finish your current order first.",
        ErrorKind.CONFLICT,
    )

    data object NoMerchantAvailable : RampError(
        "ramp.no_merchant_available",
        "Nobody can fill that amount right now. Try a smaller amount or try again soon.",
        ErrorKind.CONFLICT,
    )

    class TooManyPrompts(retryAfterSeconds: Long) :
        RampError(
            "ramp.too_many_prompts",
            "Too many payment requests. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object OrderNotFound : RampError("ramp.order_not_found", "We couldn't find that order.", ErrorKind.NOT_FOUND)

    data object OrderNotCancellable : RampError(
        "ramp.order_not_cancellable",
        "This order can't be cancelled now.",
        ErrorKind.CONFLICT,
    )

    data object CollectionsUnavailable : RampError(
        "ramp.collections_unavailable",
        "We couldn't reach M-Pesa. Try again in a moment.",
        ErrorKind.UPSTREAM,
    )

    data object CustodyUnavailable : RampError(
        "ramp.custody_unavailable",
        "We couldn't reach the wallet service. Try again in a moment.",
        ErrorKind.UPSTREAM,
    )

    data object PricingUnavailable : RampError(
        "ramp.pricing_unavailable",
        "We can't price that right now. Try again in a moment.",
        ErrorKind.UPSTREAM,
    )

    // ---- the merchant portal ------------------------------------------------------------------------------

    data object SessionInvalid : RampError(
        "ramp.session_invalid",
        "Sign in to continue.",
        ErrorKind.UNAUTHORIZED,
    )

    data object CodeInvalid : RampError(
        "ramp.code_invalid",
        "That code isn't right, or it has expired.",
        ErrorKind.INVALID,
    )

    class CodeLocked(retryAfterSeconds: Long) :
        RampError(
            "ramp.code_locked",
            "Too many tries. Wait a little, then ask for a new code.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object EmailUnavailable : RampError(
        "ramp.email_unavailable",
        "We couldn't send the code. Try again in a moment.",
        ErrorKind.UPSTREAM,
    )

    data object MerchantNotFound : RampError(
        "ramp.merchant_not_found",
        "We couldn't find that merchant.",
        ErrorKind.NOT_FOUND,
    )

    data object MerchantNotApproved : RampError(
        "ramp.merchant_not_approved",
        "Your account isn't approved to trade.",
        ErrorKind.FORBIDDEN,
    )

    data object MerchantNotReady : RampError(
        "ramp.merchant_not_ready",
        "This merchant hasn't applied yet.",
        ErrorKind.CONFLICT,
    )

    data object MerchantSetupIncomplete : RampError(
        "ramp.merchant_setup_incomplete",
        "Finish setting up first: a funded float wallet and a payment account.",
        ErrorKind.CONFLICT,
    )

    data object PinInvalid : RampError("ramp.pin_invalid", "That PIN isn't right.", ErrorKind.INVALID)

    class PinLocked(retryAfterSeconds: Long) :
        RampError(
            "ramp.pin_locked",
            "Too many wrong PINs. Try again later.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
        )

    data object CredentialsRejected : RampError(
        "ramp.credentials_rejected",
        "PayHero didn't accept that username, password or channel.",
        ErrorKind.INVALID,
    )

    /** The server has no (valid) `RAMP_CREDENTIALS_MASTER_KEY`: nothing can be sealed or unlocked on it. */
    data object VaultUnavailable : RampError(
        "ramp.vault_unavailable",
        "Payment accounts can't be stored or unlocked right now.",
        ErrorKind.UPSTREAM,
    )

    // ---- staff --------------------------------------------------------------------------------------------

    class SettingsInvalid(field: String, problem: String) :
        RampError(
            "ramp.settings_invalid",
            "Those settings aren't valid: $field $problem.",
            ErrorKind.INVALID,
            ErrorDetails(fields = listOf(FieldError(field, problem))),
        )

    data object SettingsChanged : RampError(
        "ramp.settings_changed",
        "Someone else changed these settings. Read them again.",
        ErrorKind.CONFLICT,
    )
}
