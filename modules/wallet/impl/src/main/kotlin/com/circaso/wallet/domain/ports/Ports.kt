package com.circaso.wallet.domain.ports

import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.Holding

/**
 * `wal:balance:{circleWalletId}`: what one chain's wallet holds, for 15 seconds, so opening the wallet screen doesn't
 * call Circle every time (Wallet → Data → Redis). Cleared by any webhook for the wallet (WS3).
 */
interface BalanceCache {
    suspend fun get(circleWalletId: String): List<Holding>?

    suspend fun put(circleWalletId: String, holdings: List<Holding>)

    suspend fun clear(circleWalletId: String)
}

/**
 * `wal:adding:{userId}`: the chains the user asked to add (W12) and hasn't finished in the SDK, for an hour (a
 * challenge's session lasts 60 minutes). While one is listed, W1 asks Circle for the user's wallets so the new chain
 * shows up as soon as Circle has it.
 */
interface AddingChains {
    suspend fun add(userId: UserId, blockchain: String)

    suspend fun list(userId: UserId): Set<String>

    suspend fun remove(userId: UserId, blockchain: String)
}
