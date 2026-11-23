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

    /** The user, or the identity they verified with, is barred from the ramp. */
    data object Blocked : RampError(
        "ramp.blocked",
        "You can't buy or sell here. Contact support if you think this is a mistake.",
        ErrorKind.FORBIDDEN,
    )

    /** The identity provider can't be reached right now. */
    data object KycUnavailable : RampError(
        "ramp.kyc_unavailable",
        "We couldn't start your verification. Try again in a moment.",
        ErrorKind.UPSTREAM,
    )

    /**
     * Identity checks can't run on this network yet: the provider's keys for its environment aren't on the server,
     * no workflow is named in the settings, or the server can't compute identity keys.
     */
    data object KycNotConfigured : RampError(
        "ramp.kyc_not_configured",
        "Identity verification isn't available yet. Try again later.",
        ErrorKind.UNAVAILABLE,
    )

    class KycAttemptsExceeded(retryAfterSeconds: Long) :
        RampError(
            "ramp.kyc_attempts_exceeded",
            "You've tried to verify too many times today. Try again tomorrow.",
            ErrorKind.RATE_LIMITED,
            ErrorDetails(retryAfterSeconds = retryAfterSeconds),
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

    /** The provider refused the prompt for every merchant who could have taken the order; nothing was sent. */
    data object MerchantUnavailable : RampError(
        "ramp.merchant_unavailable",
        "We couldn't start this payment right now. Nothing was taken. Please try again in a moment.",
        ErrorKind.CONFLICT,
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

    /** RM6 with the provider's service balance under the network's floor: the same code, the field says which step. */
    data object ProviderBalanceLow : RampError(
        "ramp.merchant_setup_incomplete",
        "Top up your service wallet at your payment provider before you go online.",
        ErrorKind.CONFLICT,
        ErrorDetails(fields = listOf(FieldError("provider_balance", "below the minimum needed to send prompts"))),
    )

    /** RM6 before the merchant's identity is verified on a network that requires it. */
    data object MerchantKycMissing : RampError(
        "ramp.merchant_setup_incomplete",
        "Verify your identity before you go online.",
        ErrorKind.CONFLICT,
        ErrorDetails(fields = listOf(FieldError("kyc", "identity not verified"))),
    )

    /** Staff approving a merchant whose identity isn't verified where a network that is open requires it. */
    class MerchantKycRequired(networks: String) :
        RampError(
            "ramp.merchant_kyc_required",
            "This merchant hasn't passed identity verification yet ($networks).",
            ErrorKind.CONFLICT,
            ErrorDetails(fields = listOf(FieldError("kyc", "not verified on $networks"))),
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

    data object RoutingNotFound : RampError(
        "ramp.routing_not_found",
        "We couldn't find that routing decision.",
        ErrorKind.NOT_FOUND,
    )

    data object SettingsChanged : RampError(
        "ramp.settings_changed",
        "Someone else changed these settings. Read them again.",
        ErrorKind.CONFLICT,
    )

    // ---- selling (2026-11-23) ------------------------------------------------------------------------------

    /** Neither the provider's published payout fee nor staff's table covers the amount: a sale isn't quoted. */
    data object PayoutFeeUnavailable : RampError(
        "ramp.payout_fee_unavailable",
        "We can't price this sale right now. Try a different amount, or try again later.",
        ErrorKind.UPSTREAM,
    )

    /** A sale is past waiting for the user's USDC, or past what the user may still change. */
    data object OrderNotFundable : RampError(
        "ramp.order_not_fundable",
        "This sale can't take a transfer any more.",
        ErrorKind.CONFLICT,
    )

    /** A merchant asked for something that needs their unlocked payment account while they are offline. */
    data object MerchantOffline : RampError(
        "ramp.merchant_offline",
        "Go online with your PIN first: this uses your unlocked payment account.",
        ErrorKind.CONFLICT,
    )

    data object TestPayoutInProgress : RampError(
        "ramp.test_payout_in_progress",
        "A test payout is still going through. Check its result first.",
        ErrorKind.CONFLICT,
    )

    data object TestPayoutNotFound : RampError(
        "ramp.test_payout_not_found",
        "We couldn't find that test payout.",
        ErrorKind.NOT_FOUND,
    )

    /** The payout account id isn't one: FinoQ numbers accounts with digits. */
    data object PayoutAccountInvalid : RampError(
        "ramp.payout_account_invalid",
        "That account id isn't right: it is the number of your account on the FinoQ dashboard.",
        ErrorKind.INVALID,
        ErrorDetails(fields = listOf(FieldError("accountId", "digits only"))),
    )

    // ---- resolving an order (RO4) --------------------------------------------------------------------------

    /** The resolution doesn't apply to this order as it stands (not in review, nothing to refund, …). */
    class ResolutionInvalid(problem: String) :
        RampError(
            "ramp.resolution_invalid",
            "That can't be done to this order: $problem.",
            ErrorKind.CONFLICT,
        )

    data object ReceiptRequired : RampError(
        "ramp.receipt_required",
        "Marking an order paid needs the M-Pesa receipt you saw.",
        ErrorKind.INVALID,
        ErrorDetails(fields = listOf(FieldError("receipt", "required"))),
    )

    /** A refund staff asked for while the provider says the payout was paid, or may still be. */
    data object PayoutMayBePaid : RampError(
        "ramp.payout_may_be_paid",
        "The provider says this payout was paid or is still going through: it can't be refunded.",
        ErrorKind.CONFLICT,
    )
}
