package com.circaso.wallet.domain.repositories

import com.circaso.wallet.domain.models.CoinIcon

/** The vendored coin icons, by upper-case symbol (`coin-icons/` in this module's resources). */
interface CoinIconRepository {
    /** Null for a symbol the set doesn't have. */
    suspend fun find(symbol: String): CoinIcon?
}
