package com.circaso.wallet.data.cache

import com.circaso.shared.Network
import com.circaso.shared.RealtimeEnvelope
import com.circaso.wallet.api.PaymentReceivedFrame
import com.circaso.wallet.api.SetupRequestedFrame
import com.circaso.wallet.api.TransferUpdatedFrame
import com.circaso.wallet.api.WalletFrames
import com.circaso.wallet.api.WalletReadyFrame
import com.circaso.wallet.domain.ports.WalletFrame
import com.circaso.wallet.domain.support.Amounts
import java.time.Instant

/**
 * The envelope for a frame the domain names, at [seq]: its wire `type` and its class from `wallet:api`, so the wire
 * shape is fixed in one place (amounts as strings, decision 2026-10-28). A frame that names a chain carries its
 * network from it; one that names none (`setup.requested`) takes [network], the one the sender's request was for.
 */
internal fun WalletFrame.toEnvelope(
    seq: Long,
    ts: Instant,
    network: Network = Network.TESTNET,
): RealtimeEnvelope =
    when (this) {
        is WalletFrame.TransferUpdated -> {
            RealtimeEnvelope.of(
                WalletFrames.MODULE,
                WalletFrames.TRANSFER_UPDATED,
                seq,
                TransferUpdatedFrame(
                    transferId,
                    state,
                    blockchain,
                    Amounts.of(amount),
                    txHash,
                    failureReason,
                    walletId,
                ),
                TransferUpdatedFrame.serializer(),
                ts,
            )
        }

        is WalletFrame.PaymentReceived -> {
            RealtimeEnvelope.of(
                WalletFrames.MODULE,
                WalletFrames.PAYMENT_RECEIVED,
                seq,
                PaymentReceivedFrame(
                    transactionId.toString(),
                    blockchain,
                    Amounts.of(amount),
                    fromUserId,
                    fromName,
                    walletId,
                    ownerType,
                    spaceName,
                    transferId,
                    chatId?.toString(),
                ),
                PaymentReceivedFrame.serializer(),
                ts,
            )
        }

        is WalletFrame.WalletReady -> {
            RealtimeEnvelope.of(
                WalletFrames.MODULE,
                WalletFrames.WALLET_READY,
                seq,
                WalletReadyFrame(blockchain, walletId),
                WalletReadyFrame.serializer(),
                ts,
            )
        }

        is WalletFrame.SetupRequested -> {
            RealtimeEnvelope.of(
                WalletFrames.MODULE,
                WalletFrames.SETUP_REQUESTED,
                seq,
                SetupRequestedFrame(fromUserId, fromName, network.name),
                SetupRequestedFrame.serializer(),
                ts,
            )
        }
    }
