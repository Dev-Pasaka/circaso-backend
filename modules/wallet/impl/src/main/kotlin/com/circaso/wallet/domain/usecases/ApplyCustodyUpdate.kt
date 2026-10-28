package com.circaso.wallet.domain.usecases

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.shared.Resource
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.TransferChange
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.ports.BalanceCache
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.IncomingUpdates
import com.circaso.wallet.domain.support.OutgoingUpdates
import com.circaso.wallet.domain.support.WalletSync
import java.time.Clock
import java.util.UUID

/**
 * Integrations' `CustodyNotifications`: Circle's word on a transaction, from its webhook (Wallet → Events → From
 * Circle). An outgoing one finds its transfer by `refId`; an incoming one finds the receiving wallet by Circle's
 * wallet id. Either applies the state through the transition guard, so a repeated or late update changes nothing,
 * clears the wallet's cached balance and tells the user.
 *
 * A wallet we haven't recorded yet (the user just finished setting it up, and the money came first) is looked up at
 * Circle by its user before giving up, which also turns the wallet `ACTIVE`. An error makes the webhook answer
 * non-200, so Circle delivers the update again.
 */
class ApplyCustodyUpdate(
    private val wallets: WalletRepository,
    private val sync: WalletSync,
    private val outgoing: OutgoingUpdates,
    private val incoming: IncomingUpdates,
    private val cache: BalanceCache,
    private val warnings: WalletWarnings,
    private val clock: Clock,
) {
    suspend operator fun invoke(update: CustodyTransaction): Resource<Unit> {
        val change = changeOf(update)
        val next = stateOf(update.state)
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

    private fun changeOf(update: CustodyTransaction): TransferChange {
        val failed = stateOf(update.state) == TransferState.FAILED
        return TransferChange(
            circleTxId = update.circleTxId.ifEmpty { null },
            txHash = update.txHash.ifEmpty { null },
            failureReason = if (failed) update.failureReason.ifEmpty { FAILED_WITHOUT_REASON } else null,
            at = clock.instant(),
        )
    }

    /** Circle's `STUCK` is shown as submitted; the stuck-transfer job (WS4) looks after it. */
    private fun stateOf(state: CustodyTransactionState): TransferState =
        when (state) {
            CustodyTransactionState.SUBMITTED, CustodyTransactionState.STUCK -> TransferState.SUBMITTED
            CustodyTransactionState.CONFIRMED -> TransferState.CONFIRMED
            CustodyTransactionState.FAILED -> TransferState.FAILED
        }

    private companion object {
        const val FAILED_WITHOUT_REASON = "FAILED"
    }
}
