package com.circaso.wallet.domain.repositories

import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.CircleUser

/** `circle_users` and `wallets`: the wallet and the chains it is on. */
interface WalletRepository {
    suspend fun circleUser(userId: UserId): CircleUser?

    /** Inserts [user] unless the account already has a Circle user; true if this call inserted it. */
    suspend fun insertCircleUser(user: CircleUser): Boolean

    /** The user's wallets on every chain, oldest first. */
    suspend fun wallets(userId: UserId): List<ChainWallet>

    /**
     * Inserts the wallet on one chain unless it is already recorded (same Circle wallet id, same user and chain, or
     * same address on that chain); true if this call inserted it, which is what announces it.
     */
    suspend fun insertWallet(wallet: ChainWallet): Boolean
}

/** Wallet's outbox rows, written inside the transaction that makes the change. */
interface WalletOutbox {
    suspend fun write(events: List<EventEnvelope>)
}
