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

    /** W4 from an account that isn't `ACTIVE` (suspended, blocked, being deleted). */
    data object AccountRestricted : WalletError(
        "wallet.account_restricted",
        "Payments from this account are not allowed right now.",
        ErrorKind.FORBIDDEN,
    )

    /** W4 on a chain the wallet isn't on, or isn't active on (decision 2026-10-28: a transfer names its chain). */
    data object NotOnChain : WalletError(
        "wallet.not_on_chain",
        "Your wallet isn't on that blockchain. Add it from wallet management first.",
        ErrorKind.CONFLICT,
    )

    /** Outgoing payments are held after a support restore. TODO(WS4): `data.until`, once the hold is built. */
    data object OnHold : WalletError(
        "wallet.on_hold",
        "Payments from your wallet are on hold for a while after your account was restored.",
        ErrorKind.FORBIDDEN,
    )

    data object AmountInvalid : WalletError(
        "wallet.amount_invalid",
        "That amount can't be sent.",
        ErrorKind.INVALID,
    )

    data object InsufficientFunds : WalletError(
        "wallet.insufficient_funds",
        "Your wallet doesn't hold enough on that blockchain.",
        ErrorKind.INVALID,
    )

    data object LimitExceeded : WalletError(
        "wallet.limit_exceeded",
        "That's over your payment limit.",
        ErrorKind.INVALID,
    )

    data object RecipientNoWallet : WalletError(
        "wallet.recipient_no_wallet",
        "They haven't set up a wallet yet.",
        ErrorKind.CONFLICT,
    )

    /** The person has a wallet, but can't receive on the transfer's chain (not on it, or the chain is retired). */
    data object RecipientNotOnChain : WalletError(
        "wallet.recipient_not_on_chain",
        "They can't receive on that blockchain.",
        ErrorKind.CONFLICT,
    )

    data object AddressInvalid : WalletError(
        "wallet.address_invalid",
        "That isn't a valid address on this blockchain.",
        ErrorKind.INVALID,
    )

    data object SelfTransfer : WalletError(
        "wallet.self_transfer",
        "You can't pay your own wallet.",
        ErrorKind.INVALID,
    )

    data object TransferNotFound : WalletError(
        "wallet.transfer_not_found",
        "That payment doesn't exist.",
        ErrorKind.NOT_FOUND,
    )

    /** Circle locked PIN entry after wrong tries (30 minutes). */
    data object PinLocked : WalletError(
        "wallet.pin_locked",
        "PIN entry is locked for now. Try again later.",
        ErrorKind.INVALID,
    )

    /** Users' code, returned as is when reach fails (Wallet → Paying a person); it never says why. */
    data object NotReachable : WalletError(
        "users.not_reachable",
        "You can't reach that person.",
        ErrorKind.FORBIDDEN,
    )
}
