package com.circaso.wallet.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError
import java.time.Instant

/** The fee wallets' codes for staff (`fees.*`, decision 2026-11-18). A message never carries a secret. */
sealed class FeeWalletError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    /** A destination or a withdrawal staff sent that can't be taken; [details] names the field. */
    class Invalid(field: String, problem: String) :
        FeeWalletError(
            "fees.invalid",
            "That can't be saved: $field $problem.",
            ErrorKind.INVALID,
            ErrorDetails(fields = listOf(FieldError(field, problem))),
        )

    data object DestinationNotFound : FeeWalletError(
        "fees.destination_not_found",
        "There is no such destination on that chain.",
        ErrorKind.NOT_FOUND,
    )

    data object DestinationExists : FeeWalletError(
        "fees.destination_exists",
        "That address is already a destination on that chain.",
        ErrorKind.CONFLICT,
    )

    /** Sending fees to a fee wallet moves nothing out. */
    data object DestinationIsFeeWallet : FeeWalletError(
        "fees.destination_is_fee_wallet",
        "That address is one of Circaso's own fee wallets.",
        ErrorKind.INVALID,
    )

    /** The destination was added less than the delay ago. */
    class DestinationNotReady(usableAt: Instant) :
        FeeWalletError(
            "fees.destination_not_ready",
            "That destination can't be used until $usableAt.",
            ErrorKind.CONFLICT,
        )

    data object InsufficientBalance : FeeWalletError(
        "fees.insufficient_balance",
        "The fee wallet doesn't hold that much, counting withdrawals already on their way.",
        ErrorKind.CONFLICT,
    )

    /** The fee wallet pays its own network fee, in the chain's coin. */
    class InsufficientGas(symbol: String, blockchain: String) :
        FeeWalletError(
            "fees.insufficient_gas",
            "The fee wallet doesn't hold enough $symbol on $blockchain to pay the network fee. Send it some first.",
            ErrorKind.CONFLICT,
        )

    data object AssetUnsupported : FeeWalletError(
        "fees.asset_unsupported",
        "That coin can't be withdrawn from the fee wallet on that chain.",
        ErrorKind.INVALID,
    )

    /** Nothing can be withdrawn here; [why] says what is missing (no entity secret, no Circle wallet). */
    class WithdrawalsUnavailable(why: String) :
        FeeWalletError("fees.withdrawals_unavailable", "Withdrawals aren't available: $why", ErrorKind.CONFLICT)

    /** Circle refused the transfer: nothing was sent, and the withdrawal is recorded as failed. */
    class WithdrawalRefused(why: String) :
        FeeWalletError("fees.withdrawal_refused", "The withdrawal wasn't sent: $why", ErrorKind.CONFLICT)

    data object CustodyUnavailable : FeeWalletError(
        "fees.custody_unavailable",
        "Circle can't be reached right now. Try again in a moment.",
        ErrorKind.UPSTREAM,
    )
}
