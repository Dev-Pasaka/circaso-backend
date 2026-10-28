package com.circaso.wallet.domain.errors

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind

/** Wallet's own codes (Wallet → Error codes), added as their slices arrive. */
sealed class WalletError(
    override val code: String,
    override val message: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    data object NotFound : WalletError(
        "wallet.not_found",
        "You don't have a wallet yet.",
        ErrorKind.NOT_FOUND,
    )

    data object AlreadyExists : WalletError(
        "wallet.already_exists",
        "Your wallet is already set up.",
        ErrorKind.CONFLICT,
    )

    data object NotReady : WalletError(
        "wallet.not_ready",
        "Your wallet is still being set up. Finish setting your PIN first.",
        ErrorKind.CONFLICT,
    )

    data object ChainUnsupported : WalletError(
        "wallet.chain_unsupported",
        "That blockchain isn't supported.",
        ErrorKind.INVALID,
    )

    data object FundsRemaining : WalletError(
        "wallet.funds_remaining",
        "Your wallet still holds money. Send it out before deleting the account.",
        ErrorKind.CONFLICT,
    )

    data object CustodyUnavailable : WalletError(
        "wallet.custody_unavailable",
        "Wallets are unavailable right now. Try again in a moment.",
        ErrorKind.UPSTREAM,
    )
}
