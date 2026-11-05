package com.circaso.wallet.domain.support

import com.circaso.wallet.domain.models.FeeState
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.ports.FeeConfig
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.FeeLedgerRepository
import java.math.RoundingMode

/**
 * The fee ledger follows its transfer (§7b "Fee address and accounting"): collected once the chain confirms it (the
 * fee leg went in the same transaction), void when it failed or expired unsigned. A confirmed payment records the
 * network cost the chain reported; when that came to more of the fee than its rule allows (25 %), it is flagged for
 * the reports. Called inside the transaction that moves the transfer.
 */
class FeeSettlement(
    private val ledger: FeeLedgerRepository,
    private val config: FeeConfig,
    private val warnings: WalletWarnings,
) {
    suspend fun follow(
        transfer: Transfer,
        next: TransferState,
        change: TransferChange,
    ) {
        val state =
            when (next) {
                TransferState.CONFIRMED -> FeeState.COLLECTED
                TransferState.FAILED, TransferState.EXPIRED -> FeeState.VOID
                else -> return
            }
        val native = change.networkFee?.takeIf { it.isNotBlank() && state == FeeState.COLLECTED }
        val gasMinor =
            native?.toBigDecimalOrNull()?.let { coins ->
                (
                    coins *
                        config.nativeUsd(
                            transfer.blockchain,
                        )
                ).movePointRight(MICRO).setScale(0, RoundingMode.CEILING).toLong()
            }
        val entry = ledger.settle(transfer.transferId, state, change.txHash, native, gasMinor, change.at) ?: return
        val fee = entry.fee.amountMinor
        if (gasMinor != null && fee > 0 && gasMinor * BPS > fee * entry.fee.gasShareMaxBps) {
            warnings.gasAboveShare(transfer.transferId.toString(), transfer.blockchain, (gasMinor * BPS / fee).toInt())
        }
    }

    private companion object {
        const val MICRO = 6
        const val BPS = 10_000L
    }
}
