package com.circaso.wallet.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.HasWallet

/** What other modules ask the wallet through its `api` and identity's `DeletionGuard`. */
class WalletDirectoryService(
    private val hasWallet: HasWallet,
    private val checkFundsBeforeDeletion: CheckFundsBeforeDeletion,
) {
    suspend fun hasWallet(userId: UserId): Boolean = hasWallet.invoke(userId)

    suspend fun canDelete(userId: UserId): Resource<Unit> = checkFundsBeforeDeletion(userId)
}
