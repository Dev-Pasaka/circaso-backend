package com.circaso.wallet.domain.support

import com.circaso.shared.EventBus
import com.circaso.shared.EventEnvelope
import com.circaso.shared.envelope
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferFailed
import com.circaso.wallet.domain.models.Transfer
import java.math.BigDecimal

/**
 * The events that end a transfer (Wallet → Events → Published), built before the transaction that writes their
 * outbox row: building an envelope is pure, publishing waits for the commit. Exactly one per transfer, written only by
 * the operation whose transition guard moved the transfer to its final state.
 */
class TransferEvents(private val bus: EventBus) {
    fun confirmed(transfer: Transfer): EventEnvelope =
        bus.envelope(
            TransferConfirmed(
                transferId = transfer.transferId,
                senderId = transfer.senderId,
                recipientId = transfer.recipientId,
                blockchain = transfer.blockchain,
                amount = Amounts.of(transfer.amount),
                origin = transfer.origin.name,
                proposalId = transfer.proposalId?.toString(),
                chatId = transfer.chatId,
                fromGroupId = transfer.fromGroupId,
                toGroupId = transfer.toGroupId,
                payoutRequestId = transfer.payoutRequestId?.toString(),
                signerId = transfer.signer,
                fromSpaceId = transfer.fromSpaceId?.toString(),
                toSpaceId = transfer.toSpaceId?.toString(),
                spacePaymentId = transfer.spacePaymentId?.toString(),
                usdValueMinor = usdValueMinor(transfer),
                assetDecimals = transfer.amount.asset.decimals,
            ),
        )

    /**
     * The amount in micro-dollars at the price it was paid at (2026-11-07): one dollar for a dollar stablecoin
     * (and every transfer from before, which were USDC), null for another coin whose price wasn't kept.
     */
    private fun usdValueMinor(transfer: Transfer): String? {
        val price =
            transfer.usdPrice ?: if (transfer.amount.asset.isDollarStablecoin) BigDecimal.ONE else return null
        return PaymentCoin(transfer.amount.asset, transfer.tokenAddress.orEmpty(), price)
            .usdMinor(transfer.amount.amountMinor)
            .toString()
    }

    fun failed(transfer: Transfer, reason: String?): EventEnvelope =
        bus.envelope(
            TransferFailed(
                transfer.transferId,
                transfer.senderId,
                reason ?: FAILED_WITHOUT_REASON,
                fromGroupId = transfer.fromGroupId,
                payoutRequestId = transfer.payoutRequestId?.toString(),
                spacePaymentId = transfer.spacePaymentId?.toString(),
            ),
        )

    private companion object {
        const val FAILED_WITHOUT_REASON = "FAILED"
    }
}
