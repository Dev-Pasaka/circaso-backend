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
 * `wallet/transfer.updated`, to the sender: the transfer moved to [state] (`SUBMITTED`, `CONFIRMED` or `FAILED`).
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
)

/**
 * `wallet/payment.received`, to the recipient, once the incoming transaction is confirmed on chain. [fromUserId] and
 * [fromName] are set when the sender is a Circaso user; null for an outside address.
 */
@Serializable
data class PaymentReceivedFrame(
    val transactionId: String,
    val blockchain: String,
    val amount: WalletAmount,
    val fromUserId: UserId? = null,
    val fromName: String? = null,
)

/** `wallet/wallet.ready`, to the user, when the wallet turns `ACTIVE` (its first chain, [blockchain]). No push. */
@Serializable
data class WalletReadyFrame(val blockchain: String)

/** `wallet/setup.requested` (W10), to a contact without a wallet: [fromName] wants to pay them. Pushed. */
@Serializable
data class SetupRequestedFrame(val fromUserId: UserId, val fromName: String)
