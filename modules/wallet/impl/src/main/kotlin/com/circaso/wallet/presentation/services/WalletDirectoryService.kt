package com.circaso.wallet.presentation.services

import com.circaso.integrations.api.CustodyTransaction
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.ports.LoggedFrame
import com.circaso.wallet.domain.usecases.ApplyCustodyUpdate
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.ReplayFrames

/**
 * What other parts of the system ask or tell the wallet: its `api`, identity's `DeletionGuard`, integrations'
 * `CustodyNotifications` and the gateway's replay after a reconnect.
 */
class WalletDirectoryService(
    private val hasWallet: HasWallet,
    private val checkFundsBeforeDeletion: CheckFundsBeforeDeletion,
    private val applyCustodyUpdate: ApplyCustodyUpdate,
    private val replayFrames: ReplayFrames,
) {
    suspend fun hasWallet(userId: UserId): Boolean = hasWallet.invoke(userId)

    suspend fun canDelete(userId: UserId): Resource<Unit> = checkFundsBeforeDeletion(userId)

    suspend fun onTransaction(update: CustodyTransaction): Resource<Unit> = applyCustodyUpdate(update)

    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)
}
