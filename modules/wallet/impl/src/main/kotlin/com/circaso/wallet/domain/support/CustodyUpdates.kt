package com.circaso.wallet.domain.support

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyOperation
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.ports.BalanceCache
import com.circaso.wallet.domain.ports.FeeConfig
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.WalletRepository
import java.time.Clock
import java.util.UUID

/**
 * Circle's word on a transaction, applied (Wallet → Events → From Circle). The one path for it: the webhook
 * (`ApplyCustodyUpdate`) and the jobs that read Circle when a webhook was lost (`CheckStuckTransfers`,
 * `ReconcileWallets`) all come through [apply], so the transfer, its rows, its event, the frames and the push are the
 * same whichever told us first, and the transition guard makes the second one change nothing.
 *
 * An outgoing update finds its transfer by `refId`; an incoming one finds the receiving wallet by Circle's wallet id.
 * A wallet we haven't recorded yet (the user just finished setting it up, and the money came first) is looked up at
 * Circle by its user before giving up, which also turns the wallet `ACTIVE`.
 */
class CustodyUpdates(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val outgoing: OutgoingUpdates,
    private val incoming: IncomingUpdates,
    private val cache: BalanceCache,
    private val warnings: WalletWarnings,
    private val clock: Clock,
    /** Circaso's fee wallets (phase 2F): their incoming fee legs are ours to ignore, not an unknown wallet's. */
    private val feeConfig: FeeConfig? = null,
    /** Finds a split payment's transfer from its batch, which carries no `refId`. */
    private val splits: SplitPayments? = null,
) {
    suspend fun apply(update: CustodyTransaction): Resource<Unit> {
        // Circaso's own wallets (the fee wallets): their legs are settled by the payment's own update.
        if (update.developerWallet) return Resource.success(Unit)
        val change = changeOf(update)
        val next = stateOf(update.state)
        if (update.operation == CustodyOperation.CONTRACT_EXECUTION) return applyExecution(update, next, change)
        if (update.direction == CustodyDirection.OUT && outgoing.applyToTransfer(update, next, change)) {
            cache.clear(update.walletId)
            return Resource.success(Unit)
        }

        val wallet =
            when (val found = walletOf(update)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (wallet == null) {
            if (isFeeLeg(update)) return Resource.success(Unit)
            warnings.unknownWallet(update.blockchain, update.walletId)
            return Resource.success(Unit)
        }
        when (update.direction) {
            CustodyDirection.IN -> incoming.apply(wallet, update, next, change)
            CustodyDirection.OUT -> outgoing.applyUntracked(wallet, update, next, change)
        }
        cache.clear(wallet.circleWalletId)
        return Resource.success(Unit)
    }

    /**
     * An outgoing contract execution: a split payment's batch moves its transfer exactly as a plain transfer's update
     * does ([OutgoingUpdates.applyToTransfer]: the state, the `OUT` row, the fee ledger, the event, the frames). Found
     * by `refId` if Circle kept one, else by [SplitPayments]. One that pays no transfer of ours (any other contract
     * call) is left alone: it has no amount to put in the history.
     */
    private suspend fun applyExecution(
        update: CustodyTransaction,
        next: TransferState,
        change: TransferChange,
    ): Resource<Unit> {
        val refId =
            update.refId.ifEmpty {
                when (val found = splits?.transferOf(update)) {
                    null -> null
                    is Resource.Error -> return found
                    is Resource.Success -> found.data?.transferId?.toString()
                }
            } ?: return Resource.success(Unit)
        if (outgoing.applyToTransfer(update.copy(refId = refId), next, change)) cache.clear(update.walletId)
        return Resource.success(Unit)
    }

    /** Our wallet by Circle's id, recording the user's new wallets from Circle first when we don't know it yet. */
    private suspend fun walletOf(update: CustodyTransaction): Resource<ChainWallet?> {
        wallets.wallet(update.walletId)?.let { return Resource.success(it) }
        val circleUserId = runCatching { UUID.fromString(update.circleUserId) }.getOrNull()
        val user = circleUserId?.let { wallets.circleUserByCircleId(it) } ?: return Resource.success(null)
        return when (val synced = sync.sync(user)) {
            is Resource.Success -> Resource.success(synced.data.firstOrNull { it.circleWalletId == update.walletId })
            is Resource.Error -> synced
        }
    }

    private fun isFeeLeg(update: CustodyTransaction): Boolean =
        update.direction == CustodyDirection.IN &&
            feeConfig?.feeAddress(update.blockchain)?.let { Addresses.normalise(it) } ==
            Addresses.normalise(update.destinationAddress)

    private fun changeOf(update: CustodyTransaction): TransferChange {
        val failed = stateOf(update.state) == TransferState.FAILED
        return TransferChange(
            circleTxId = update.circleTxId.ifEmpty { null },
            txHash = update.txHash.ifEmpty { null },
            failureReason = if (failed) update.failureReason.ifEmpty { FAILED_WITHOUT_REASON } else null,
            at = clock.instant(),
            networkFee = update.networkFee.ifEmpty { null },
        )
    }

    private companion object {
        const val FAILED_WITHOUT_REASON = "FAILED"

        /** Circle's `STUCK` is shown as submitted; `CheckStuckTransfers` looks after it. */
        fun stateOf(state: CustodyTransactionState): TransferState =
            when (state) {
                CustodyTransactionState.SUBMITTED, CustodyTransactionState.STUCK -> TransferState.SUBMITTED
                CustodyTransactionState.CONFIRMED -> TransferState.CONFIRMED
                CustodyTransactionState.FAILED -> TransferState.FAILED
            }
    }
}
