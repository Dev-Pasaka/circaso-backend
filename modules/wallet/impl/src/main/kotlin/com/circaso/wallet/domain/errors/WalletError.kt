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

    /**
     * Over a limit rule (§7a). `details.fields` carries the numbers, each `{field, problem}` pair a name and its
     * value: `limit` (`PER_PAYMENT` or `DAILY`), `limitMinor`, `usedMinor`, `leftMinor`, `maxAmountMinor` (the most
     * that fits with its fee), `resetsAt` (when this payment fits; absent when it never can) and `moreAt` (when more
     * becomes available), amounts in USDC minor units.
     */
    class LimitExceeded(details: ErrorDetails? = null) :
        WalletError(
            "wallet.limit_exceeded",
            "That's over this wallet's payment limit.",
            ErrorKind.INVALID,
            details,
        )

    /**
     * The wallet holds the amount but not its fee as well, or not even the amount (GW20). `details.fields`:
     * `balanceMinor`, `feeMinor`, `maxAmountMinor` (the most that can be sent with its fee).
     */
    class FundsShort(details: ErrorDetails) :
        WalletError(
            "wallet.insufficient_funds",
            "Your wallet doesn't hold enough for this payment and its fee.",
            ErrorKind.INVALID,
            details,
        )

    /**
     * W4's fee is more than the one the person was shown: the quote expired (15 minutes) or none was sent (GW13c).
     * Nothing was prepared. `details.fields`: `quotedFeeMinor`, `feeMinor`, `totalMinor`, and `quoteId` and
     * `expiresAt` of a new quote for the new fee: show it, then send W4 again with that `quoteId` and a new key.
     */
    class FeeChanged(details: ErrorDetails) :
        WalletError(
            "wallet.fee_changed",
            "The fee changed since you saw it. Check the new total before you pay.",
            ErrorKind.CONFLICT,
            details,
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

    /**
     * W2, W12, W13 for a support account, and W4, W9, W10 with one on either side (decision 2026-10-31): Circaso's
     * support accounts never hold a wallet, so they can't pay or be paid.
     */
    data object SupportAccountHasNoWallet : WalletError(
        "wallet.support_account_has_no_wallet",
        "Circaso support doesn't have a wallet, so it can't send or receive money.",
        ErrorKind.FORBIDDEN,
    )

    /** Users' code, returned as is when reach fails (Wallet → Paying a person); it never says why. */
    data object NotReachable : WalletError(
        "users.not_reachable",
        "You can't reach that person.",
        ErrorKind.FORBIDDEN,
    )

    // ---- group wallets (Spaces phase 2, decision 2026-11-03) ----

    data object GroupNotFound : WalletError(
        "wallet.group_not_found",
        "We couldn't find that group.",
        ErrorKind.NOT_FOUND,
    )

    data object NotGroupOwner : WalletError(
        "wallet.not_group_owner",
        "Only the group's owner can set up its wallet.",
        ErrorKind.FORBIDDEN,
    )

    data object NotSigner : WalletError(
        "wallet.not_signer",
        "Only the wallet's owner can send from it or change it.",
        ErrorKind.FORBIDDEN,
    )

    data object SignerLeft : WalletError(
        "wallet.signer_left",
        "The person who holds this wallet's PIN has left the group, so nothing can be sent from it for now.",
        ErrorKind.CONFLICT,
    )

    data object PayoutRequired : WalletError(
        "wallet.payout_required",
        "Money leaves a group wallet only for a payout the admins approved.",
        ErrorKind.INVALID,
    )

    data object PayoutNotApproved : WalletError(
        "wallet.payout_not_approved",
        "This payout isn't approved, or it has already been paid.",
        ErrorKind.CONFLICT,
    )

    data object PayoutMismatch : WalletError(
        "wallet.payout_mismatch",
        "This payment doesn't match the approved payout (who it's to or the amount).",
        ErrorKind.INVALID,
    )

    data object GroupFundsRemaining : WalletError(
        "wallet.group_funds_remaining",
        "A group wallet you hold the PIN for still holds money. Pay it out before deleting the account.",
        ErrorKind.CONFLICT,
    )

    data object PayoutInProgress : WalletError(
        "wallet.payout_in_progress",
        "This payout is already being paid.",
        ErrorKind.CONFLICT,
    )

    // ---- space wallets (Spaces phase 3, decision 2026-11-04) ----

    /**
     * Spaces is archived (decision 2026-11-05): with `SPACES_ENABLED` off, W18 and W19 answer this. Spaces' own code on
     * purpose, so the app sees one code for the whole archived feature.
     */
    data object SpacesUnavailable : WalletError(
        "spaces.unavailable",
        "Spaces isn't available.",
        ErrorKind.NOT_FOUND,
    )

    data object SpaceNotFound : WalletError(
        "wallet.space_not_found",
        "We couldn't find that space.",
        ErrorKind.NOT_FOUND,
    )

    data object NotSpaceOwner : WalletError(
        "wallet.not_space_owner",
        "Only the space's owner can set up its wallet.",
        ErrorKind.FORBIDDEN,
    )

    data object SpaceNotMember : WalletError(
        "wallet.space_not_member",
        "Only people in this space can pay it.",
        ErrorKind.FORBIDDEN,
    )

    data object SpacePaymentInvalid : WalletError(
        "wallet.space_payment_invalid",
        "This membership payment is no longer valid. Start again.",
        ErrorKind.CONFLICT,
    )

    data object SpacePaymentPersonalOnly : WalletError(
        "wallet.space_payment_personal_only",
        "A membership is paid from your personal wallet.",
        ErrorKind.INVALID,
    )

    data object IconSymbolInvalid : WalletError(
        "wallet.icon_symbol_invalid",
        "A coin symbol is 1 to 15 letters, digits or dots.",
        ErrorKind.INVALID,
    )

    data object IconNotFound : WalletError(
        "wallet.icon_not_found",
        "We don't have an icon for that coin.",
        ErrorKind.NOT_FOUND,
    )
}
