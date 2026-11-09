package com.circaso.chama.presentation.events

import com.circaso.chama.domain.models.PaidCoin
import com.circaso.chama.domain.models.SubmittedContribution
import com.circaso.chama.domain.models.WalletTransfer
import com.circaso.chama.presentation.services.ChamaEventsService
import com.circaso.shared.DomainException
import com.circaso.shared.EventEnvelope
import com.circaso.shared.EventHandler
import com.circaso.shared.EventSubscription
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import com.circaso.shared.decode
import com.circaso.wallet.api.TransferConfirmed
import com.circaso.wallet.api.TransferSubmitted

/**
 * Chama's consumer group on `events:wallet`: a confirmed transfer may be a contribution into a group's wallet, or the
 * payment of an approved request from it; a submitted one into a group's wallet is announced to its members. Safe to run twice (a contribution is recorded once per transfer; a request moves once).
 */
class WalletEventHandler(private val events: ChamaEventsService) {
    private companion object {
        val DOLLAR_STABLECOINS = setOf("USDC", "USDT")
        const val USD_DECIMALS = 6
    }

    fun subscription() =
        EventSubscription(
            group = "chama",
            stream = EventEnvelope.streamOf("wallet"),
            handlers =
                mapOf(
                    TransferConfirmed.TYPE to EventHandler { transferConfirmed(it) },
                    TransferSubmitted.TYPE to EventHandler { transferSubmitted(it) },
                ),
        )

    private suspend fun transferConfirmed(envelope: EventEnvelope) {
        val event = envelope.decode<TransferConfirmed>()
        // The group counts dollars (2026-11-07: any held coin can be paid): a dollar stablecoin's own minor units, any
        // other coin's value at the price it was paid at. One the wallet couldn't price isn't counted.
        val symbol = event.amount.asset
        val dollars = symbol in DOLLAR_STABLECOINS
        val minor = (if (dollars) event.amount.amountMinor else event.usdValueMinor)?.toLongOrNull() ?: return
        val paid =
            event.assetDecimals?.let { decimals ->
                event.amount.amountMinor
                    .toBigIntegerOrNull()
                    ?.let { PaidCoin(symbol, decimals, it) }
            }
                ?: if (dollars) {
                    event.amount.amountMinor.toBigIntegerOrNull()?.let {
                        PaidCoin(
                            symbol,
                            USD_DECIMALS,
                            it,
                        )
                    }
                } else {
                    null
                }
        val transfer =
            WalletTransfer(
                transferId = event.transferId,
                senderId = event.senderId,
                amountMinor = minor,
                chatId = event.chatId,
                fromGroupId = event.fromGroupId,
                toGroupId = event.toGroupId,
                payoutRequestId = event.payoutRequestId?.let { Uuid7.parse(it) },
                paid = paid,
            )
        val result = events.transferConfirmed(transfer)
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }

    /** 2026-11-09: a payment into a group's wallet is on its way; its members hear it before the chain confirms it. */
    private suspend fun transferSubmitted(envelope: EventEnvelope) {
        val event = envelope.decode<TransferSubmitted>()
        val symbol = event.amount.asset
        val decimals = event.assetDecimals ?: USD_DECIMALS.takeIf { symbol in DOLLAR_STABLECOINS } ?: return
        val minor = event.amount.amountMinor.toBigIntegerOrNull() ?: return
        val usd = if (symbol in DOLLAR_STABLECOINS) event.amount.amountMinor else event.usdValueMinor
        val payment =
            SubmittedContribution(
                transferId = event.transferId,
                senderId = event.senderId,
                chatId = event.toGroupId,
                paid = PaidCoin(symbol, decimals, minor),
                usdMinor = usd?.toLongOrNull(),
            )
        val result = events.contributionSubmitted(payment)
        if (result is Resource.Error) throw DomainException(result.error, result.cause)
    }
}
