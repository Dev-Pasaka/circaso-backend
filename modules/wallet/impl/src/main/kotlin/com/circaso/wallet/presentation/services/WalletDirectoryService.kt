package com.circaso.wallet.presentation.services

import com.circaso.integrations.api.CustodyChallenge
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.SpaceWalletSummary
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.domain.ports.LoggedFrame
import com.circaso.wallet.domain.usecases.ApplyCustodyUpdate
import com.circaso.wallet.domain.usecases.ApplyWalletChallenge
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.CheckSpaceWalletFunds
import com.circaso.wallet.domain.usecases.FindGroupWallet
import com.circaso.wallet.domain.usecases.FindSpaceWallet
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.LookupTransfer
import com.circaso.wallet.domain.usecases.ReplayFrames
import java.util.UUID

/**
 * What other parts of the system ask or tell the wallet: its `api`, identity's `DeletionGuard`, integrations'
 * `CustodyNotifications` and the gateway's replay after a reconnect.
 */
class WalletDirectoryService(
    private val hasWallet: HasWallet,
    private val checkFundsBeforeDeletion: CheckFundsBeforeDeletion,
    private val applyCustodyUpdate: ApplyCustodyUpdate,
    private val replayFrames: ReplayFrames,
    private val lookupTransfer: LookupTransfer,
    private val findGroupWallet: FindGroupWallet,
    private val findSpaceWallet: FindSpaceWallet,
    private val checkSpaceWalletFunds: CheckSpaceWalletFunds,
    private val applyWalletChallenge: ApplyWalletChallenge,
) {
    suspend fun hasWallet(userId: UserId): Boolean = hasWallet.invoke(userId)

    suspend fun transfer(transferId: TransferId): TransferSummary? = lookupTransfer(transferId)

    suspend fun groupWallet(chatId: ChatId): GroupWalletSummary? = findGroupWallet(chatId)

    suspend fun spaceWallet(spaceId: UUID): SpaceWalletSummary? = findSpaceWallet(spaceId)

    suspend fun spaceWalletHoldsMoney(spaceId: UUID): Resource<Boolean> = checkSpaceWalletFunds(spaceId)

    suspend fun canDelete(userId: UserId): Resource<Unit> = checkFundsBeforeDeletion(userId)

    suspend fun onTransaction(update: CustodyTransaction): Resource<Unit> = applyCustodyUpdate(update)

    suspend fun onWalletChallenge(challenge: CustodyChallenge): Resource<Unit> = applyWalletChallenge(challenge)

    suspend fun missedFrames(userId: UserId, seq: Long): Resource<List<LoggedFrame>?> = replayFrames(userId, seq)
}
