package com.circaso.wallet.domain.ports

import com.circaso.shared.Money
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.domain.models.PaymentHold
import java.util.UUID

/**
 * A frame the wallet tells a user about, in the domain's words; the data layer gives each its wire class from
 * `wallet:api`. Amounts travel in the frame (never in a push).
 */
sealed interface WalletFrame {
    data class TransferUpdated(
        val transferId: TransferId,
        val state: String,
        val blockchain: String,
        val amount: Money,
        val txHash: String?,
        val failureReason: String?,
        val walletId: WalletId? = null,
    ) : WalletFrame

    data class PaymentReceived(
        val transactionId: UUID,
        val blockchain: String,
        val amount: Money,
        val fromUserId: UserId?,
        val fromName: String?,
        val walletId: WalletId? = null,
    ) : WalletFrame

    data class WalletReady(val blockchain: String, val walletId: WalletId? = null) : WalletFrame

    data class SetupRequested(val fromUserId: UserId, val fromName: String) : WalletFrame
}

/**
 * Sends a wallet frame: the next `seq` from `wal:seq:{userId}`, kept 7 days in `wallet.realtime` for replay, then
 * the gateway (socket, or push for the frames worth one). Never inside a transaction; a frame that can't be sent is
 * logged and dropped, and the phone catches up from what was kept.
 */
interface FrameSender {
    suspend fun send(userId: UserId, frame: WalletFrame)
}

/** The frames `wallet` has sent, kept for 7 days so a phone that reconnects can catch up. */
interface RealtimeLog {
    /** Frames after [seq], oldest first. */
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    /** The latest `seq` handed out for this user, or null if none is remembered. */
    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)

/** `wal:remind:{from}:{to}`: W10 tells one contact once per sender per 24 hours. */
interface Reminders {
    /** True if this call took today's reminder for the pair. */
    suspend fun claim(from: UserId, to: UserId): Boolean
}

/**
 * The hold on outgoing payments after a support restore (`payment_holds`, decision 2026-10-28). Built in WS4; until
 * then nobody is on hold.
 */
interface PaymentHolds {
    suspend fun active(userId: UserId): PaymentHold?
}
