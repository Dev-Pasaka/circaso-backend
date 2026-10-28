package com.circaso.wallet.api

import com.circaso.shared.UserId

/** What other modules may ask the wallet. The assistant reads it later, for Asha's payment proposals (Phase B). */
interface Wallets {
    /** Whether [userId] has a wallet that is set up on at least one chain. */
    suspend fun hasWallet(userId: UserId): Boolean
}
