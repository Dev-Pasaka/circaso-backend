package com.circaso.wallet.presentation.api

import com.circaso.identity.api.DeletionGuard
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.api.Wallets
import com.circaso.wallet.presentation.services.WalletDirectoryService

/** wallet:api for the assistant later. Every call goes through a service, like any other way in. */
class WalletsImpl(private val directory: WalletDirectoryService) : Wallets {
    override suspend fun hasWallet(userId: UserId): Boolean = directory.hasWallet(userId)
}

/**
 * Identity asks before it schedules a deletion (I14); the wallet refuses while it holds 0.01 or more of a US dollar
 * stablecoin, or any amount of any other asset, on any chain, with its own `wallet.funds_remaining`, which reaches the
 * app as is (decision 2026-10-28).
 */
class WalletDeletionGuard(private val directory: WalletDirectoryService) : DeletionGuard {
    override suspend fun canDelete(userId: UserId): Resource<Unit> = directory.canDelete(userId)
}
