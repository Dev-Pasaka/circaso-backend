package com.circaso.wallet.api

import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

/**
 * The `data` of each realtime frame `wallet` sends, one class per frame type (decision 2026-10-27). They live in the
 * contract so the gateway reads them typed when it words a push. A push never shows an amount on the lock screen.
 */
object WalletFrames {
    const val MODULE = "wallet"
    const val TRANSFER_UPDATED = "transfer.updated"
    const val PAYMENT_RECEIVED = "payment.received"
    const val WALLET_READY = "wallet.ready"
    const val SETUP_REQUESTED = "setup.requested"
}

/**
 * `wallet/transfer.updated`, to the sender (for a group wallet: the person who signs for it): the transfer moved to [state] (`SUBMITTED`, `CONFIRMED` or `FAILED`).
 * Pushed when offline on `CONFIRMED` and `FAILED` only.
 */
@Serializable
data class TransferUpdatedFrame(
    val transferId: TransferId,
    val state: String,
    val blockchain: String,
    val amount: WalletAmount,
    val txHash: String? = null,
    val failureReason: String? = null,
    /** The wallet it left when that is a group wallet (added 2026-11-03); null for your personal wallet. */
    val walletId: WalletId? = null,
)

/**
 * `wallet/payment.received`, to the recipient (for a group wallet: the person who signs for it; members hear of
 * contributions from chama's `contribution.added`), once the incoming transaction is confirmed on chain. [fromUserId] and
 * [fromName] are set when the sender is a Circaso user; null for an outside address.
 */
@Serializable
data class PaymentReceivedFrame(
    val transactionId: String,
    val blockchain: String,
    val amount: WalletAmount,
    val fromUserId: UserId? = null,
    val fromName: String? = null,
    /** The wallet that received it when that is a group or space wallet you sign for (added 2026-11-03). */
    val walletId: WalletId? = null,
    /** `GROUP` or `SPACE` when [walletId] is set (added 2026-11-05). */
    val ownerType: String? = null,
    /** For a space wallet: the space's name, so the push names it (a group's name is sealed and never here). */
    val spaceName: String? = null,
)

/**
 * `wallet/wallet.ready`, to the user, when the wallet turns `ACTIVE` (its first chain, [blockchain]). No push. For a
 * group wallet it goes to every member, with [walletId] set (added 2026-11-03).
 */
@Serializable
data class WalletReadyFrame(val blockchain: String, val walletId: WalletId? = null)

/** `wallet/setup.requested` (W10), to a contact without a wallet: [fromName] wants to pay them. Pushed. */
@Serializable
data class SetupRequestedFrame(val fromUserId: UserId, val fromName: String)
